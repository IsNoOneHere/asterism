import hashlib
import json
import logging
import os
import re
import shutil
import stat
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from claude_agent_sdk import (
    AgentDefinition,
    AssistantMessage,
    ClaudeAgentOptions,
    HookMatcher,
    PermissionResultAllow,
    PermissionResultDeny,
    ResultMessage,
    TERMINAL_TASK_STATUSES,
    TaskNotificationMessage,
    TaskStartedMessage,
    TaskUpdatedMessage,
    TextBlock,
    ToolResultBlock,
    ToolPermissionContext,
    ToolUseBlock,
    UserMessage,
    query,
)

from asterism_worker.activities.execution_support import changed_paths
from asterism_worker.agent_config import AgentConstraints, EngineConfig, ModelProfile
from asterism_worker.contracts import (
    CodingAttemptRequest,
    CodingAttemptResult,
    RepoChangeResult,
    RepoSnapshot,
    SubagentRun,
)
from asterism_worker.repo_source import TeamWorkspace

log = logging.getLogger(__name__)
SUPERVISOR_TOOLS = ["Read", "Glob", "Grep", "Agent", "TaskOutput"]
SUBAGENT_TOOLS = ["Read", "Glob", "Grep", "Edit", "Write", "Bash"]
# SDK 顶层工具是所有子 Agent 的能力上限；实际使用权限仍由 AgentPolicy 逐次裁决。
TEAM_TOOLS = list(dict.fromkeys([*SUPERVISOR_TOOLS, *SUBAGENT_TOOLS]))
DISALLOWED_TOOLS = ["WebSearch", "WebFetch"]
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


class ClaudeSdkTeamProvider:
    """一个 Claude SDK Supervisor 会话内调度按仓库隔离的原生子 Agent。"""

    def __init__(
        self,
        model_profile: ModelProfile,
        engine_options: EngineConfig,
        artifacts_root: str,
        supervisor: AgentConstraints,
        callbacks: dict[str, Any] | None = None,
    ) -> None:
        if not model_profile.api_key:
            raise RuntimeError("claude_sdk Supervisor 缺少模型 Profile API key")
        callbacks = callbacks or {}
        self.model_profile = model_profile
        self.engine_options = engine_options
        self.artifacts_root = Path(artifacts_root)
        self.supervisor = supervisor
        self.event_callback = callbacks.get("event")
        self.query = callbacks.get("query") or query
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
        specs = self._agent_specs(request, workspace)
        policies = {spec.name: spec.policy for spec in specs}
        supervisor_policy = AgentPolicy("developer", "", (workspace.root,), (), can_delegate=True)
        readonly_policy = AgentPolicy("readonly", "", (workspace.root,), ())
        agent_ids: dict[str, str] = {}
        runs: dict[str, SubagentRun] = {}
        active_tasks: set[str] = set()
        dispatched_tasks: set[str] = set()
        runtime_dir = workspace.root.parent / f".{workspace.root.name}-claude-runtime"
        for name in ("home", "config", "cache", "data", "state"):
            (runtime_dir / name).mkdir(parents=True, exist_ok=True)
        settings_path = runtime_dir / "settings.json"
        settings_path.write_text("{}\n", encoding="utf-8")
        context_path = workspace.root / "CLAUDE.md"
        context_path.write_text(self._context(request, specs), encoding="utf-8")
        transcript = self._transcript_path(request)
        transcript.write_text("", encoding="utf-8")

        def authorize_runtime_tool(
            agent_id: str, agent_type: str, tool_name: str, tool_input: dict[str, Any],
        ) -> tuple[bool, str]:
            is_supervisor = not agent_id and (
                not agent_type or agent_type in {"developer", self.supervisor.role_id}
            )
            policy = supervisor_policy if is_supervisor else policies.get(agent_type, readonly_policy)
            allowed, reason = self._authorize(policy, tool_name, tool_input, workspace.root)
            if not allowed or tool_name != "Agent":
                return allowed, reason
            fingerprint = self._dispatch_fingerprint(tool_input)
            if fingerprint and fingerprint in dispatched_tasks:
                return False, "相同子任务已经派发，请等待或使用已有结果"
            if fingerprint:
                dispatched_tasks.add(fingerprint)
            return True, ""

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
                runs[agent_id] = current.model_copy(update={"status": "completed"})
                self._write(transcript, {"type": "subagent_stop", **runs[agent_id].model_dump()})
                self._heartbeat("SubagentStop", current.agent_type)
            return {}

        async def pre_tool_use(data: dict, _tool_use_id: str | None, _context: object) -> dict:
            agent_id = str(data.get("agent_id", ""))
            agent_type = str(data.get("agent_type", "")) or agent_ids.get(agent_id, "")
            tool_name = str(data.get("tool_name", ""))
            tool_input = dict(data.get("tool_input") or {})
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

        async def resolve_permission(
            tool_name: str, tool_input: dict[str, Any], context: ToolPermissionContext,
        ) -> PermissionResultAllow | PermissionResultDeny:
            # 内置后台 Agent 可能仍发起 SDK 权限请求；继续复用同一策略，避免退回交互拒绝。
            agent_id = context.agent_id or ""
            agent_type = agent_ids.get(agent_id, "")
            allowed, reason = authorize_runtime_tool(agent_id, agent_type, tool_name, tool_input)
            self._write(transcript, {
                "type": "tool_permission",
                "source": "sdk_permission_callback",
                "agent": agent_type or "developer",
                "tool": tool_name,
                "target": self._tool_target(tool_input),
                "allowed": allowed,
                **({"reason": reason} if reason else {}),
            })
            if allowed:
                return PermissionResultAllow()
            return PermissionResultDeny(message=reason)

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
        result_error = ""
        try:
            options = ClaudeAgentOptions(
                tools=TEAM_TOOLS,
                # PreToolUse 是确定性门禁，can_use_tool 承接内置后台 Agent 的权限请求。
                allowed_tools=[],
                disallowed_tools=DISALLOWED_TOOLS,
                permission_mode="default",
                can_use_tool=resolve_permission,
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
                enable_file_checkpointing=True,
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
                    self._complete_task_run(message.task_id, message.status, runs)
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
                        self._complete_task_run(message.task_id, status, runs)
                    self._write(transcript, {
                        "type": "task_updated",
                        "task_id": message.task_id,
                        "status": status,
                    })
                elif isinstance(message, ResultMessage):
                    summary = message.result or summary
                    turns = message.num_turns
                    token_usage = dict(message.usage or {})
                    session_id = message.session_id
                    if message.is_error:
                        detail = "; ".join(message.errors or [])
                        result_error = f"{message.subtype}: {detail}".rstrip(": ")
            if result_error:
                raise RuntimeError(f"Claude SDK Supervisor execution failed: {result_error}")
            unfinished = sorted({item.agent_type for item in runs.values() if item.status != "completed"})
            if unfinished:
                raise RuntimeError(f"Claude SDK Supervisor 提前结束，子 Agent 尚未完成: {', '.join(unfinished)}")
            if active_tasks:
                raise RuntimeError(f"Claude SDK Supervisor 提前结束，后台任务尚未完成: {', '.join(sorted(active_tasks))}")
            # SDK 以低权限用户写工作区；收集 Diff 前恢复 Worker 身份，避免 Git 拒绝非当前用户仓库。
            self._restore_service_owner(workspace.root, runtime_dir, sdk_user, service_owner)
            ownership_restored = True
            repo_changes = self._repo_changes(workspace, summary)
            self._complete_missing_runs(specs, repo_changes, runs)
            result = CodingAttemptResult(
                summary=summary,
                repo_changes=repo_changes,
                subagent_runs=list(runs.values()),
                token_usage=token_usage,
                session_id=session_id,
                turns=turns,
            )
            self._write(transcript, {"type": "result", **result.model_dump()})
            log.info(
                "Claude SDK Supervisor 执行完成 work_item=%s subagents=%s changed_repos=%s",
                request.work_item_id,
                len(result.subagent_runs),
                len([item for item in result.repo_changes if item.diff_patch.strip()]),
            )
            return result
        finally:
            context_path.unlink(missing_ok=True)
            if not ownership_restored:
                self._restore_service_owner(workspace.root, runtime_dir, sdk_user, service_owner)
            shutil.rmtree(runtime_dir, ignore_errors=True)

    def _agent_specs(self, request: CodingAttemptRequest, workspace: TeamWorkspace) -> list[TeamAgentSpec]:
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
            "Bash 只允许执行下方系统预先配置的验证命令，其他定位请使用 Read/Glob/Grep。"
            f"\n\n可执行验证命令：\n{tests}"
        )
        tools = [item for item in SUBAGENT_TOOLS if item != "Bash" or spec.policy.test_commands]
        return AgentDefinition(
            description=f"负责 {spec.repo.repo_id} 仓库的代码实现",
            prompt=prompt,
            tools=tools,
            disallowedTools=DISALLOWED_TOOLS,
            model="inherit",
            maxTurns=self.engine_options.max_turns,
            # 子 Agent 与 Supervisor 共用同一套 Hook 与 SDK 权限回调。
            permissionMode="default",
        )

    def _context(self, request: CodingAttemptRequest, specs: list[TeamAgentSpec]) -> str:
        memories = "\n".join(
            f"- {item.get('content', '')}" for item in request.memories if item.get("content")
        ) or "- 无"
        repositories = "\n".join(
            f"- {spec.repo.repo_id}: {spec.policy.writable_roots[0].name}，实现 Agent={spec.name}，"
            f"允许路径={self._paths(list(spec.policy.allowed_paths))}，"
            f"禁止路径={self._paths(list(spec.policy.forbidden_paths))}，"
            f"验证命令={self._paths(list(spec.policy.test_commands))}"
            for spec in specs
        )
        return (
            "# Asterism Claude SDK 团队上下文\n\n"
            "Temporal 只管理生命周期；当前会话由一个只读 Supervisor 调度原生子 Agent。"
            "Git diff、门禁、人工确认和发布由外部系统负责。\n\n"
            f"Context manifest: {request.context_manifest_id or 'none'}\n\n"
            f"## 仓库与写权限\n{repositories}\n\n"
            f"## 已批准记忆\n{memories}\n"
        )

    def _prompt(self, request: CodingAttemptRequest, specs: list[TeamAgentSpec]) -> str:
        criteria = "\n".join(f"- {item}" for item in request.acceptance_criteria) or "- 无"
        mappings = "\n".join(
            f"- {spec.name} -> 仓库 {spec.repo.repo_id}，目录 {spec.policy.writable_roots[0].name}"
            for spec in specs
        )
        previous = json.dumps([item.model_dump() for item in request.previous_candidate], ensure_ascii=False)
        return (
            "你是 Coding Supervisor。先理解跨仓需求，再自主使用 Claude Code 原生 Agent 完成工作。"
            "Explore、Plan 等内置 Agent 可自由用于只读探索和方案分析；需要修改代码时，调用下方自动生成的仓库 Agent。"
            "你自己没有 Edit/Write 权限，其他未绑定仓库的子 Agent 也只有只读权限。"
            "团队工作区下的仓库目录就是映射中给出的单层目录，不要再次拼接仓库名。"
            "探索时围绕目标目录和关键符号做定向 Glob/Grep/Read，避开 .git 与构建产物，并返回精炼结论。"
            "不要猜文件名，不要提交 Git，不要重复派发完全相同的子任务。"
            "可以按需并行运行后台 Agent，但在总结前必须使用 TaskOutput 等待全部任务结束。"
            "已有代码变更覆盖验收标准后立即收尾，不要反复启动 Agent 重做同一修改。\n\n"
            "上一版候选只是待修订基线，不代表已批准；修订时必须逐条落实人工反馈，"
            "人工反馈与候选说明冲突时以人工反馈为准。\n\n"
            f"目标：\n{request.goal}\n\n"
            f"验收标准：\n{criteria}\n\n"
            f"人工反馈：\n{request.feedback or '无'}\n\n"
            f"上一版候选（已恢复到当前工作区；重做时直接按反馈修订，可能为空）：\n{previous or '[]'}\n\n"
            f"可用子 Agent 与仓库：\n{mappings}\n\n"
            f"Supervisor 补充约束：\n{self.supervisor.prompt or '无'}\n"
        )

    async def _prompt_stream(self, prompt: str):
        # can_use_tool 需要 streaming 输入；单条消息仍保持一次性 Coding Attempt 语义。
        yield {"type": "user", "message": {"role": "user", "content": prompt}}

    def _authorize(
        self, policy: AgentPolicy, tool_name: str, tool_input: dict[str, Any], team_root: Path,
    ) -> tuple[bool, str]:
        if tool_name in DISALLOWED_TOOLS:
            return False, f"工具 {tool_name} 不在 Coding Attempt 权限内"
        if tool_name == "Agent":
            return (policy.can_delegate, "只有 Supervisor 可以继续创建子 Agent")
        if tool_name == "Bash":
            return self._authorize_bash(policy, tool_input)
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

    def _authorize_bash(self, policy: AgentPolicy, tool_input: dict[str, Any]) -> tuple[bool, str]:
        command = self._normalize_command(str(tool_input.get("command", "")))
        if not policy.repo or not command:
            return False, "Bash 只允许仓库 Agent 执行系统配置的验证命令"
        repo_dir = policy.writable_roots[0].name
        configured = {
            candidate
            for item in policy.test_commands
            for candidate in (
                self._normalize_command(item),
                self._normalize_command(f"cd {repo_dir} && {item}"),
            )
            if candidate
        }
        if command in configured:
            return True, ""
        return False, "Bash 命令不在该仓库的验证命令配置中"

    def _repo_changes(self, workspace: TeamWorkspace, summary: str) -> list[RepoChangeResult]:
        changes: list[RepoChangeResult] = []
        for repo_id, repo_path in workspace.repos.items():
            self._git(repo_path, "add", "-N", ".")
            diff_patch = self._git(repo_path, "diff", "--no-ext-diff", "--binary").stdout
            self._git(repo_path, "reset", "--hard", "HEAD")
            self._git(repo_path, "clean", "-fd")
            changes.append(RepoChangeResult(
                repo=repo_id,
                diff_patch=diff_patch,
                changed_paths=sorted(changed_paths(diff_patch)),
                summary=summary if diff_patch.strip() else "",
            ))
        return changes

    def _git(self, repo_path: Path, *args: str) -> subprocess.CompletedProcess[str]:
        try:
            return subprocess.run(
                ["git", *args], cwd=repo_path, check=True, capture_output=True, text=True,
            )
        except subprocess.CalledProcessError as error:
            detail = (error.stderr or error.stdout or "").strip() or f"exit {error.returncode}"
            log.error("Claude SDK 工作区 Git 操作失败 repo=%s command=%s detail=%s", repo_path.name, args[0], detail)
            raise RuntimeError(f"{repo_path.name}: git {' '.join(args)} 失败: {detail}") from error

    def _complete_missing_runs(
        self, specs: list[TeamAgentSpec], changes: list[RepoChangeResult], runs: dict[str, SubagentRun],
    ) -> None:
        changed_repos = {item.repo for item in changes if item.diff_patch.strip()}
        recorded_repos = {item.repo for item in runs.values()}
        for spec in specs:
            if spec.repo.repo_id in changed_repos and spec.repo.repo_id not in recorded_repos:
                agent_id = f"synthetic:{spec.name}"
                runs[agent_id] = SubagentRun(
                    agent_id=agent_id,
                    agent_type=spec.name,
                    repo=spec.repo.repo_id,
                    status="completed",
                )

    def _complete_task_run(
        self, task_id: str, status: str, runs: dict[str, SubagentRun],
    ) -> None:
        # SDK 内置 Agent 可能只发送 Task 终态而不发送 SubagentStop。
        current = runs.get(task_id)
        if current and status in TERMINAL_TASK_STATUSES:
            runs[task_id] = current.model_copy(update={"status": "completed"})

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

    def _dispatch_fingerprint(self, tool_input: dict[str, Any]) -> str:
        identity = {
            field: self._normalize_command(str(tool_input.get(field, ""))).lower()
            for field in ("subagent_type", "description", "prompt")
        }
        if not any(identity.values()):
            return ""
        # 内容寻址避免分隔符碰撞；前后台执行方式不改变任务本身身份。
        payload = json.dumps(identity, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        return hashlib.sha256(payload.encode()).hexdigest()

    def _normalize_command(self, value: str) -> str:
        return " ".join(value.split())

    def _result_excerpt(self, content: object) -> str:
        if content is None:
            return ""
        value = content if isinstance(content, str) else json.dumps(content, ensure_ascii=False)
        return value[-2000:]
