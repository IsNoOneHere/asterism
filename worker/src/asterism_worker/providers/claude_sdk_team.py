import json
import logging
import os
import re
import shutil
import stat
import subprocess
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from claude_agent_sdk import (
    AgentDefinition,
    AssistantMessage,
    ClaudeAgentOptions,
    HookMatcher,
    ResultMessage,
    TERMINAL_TASK_STATUSES,
    TaskNotificationMessage,
    TaskStartedMessage,
    TaskUpdatedMessage,
    TextBlock,
    ToolResultBlock,
    ToolUseBlock,
    UserMessage,
    query,
)

from asterism_worker.activities.execution_support import changed_paths
from asterism_worker.agent_config import AgentConstraints, EngineConfig, ModelProfile
from asterism_worker.contracts import (
    CodingAttemptRequest,
    CodingAttemptResult,
    CodingPlanRequest,
    ExecutionOutcome,
    RepoChangeResult,
    RepoSnapshot,
    SubagentRun,
)
from asterism_worker.providers.claude_sdk_planning import ClaudeSdkPlanningMixin
from asterism_worker.providers.session_store import JsonlSessionStore
from asterism_worker.repo_source import TeamWorkspace, reset_team_workspace

log = logging.getLogger(__name__)
SUPERVISOR_TOOLS = ["Read", "Glob", "Grep", "Edit", "Write", "Agent", "TaskOutput"]
SUBAGENT_TOOLS = ["Read", "Glob", "Grep", "Edit", "Write"]
# SDK 顶层工具是所有子 Agent 的能力上限；实际使用权限仍由 AgentPolicy 逐次裁决。
TEAM_TOOLS = list(dict.fromkeys([*SUPERVISOR_TOOLS, *SUBAGENT_TOOLS]))
# 验证命令由外层 Workflow 统一执行；Coding 会话不暴露 Bash，避免一次拒绝中断整个原生子 Agent。
DISALLOWED_TOOLS = ["Bash", "WebSearch", "WebFetch"]
WRITE_TOOLS = {"Edit", "Write"}
CLAUDE_UID = 65534
CLAUDE_GID = 65534


@dataclass(frozen=True, slots=True)
class AgentPolicy:
    agent_type: str
    repo: str
    readable_roots: tuple[Path, ...]
    writable_roots: tuple[Path, ...]
    allowed_paths: tuple[str, ...] = ()
    forbidden_paths: tuple[str, ...] = ()
    test_commands: tuple[str, ...] = ()
    can_delegate: bool = False


@dataclass(frozen=True, slots=True)
class TeamAgentSpec:
    name: str
    repo: RepoSnapshot
    policy: AgentPolicy


class ClaudeSdkTeamProvider(ClaudeSdkPlanningMixin):
    """单一 Claude SDK Root Supervisor；原生子 Agent 仅作可选内部协作。"""

    def __init__(
        self,
        model_profile: ModelProfile,
        engine_options: EngineConfig,
        artifacts_root: str,
        supervisor: AgentConstraints,
        callbacks: dict[str, Any] | None = None,
    ) -> None:
        if not model_profile.api_key:
            raise RuntimeError("claude_sdk_team 缺少模型 Profile API key")
        callbacks = callbacks or {}
        self.model_profile = model_profile
        self.engine_options = engine_options
        self.artifacts_root = Path(artifacts_root)
        self.supervisor = supervisor
        self.event_callback = callbacks.get("event")
        self.candidate_callback = callbacks.get("candidate_checkpoint")
        self.write_guard = callbacks.get("write_guard")
        self.query = callbacks.get("query") or query
        self.session_store = JsonlSessionStore(self.artifacts_root / "sdk-sessions")
        model = model_profile.model.strip()
        self.model_env = {
            key: model
            for key in (
                "ANTHROPIC_MODEL",
                "ANTHROPIC_DEFAULT_OPUS_MODEL",
                "ANTHROPIC_DEFAULT_SONNET_MODEL",
                "ANTHROPIC_DEFAULT_HAIKU_MODEL",
                "CLAUDE_CODE_SUBAGENT_MODEL",
            )
            if model
        }
        if engine_options.effort_level:
            self.model_env["CLAUDE_CODE_EFFORT_LEVEL"] = engine_options.effort_level

    async def run(self, request: CodingAttemptRequest, workspace: TeamWorkspace) -> CodingAttemptResult:
        self._guard_write()
        specs = self._agent_specs(request, workspace)
        policies = {spec.name: spec.policy for spec in specs}
        supervisor_policy = AgentPolicy("developer", "", (workspace.root,), (), can_delegate=True)
        readonly_policy = AgentPolicy("readonly", "", (workspace.root,), ())
        agent_ids: dict[str, str] = {}
        runs: dict[str, SubagentRun] = {}
        active_tasks: set[str] = set()
        runtime_dir = self._runtime_dir(workspace)
        settings_path = runtime_dir / "settings.json"
        settings_path.write_text("{}\n", encoding="utf-8")
        context_path = workspace.root / "CLAUDE.md"
        context_path.write_text(self._context(request, specs), encoding="utf-8")
        transcript = self._transcript_path(request)
        # 同一 Case 的初始执行与多轮修订共用一份轨迹，追加边界记录以保留完整审计链。
        self._write(transcript, self._attempt_start(request))

        def authorize_runtime_tool(
            agent_id: str, agent_type: str, tool_name: str, tool_input: dict[str, Any],
        ) -> tuple[bool, str]:
            is_supervisor = not agent_id and (
                not agent_type or agent_type in {"developer", self.supervisor.role_id}
            )
            if is_supervisor and tool_name in WRITE_TOOLS:
                target = self._tool_path(tool_input, workspace.root)
                if target is None:
                    return False, "写操作缺少目标路径"
                repo_policy = next(
                    (spec.policy for spec in specs if self._within(target, spec.policy.writable_roots[0])),
                    None,
                )
                if repo_policy is None:
                    return False, "Root Supervisor 只能修改当前 Coding Attempt 的仓库"
                # Root 直接写仍复用仓库级 allowedPaths/forbiddenPaths 门禁。
                return self._authorize(repo_policy, tool_name, tool_input, workspace.root)
            policy = supervisor_policy if is_supervisor else policies.get(agent_type, readonly_policy)
            return self._authorize(policy, tool_name, tool_input, workspace.root)

        async def subagent_start(data: dict, _tool_use_id: str | None, _context: object) -> dict:
            agent_id = str(data.get("agent_id", ""))
            agent_type = str(data.get("agent_type", ""))
            if agent_id and agent_type:
                agent_ids[agent_id] = agent_type
                policy = policies.get(agent_type, readonly_policy)
                runs[agent_id] = SubagentRun(
                    agent_id=agent_id,
                    agent_type=agent_type,
                    repo=policy.repo,
                    status="running",
                )
                self._write(transcript, {"type": "subagent_start", **runs[agent_id].model_dump()})
                self._heartbeat("SubagentStart", agent_type)
            return {}

        async def subagent_stop(data: dict, _tool_use_id: str | None, _context: object) -> dict:
            agent_id = str(data.get("agent_id", ""))
            current = runs.get(agent_id)
            if current:
                # Stop 只是 SDK 遥测，不能代替 Root Supervisor 的顶层完成声明。
                runs[agent_id] = current.model_copy(update={"status": "stopped"})
                self._write(transcript, {"type": "subagent_stop", **runs[agent_id].model_dump()})
                self._heartbeat("SubagentStop", current.agent_type)
            return {}

        async def pre_tool_use(data: dict, _tool_use_id: str | None, _context: object) -> dict:
            agent_id = str(data.get("agent_id", ""))
            agent_type = str(data.get("agent_type", "")) or agent_ids.get(agent_id, "")
            tool_name = str(data.get("tool_name", ""))
            tool_input = dict(data.get("tool_input") or {})
            if tool_name in WRITE_TOOLS:
                self._guard_write()
            allowed, reason = authorize_runtime_tool(agent_id, agent_type, tool_name, tool_input)
            permission_record = {
                "type": "tool_permission",
                "agent": agent_type or "developer",
                "tool": tool_name,
                "target": self._tool_target(tool_input),
                "allowed": allowed,
                **({"reason": reason} if reason else {}),
            }
            if tool_name == "Agent":
                permission_record.update({
                    "subagent_type": str(tool_input.get("subagent_type", "")),
                    "background_requested": bool(tool_input.get("run_in_background")),
                })
            self._write(transcript, permission_record)
            if allowed:
                return {"hookSpecificOutput": {
                    "hookEventName": "PreToolUse",
                    "permissionDecision": "allow",
                }}
            return {
                "hookSpecificOutput": {
                    "hookEventName": "PreToolUse",
                    "permissionDecision": "deny",
                    "permissionDecisionReason": reason,
                }
            }

        hooks = {
            "PreToolUse": [HookMatcher(hooks=[pre_tool_use])],
            "SubagentStart": [HookMatcher(hooks=[subagent_start])],
            "SubagentStop": [HookMatcher(hooks=[subagent_stop])],
        }
        service_owner = (os.geteuid(), os.getegid())
        sdk_user = self._prepare_sdk_user(workspace.root, runtime_dir)
        ownership_restored = False
        summary = "Claude SDK Supervisor execution completed"
        turns = 0
        token_usage: dict[str, Any] = {}
        session_id = ""
        result_seen = False
        sdk_blockers: list[str] = []
        try:
            resume_session_id, session_store = self._resolve_session(
                request.resume_session_id, runtime_dir, transcript, "coding",
            )
            options = ClaudeAgentOptions(
                tools=TEAM_TOOLS,
                # 后台子 Agent 无法交互确认权限；固定工具面预授权，Hook 继续执行路径门禁。
                allowed_tools=TEAM_TOOLS,
                disallowed_tools=DISALLOWED_TOOLS,
                permission_mode="dontAsk",
                hooks=hooks,
                agents={spec.name: self._definition(spec) for spec in specs},
                model=self.model_profile.model or None,
                max_turns=self.engine_options.max_turns,
                # 内置 Agent 的终态可能携带较大的探索结果，使用 SDK 官方可配置缓冲区承接。
                max_buffer_size=self.engine_options.max_buffer_size,
                cwd=workspace.root,
                settings=str(settings_path),
                setting_sources=["project"],
                strict_mcp_config=True,
                skills=[],
                env=self._sdk_env(runtime_dir),
                user=sdk_user,
                resume=resume_session_id,
                session_store=session_store,
                session_store_flush="eager",
                enable_file_checkpointing=False,
                stderr=lambda line: self._write(transcript, {"type": "sdk_stderr", "message": line}),
            )
            async for message in self.query(prompt=self._prompt_stream(self._prompt(request, specs)), options=options):
                turns += int(isinstance(message, AssistantMessage))
                self._heartbeat(type(message).__name__, "developer")
                if isinstance(message, AssistantMessage):
                    for block in message.content:
                        if isinstance(block, TextBlock) and block.text.strip():
                            summary = block.text.strip()
                        elif isinstance(block, ToolUseBlock):
                            self._write(transcript, {
                                "type": "tool_use",
                                "tool_use_id": block.id,
                                "tool": block.name,
                                "target": self._tool_target(block.input),
                                "parent_tool_use_id": message.parent_tool_use_id or "",
                            })
                elif isinstance(message, UserMessage) and isinstance(message.content, list):
                    for block in message.content:
                        if isinstance(block, ToolResultBlock):
                            self._write(transcript, {
                                "type": "tool_result",
                                "tool_use_id": block.tool_use_id,
                                "is_error": bool(block.is_error),
                                "content": self._result_excerpt(block.content),
                                "parent_tool_use_id": message.parent_tool_use_id or "",
                            })
                elif isinstance(message, TaskStartedMessage):
                    active_tasks.add(message.task_id)
                    self._write(transcript, {
                        "type": "task_started",
                        "task_id": message.task_id,
                        "description": message.description,
                        "task_type": message.task_type or "",
                    })
                elif isinstance(message, TaskNotificationMessage):
                    active_tasks.discard(message.task_id)
                    self._update_task_run(message.task_id, message.status, runs)
                    self._write(transcript, {
                        "type": "task_completed",
                        "task_id": message.task_id,
                        "status": message.status,
                        "summary": message.summary,
                    })
                elif isinstance(message, TaskUpdatedMessage):
                    status = message.status or str(message.patch.get("status", ""))
                    if status in TERMINAL_TASK_STATUSES:
                        active_tasks.discard(message.task_id)
                        self._update_task_run(message.task_id, status, runs)
                    self._write(transcript, {
                        "type": "task_updated",
                        "task_id": message.task_id,
                        "status": status,
                    })
                elif isinstance(message, ResultMessage):
                    result_seen = True
                    summary = (message.result or summary).strip()
                    turns = message.num_turns
                    token_usage = dict(message.usage or {})
                    session_id = message.session_id
                    if message.permission_denials:
                        sdk_blockers.append(
                            f"SDK permission_denials: {len(message.permission_denials)} 个工具请求被拒绝"
                        )
                    if message.deferred_tool_use is not None:
                        sdk_blockers.append("SDK deferred_tool_use: 存在未执行的工具请求")
                    self._write(transcript, {
                        "type": "sdk_result_metadata",
                        "subtype": message.subtype,
                        "is_error": message.is_error,
                        "stop_reason": message.stop_reason or "",
                        "permission_denial_count": len(message.permission_denials or []),
                        "deferred_tool_use": message.deferred_tool_use is not None,
                        "result_length": len(summary),
                    })
                    # SDK 终态由 subtype 与 is_error 共同裁决，模型自然语言不能覆盖系统失败事实。
                    if message.subtype != "success" or message.is_error:
                        detail = "; ".join(message.errors or [])
                        sdk_blockers.append(
                            f"Claude SDK Supervisor execution failed: "
                            f"{message.subtype}: {detail}".rstrip(": ")
                        )
            if not result_seen:
                sdk_blockers.append("Claude SDK 未返回 ResultMessage")
            if active_tasks:
                self._write(transcript, {
                    "type": "subagent_tasks_still_running",
                    "task_ids": sorted(active_tasks),
                })
            # SDK 以低权限用户写工作区；收集 Diff 前恢复 Worker 身份，避免 Git 拒绝非当前用户仓库。
            self._restore_service_owner(workspace.root, runtime_dir, sdk_user, service_owner)
            ownership_restored = True
            repo_changes = self._repo_changes(workspace, summary)
            outcome = self._finalize_outcome(repo_changes, session_id, sdk_blockers)
            result = CodingAttemptResult(
                attempt_id=request.attempt_id,
                summary=summary,
                outcome=outcome,
                repo_changes=repo_changes,
                subagent_runs=list(runs.values()),
                token_usage=token_usage,
                session_id=session_id,
                turns=turns,
            )
            self._write(transcript, {"type": "result", **result.model_dump()})
            # Activity 注入的 callback 必须先原子持久化候选，之后才能破坏性清理工作区。
            if self.candidate_callback:
                self._guard_write()
                self.candidate_callback(result)
            self._guard_write()
            reset_team_workspace(workspace)
            log.info(
                "Claude SDK Supervisor 执行结束 work_item=%s status=%s subagents=%s changed_repos=%s",
                request.work_item_id,
                result.outcome.status,
                len(result.subagent_runs),
                len([item for item in result.repo_changes if item.diff_patch.strip()]),
            )
            return result
        finally:
            context_path.unlink(missing_ok=True)
            if not ownership_restored:
                self._restore_service_owner(workspace.root, runtime_dir, sdk_user, service_owner)
            if not workspace.persistent:
                shutil.rmtree(runtime_dir, ignore_errors=True)

    def _agent_specs(
        self, request: CodingAttemptRequest | CodingPlanRequest, workspace: TeamWorkspace,
    ) -> list[TeamAgentSpec]:
        specs: list[TeamAgentSpec] = []
        for repo in request.repos:
            name = self._unique_agent_name(f"repo-{repo.repo_id}", {item.name for item in specs})
            policy = AgentPolicy(
                agent_type=name,
                repo=repo.repo_id,
                readable_roots=(workspace.root,),
                writable_roots=(workspace.repos[repo.repo_id],),
                allowed_paths=tuple(repo.allowed_paths),
                forbidden_paths=tuple(repo.forbidden_paths),
                test_commands=tuple(repo.test_commands),
            )
            specs.append(TeamAgentSpec(name=name, repo=repo, policy=policy))
        return specs

    def _definition(self, spec: TeamAgentSpec) -> AgentDefinition:
        repo_dir = spec.policy.writable_roots[0].name
        tests = "\n".join(f"- cd {repo_dir} && {item}" for item in spec.policy.test_commands) or "- 无"
        prompt = (
            f"你负责仓库 {spec.repo.repo_id}（目录 {repo_dir}，类型 {spec.repo.kind}）。"
            "你可以读取整个团队工作区以核对跨仓接口，但只能编辑自己负责的仓库。"
            "自主定位源码并完成目标，不要提交 Git，不要编辑生成物或忽略目录。"
            "源码定位只使用 Read/Glob/Grep；验证命令由外层 Workflow 在收集 Diff 后统一执行。"
            f"\n\n可执行验证命令：\n{tests}"
        )
        return AgentDefinition(
            description=f"负责 {spec.repo.repo_id} 仓库的代码实现",
            prompt=prompt,
            tools=SUBAGENT_TOOLS,
            disallowedTools=DISALLOWED_TOOLS,
            model="inherit",
            maxTurns=self.engine_options.max_turns,
            # 固定工具面适用于后台 Agent，路径隔离仍由全局 Hook 裁决。
            permissionMode="dontAsk",
        )

    def _context(
        self, request: CodingAttemptRequest | CodingPlanRequest, specs: list[TeamAgentSpec],
    ) -> str:
        requirement_context = self._render_context_items(request.requirement_context)
        execution_context = self._render_context_items(request.execution_context)
        repositories = "\n".join(
            f"- {spec.repo.repo_id}: {spec.policy.writable_roots[0].name}，实现 Agent={spec.name}，"
            f"允许路径={self._paths(list(spec.policy.allowed_paths))}，"
            f"禁止路径={self._paths(list(spec.policy.forbidden_paths))}，"
            f"验证命令={self._paths(list(spec.policy.test_commands))}"
            for spec in specs
        )
        return (
            "# Asterism Claude SDK 团队上下文\n\n"
            "Temporal 只管理生命周期；当前 Coding Attempt 由一个 Root Supervisor 对最终结果负责。"
            "Root 可在仓库路径门禁内直接修改代码，原生子 Agent 只是可选的内部加速器。"
            "Git diff、门禁、人工确认和发布由外部系统负责。\n\n"
            f"Requirement manifest: {request.requirement_manifest_id}\n"
            f"Execution context bundle: {request.execution_bundle_id or 'none'}\n\n"
            f"## 仓库与写权限\n{repositories}\n\n"
            "## 已冻结需求依据\n"
            "以下内容是确认 PRD 时实际引用的不可变快照，规划和开发必须保持其业务语义。\n"
            f"{requirement_context}\n\n"
            "## 执行阶段补充经验\n"
            "以下内容只能指导实现；若与目标、验收标准或已冻结需求依据冲突，必须忽略。\n"
            f"{execution_context}\n"
        )

    def _render_context_items(self, items: list[dict[str, Any]]) -> str:
        lines = []
        for item in items:
            content = str(item.get("content", "")).strip()
            if not content:
                continue
            ref_id = item.get("refId", item.get("ref_id", ""))
            lines.append(f"- [{ref_id}] {item.get('title', '')}: {content}")
        return "\n".join(lines) or "- 无"

    def _prompt(self, request: CodingAttemptRequest, specs: list[TeamAgentSpec]) -> str:
        criteria = "\n".join(f"- {item}" for item in request.acceptance_criteria) or "- 无"
        mappings = "\n".join(
            f"- {spec.name} -> 仓库 {spec.repo.repo_id}，目录 {spec.policy.writable_roots[0].name}"
            for spec in specs
        )
        # 旧 Patch 已恢复到工作区，Prompt 只传摘要，避免重复占用模型上下文。
        previous = json.dumps([
            {
                "repo": item.repo,
                "summary": item.summary,
                "changed_paths": item.changed_paths,
            }
            for item in request.previous_candidate
        ], ensure_ascii=False)
        revision = json.dumps(
            request.revision_context.model_dump() if request.revision_context else {}, ensure_ascii=False,
        )
        approved_plan = request.approved_plan.plan_markdown if request.approved_plan else "无"
        return (
            "你是本次 Coding Attempt 唯一的 Root Supervisor。当前计划已经过人工批准；优先承接原会话完成工作。"
            "若原会话不可恢复，以当前持久工作区、已批准计划、候选摘要和人工反馈为权威继续，不得从业务目标外扩。"
            "你可以在系统仓库路径门禁内直接 Edit/Write；原生 Agent 仅是可选的内部加速器，不是必经 handoff。"
            "Explore、Plan 等内置 Agent 只用于探索和分析；自动生成的仓库 Agent 可按需协助实现。"
            "团队工作区下的仓库目录就是映射中给出的单层目录，不要再次拼接仓库名。"
            "探索时围绕目标目录和关键符号做定向 Glob/Grep/Read，避开 .git 与构建产物，并返回精炼结论。"
            "不要猜文件名，不要提交 Git。已有代码变更覆盖验收标准后立即收尾。"
            "最终用自然语言简要说明完成内容、验证情况和仍存在的问题，不要输出 JSON、task_id 或 changed_paths；"
            "系统会直接读取真实 Git Diff、路径门禁和测试结果判断候选状态。\n\n"
            "上一版候选只是待修订基线，不代表已批准；修订时必须逐条落实人工反馈，"
            "人工反馈与候选说明冲突时以人工反馈为准。\n\n"
            f"目标：\n{request.goal}\n\n"
            f"验收标准：\n{criteria}\n\n"
            f"人工反馈：\n{request.feedback or '无'}\n\n"
            f"已批准计划（任务路径只是证据，不是权限边界）：\n{approved_plan}\n\n"
            f"修订上下文：\n{revision}\n\n"
            f"上一版候选摘要（代码已恢复到当前工作区；重做时直接按反馈修订，可能为空）：\n{previous}\n\n"
            f"可选子 Agent 与仓库：\n{mappings}\n\n"
            f"Supervisor 补充约束：\n{self.supervisor.prompt or '无'}\n"
        )

    async def _prompt_stream(self, prompt: str):
        # 单条流式消息保持一次性 Coding Attempt 语义，并兼容 SDK 控制通道。
        yield {"type": "user", "message": {"role": "user", "content": prompt}}

    def _authorize(
        self, policy: AgentPolicy, tool_name: str, tool_input: dict[str, Any], team_root: Path,
    ) -> tuple[bool, str]:
        if tool_name in DISALLOWED_TOOLS:
            return False, f"工具 {tool_name} 不在 Coding Attempt 权限内"
        if tool_name == "Agent":
            return (policy.can_delegate, "只有 Supervisor 可以继续创建子 Agent")
        target = self._tool_path(tool_input, team_root)
        if target is None:
            return True, ""
        if tool_name in WRITE_TOOLS:
            writable = next((root for root in policy.writable_roots if self._within(target, root)), None)
            if writable is None:
                return False, "只能修改当前子 Agent 负责的仓库"
            relative = target.relative_to(writable.resolve()).as_posix()
            if self._matches(relative, list(policy.forbidden_paths)):
                return False, f"禁止修改路径: {relative}"
            if policy.allowed_paths and not self._matches(relative, list(policy.allowed_paths)):
                return False, f"超出允许路径: {relative}"
            return True, ""
        if any(self._within(target, root) for root in policy.readable_roots):
            return True, ""
        return False, "只能读取当前 Coding Attempt 工作区"

    def _repo_changes(self, workspace: TeamWorkspace, summary: str) -> list[RepoChangeResult]:
        self._guard_write()
        changes: list[RepoChangeResult] = []
        for repo_id, repo_path in workspace.repos.items():
            self._git(repo_path, "add", "-N", ".")
            diff_patch = self._git(repo_path, "diff", "--no-ext-diff", "--binary").stdout
            changes.append(RepoChangeResult(
                repo=repo_id,
                diff_patch=diff_patch,
                changed_paths=sorted(changed_paths(diff_patch)),
                summary=summary if diff_patch.strip() else "",
            ))
        return changes

    def _guard_write(self) -> None:
        if self.write_guard:
            self.write_guard()

    def _git(self, repo_path: Path, *args: str) -> subprocess.CompletedProcess[str]:
        try:
            # 仅信任本次命令的精确工作区，兼容 Worker 中断后持久卷仍保留 SDK 用户 ownership。
            return subprocess.run(
                ["git", "-c", f"safe.directory={repo_path.resolve()}", *args],
                cwd=repo_path, check=True, capture_output=True, text=True,
            )
        except subprocess.CalledProcessError as error:
            detail = (error.stderr or error.stdout or "").strip() or f"exit {error.returncode}"
            log.error("Claude SDK 工作区 Git 操作失败 repo=%s command=%s detail=%s", repo_path.name, args[0], detail)
            raise RuntimeError(f"{repo_path.name}: git {' '.join(args)} 失败: {detail}") from error

    def _finalize_outcome(
        self,
        changes: list[RepoChangeResult],
        session_id: str,
        sdk_blockers: list[str],
    ) -> ExecutionOutcome:
        """只用 SDK 终态和真实 Git Diff 收敛 Attempt，不采信模型声明的机器字段。"""

        blockers = list(sdk_blockers)
        actual_paths = sorted({path for change in changes for path in change.changed_paths})
        if not any(change.diff_patch.strip() for change in changes):
            blockers.append("Coding Attempt 未生成有效代码变更")
        blockers = list(dict.fromkeys(item for item in blockers if item))
        return ExecutionOutcome(
            status="blocked" if blockers else "completed",
            blockers=blockers,
            changed_paths=actual_paths,
            session_id=session_id,
        )

    def _update_task_run(
        self, task_id: str, status: str, runs: dict[str, SubagentRun],
    ) -> None:
        # 子 Agent 状态只供审计，原样保留 SDK 终态。
        current = runs.get(task_id)
        if current and status in TERMINAL_TASK_STATUSES:
            runs[task_id] = current.model_copy(update={"status": status})

    def _sdk_env(self, runtime_dir: Path) -> dict[str, str]:
        env = {
            "CLAUDE_CONFIG_DIR": str(runtime_dir),
            "CLAUDE_AGENT_SDK_CLIENT_APP": "asterism",
            "HOME": str(runtime_dir / "home"),
            "XDG_CONFIG_HOME": str(runtime_dir / "config"),
            "XDG_CACHE_HOME": str(runtime_dir / "cache"),
            "XDG_DATA_HOME": str(runtime_dir / "data"),
            "XDG_STATE_HOME": str(runtime_dir / "state"),
            **self.model_env,
        }
        if self.model_profile.base_url:
            env["ANTHROPIC_BASE_URL"] = self.model_profile.base_url
            env["ANTHROPIC_AUTH_TOKEN"] = self.model_profile.api_key
        else:
            env["ANTHROPIC_API_KEY"] = self.model_profile.api_key
        return env

    def _runtime_dir(self, workspace: TeamWorkspace) -> Path:
        runtime_dir = workspace.root.parent / f".{workspace.root.name}-claude-runtime"
        if not workspace.persistent:
            shutil.rmtree(runtime_dir, ignore_errors=True)
        for name in ("home", "config", "cache", "data", "state"):
            (runtime_dir / name).mkdir(parents=True, exist_ok=True)
        return runtime_dir

    def _resolve_session(
        self,
        requested_session_id: str,
        runtime_dir: Path,
        transcript: Path,
        phase: str,
    ) -> tuple[str | None, JsonlSessionStore | None]:
        """Session 是恢复加速项；缺失时由持久执行上下文重建，不阻塞业务流程。"""

        if not requested_session_id:
            return None, self.session_store
        if self._local_session_exists(runtime_dir, requested_session_id):
            # 低权限 Claude CLI 直接读取 Artifact volume 中的原生 runtime。
            mode = "local_runtime"
            resume_session_id = requested_session_id
            session_store = None
        elif self.session_store.contains(requested_session_id):
            # SDK Store 会物化到父进程私有临时目录，降权 CLI 无法读取；改由持久上下文重建。
            mode = "rebuilt_from_context"
            resume_session_id = None
            session_store = self.session_store
        else:
            mode = "rebuilt"
            resume_session_id = None
            session_store = self.session_store
        self._write(transcript, {
            "type": "session_recovery",
            "phase": phase,
            "mode": mode,
            "requested_session_id": requested_session_id,
        })
        if resume_session_id:
            log.info(
                "Claude SDK Session 从本地 runtime 恢复 phase=%s session_id=%s",
                phase, requested_session_id,
            )
        else:
            log.warning(
                "Claude SDK Session 不可直接恢复，基于持久执行上下文重建 phase=%s mode=%s session_id=%s",
                phase, mode, requested_session_id,
            )
        return resume_session_id, session_store

    def _local_session_exists(self, runtime_dir: Path, session_id: str) -> bool:
        projects = runtime_dir / "projects"
        return projects.is_dir() and any(
            (project / f"{session_id}.jsonl").is_file()
            for project in projects.iterdir()
            if project.is_dir()
        )

    def _prepare_sdk_user(self, team_root: Path, runtime_dir: Path) -> int | None:
        if os.geteuid() != 0:
            return None
        self._set_tree_owner(team_root, CLAUDE_UID, CLAUDE_GID, writable=True)
        self._set_tree_owner(runtime_dir, CLAUDE_UID, CLAUDE_GID, writable=True)
        log.info("Claude SDK Supervisor 切换低权限用户 uid=%s", CLAUDE_UID)
        return CLAUDE_UID

    def _restore_service_owner(
        self,
        team_root: Path,
        runtime_dir: Path,
        sdk_user: int | None,
        service_owner: tuple[int, int],
    ) -> None:
        if sdk_user is None:
            return
        self._set_tree_owner(team_root, *service_owner)
        self._set_tree_owner(runtime_dir, *service_owner)

    def _set_tree_owner(self, root: Path, uid: int, gid: int, writable: bool = False) -> None:
        if not root.exists():
            return
        for path in (root, *root.rglob("*")):
            os.chown(path, uid, gid, follow_symlinks=False)
            if not writable or path.is_symlink():
                continue
            mode = path.stat().st_mode
            required = stat.S_IRUSR | stat.S_IWUSR | (stat.S_IXUSR if path.is_dir() else 0)
            path.chmod(mode | required)

    def _transcript_path(self, request: CodingAttemptRequest) -> Path:
        safe_id = re.sub(r"[^a-zA-Z0-9_.-]", "_", request.work_item_id)
        safe_case = re.sub(r"[^a-zA-Z0-9_.-]", "_", request.case_id)
        path = self.artifacts_root / safe_id / f"coding-attempt-{safe_case}.jsonl"
        path.parent.mkdir(parents=True, exist_ok=True)
        return path

    def _attempt_start(self, request: CodingAttemptRequest) -> dict[str, Any]:
        revision = request.revision_context
        return {
            "type": "attempt_start",
            "started_at": datetime.now(UTC).isoformat(),
            "work_item_id": request.work_item_id,
            "case_id": request.case_id,
            "revision": revision.revision if revision else 0,
            "revision_mode": revision.revision_mode if revision else "initial",
        }

    def _write(self, path: Path, record: dict[str, Any]) -> None:
        with path.open("a", encoding="utf-8") as stream:
            stream.write(json.dumps(record, ensure_ascii=False) + "\n")

    def _heartbeat(self, event_type: str, agent: str) -> None:
        if self.event_callback:
            self.event_callback({"type": event_type, "agent": agent})

    def _tool_path(self, tool_input: dict[str, Any], team_root: Path) -> Path | None:
        raw = tool_input.get("file_path") or tool_input.get("path")
        if not raw:
            return None
        path = Path(str(raw)).expanduser()
        return path.resolve() if path.is_absolute() else (team_root / path).resolve()

    def _tool_target(self, tool_input: dict[str, Any]) -> str:
        return str(
            tool_input.get("file_path")
            or tool_input.get("path")
            or tool_input.get("pattern")
            or tool_input.get("command")
            or ""
        )

    def _within(self, path: Path, root: Path) -> bool:
        try:
            path.relative_to(root.resolve())
            return True
        except ValueError:
            return False

    def _matches(self, path: str, prefixes: list[str]) -> bool:
        normalized = path.strip("/")
        return any(
            clean and (normalized == clean or normalized.startswith(clean + "/"))
            for prefix in prefixes
            if (clean := prefix.strip("/"))
        )

    def _unique_agent_name(self, value: str, used: set[str]) -> str:
        base = re.sub(r"[^a-zA-Z0-9_-]", "-", value).strip("-") or "repo-agent"
        name = base
        suffix = 2
        while name in used:
            name = f"{base}-{suffix}"
            suffix += 1
        return name

    def _paths(self, paths: list[str]) -> str:
        return ", ".join(paths) if paths else "未限制"

    def _result_excerpt(self, content: object) -> str:
        if content is None:
            return ""
        value = content if isinstance(content, str) else json.dumps(content, ensure_ascii=False)
        return value[-2000:]
