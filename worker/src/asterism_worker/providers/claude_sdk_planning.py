import json
import logging
import os
import re
import shutil
from datetime import UTC, datetime

from claude_agent_sdk import AssistantMessage, ClaudeAgentOptions, HookMatcher, ResultMessage
from pydantic import ValidationError

from asterism_worker.contracts import (
    CodingPlanDraft,
    CodingPlanProposal,
    CodingPlanRequest,
    CodingPlanTask,
)
from asterism_worker.repo_source import TeamWorkspace

log = logging.getLogger(__name__)
PLANNING_TOOLS = ["Read", "Glob", "Grep"]
PLANNING_DISALLOWED_TOOLS = ["Agent", "Edit", "Write", "Bash", "WebSearch", "WebFetch"]
PLANNING_SYNTHESIS_RESERVE = 8


class PlanningOutputError(RuntimeError):
    """规划结构化输出永久不符合系统契约。"""


class ClaudeSdkPlanningMixin:
    """同一个 Claude SDK Supervisor 的只读规划回合。"""

    async def plan(self, request: CodingPlanRequest, workspace: TeamWorkspace) -> CodingPlanDraft:
        self._guard_write()
        specs = self._agent_specs(request, workspace)
        runtime_dir = self._runtime_dir(workspace)
        settings_path = runtime_dir / "settings.json"
        settings_path.write_text("{}\n", encoding="utf-8")
        context_path = workspace.root / "CLAUDE.md"
        context_path.write_text(self._context(request, specs), encoding="utf-8")
        transcript = self._planning_transcript_path(request)
        service_owner = (os.geteuid(), os.getegid())
        sdk_user = None
        result_message: ResultMessage | None = None
        assistant_turns = 0
        inspected: set[str] = set()
        tool_turn_limit = max(4, self.engine_options.max_turns - PLANNING_SYNTHESIS_RESERVE)

        async def read_only_gate(data: dict, _tool_use_id: str | None, _context: object) -> dict:
            nonlocal inspected
            tool_name = str(data.get("tool_name", ""))
            tool_input = dict(data.get("tool_input") or {})
            target = self._tool_path(tool_input, workspace.root)
            allowed = tool_name in PLANNING_TOOLS and (
                target is None or self._within(target, workspace.root)
            )
            reason = "规划阶段只允许读取当前团队工作区"
            fingerprint = json.dumps(
                {"tool": tool_name, "input": tool_input}, ensure_ascii=False, sort_keys=True,
            )
            if allowed and fingerprint in inspected:
                allowed = False
                reason = "该证据已经读取，请使用已有结果继续分析"
            elif allowed and assistant_turns >= tool_turn_limit:
                allowed = False
                reason = "规划证据窗口已结束，请停止探索并立即提交结构化执行计划"
            elif allowed:
                inspected.add(fingerprint)
            self._write(transcript, {
                "type": "planning_tool_permission",
                "tool": tool_name,
                "target": self._tool_target(tool_input),
                "allowed": allowed,
                **({"reason": reason} if not allowed else {}),
            })
            return {"hookSpecificOutput": {
                "hookEventName": "PreToolUse",
                "permissionDecision": "allow" if allowed else "deny",
                **({"permissionDecisionReason": reason} if not allowed else {}),
            }}

        try:
            # 规划是只读回合，先以 Worker 身份记录 Git 基线，再把工作区交给低权限 SDK 用户。
            base_revisions = {
                repo_id: self._git(path, "rev-parse", "HEAD").stdout.strip()
                for repo_id, path in workspace.repos.items()
            }
            sdk_user = self._prepare_sdk_user(workspace.root, runtime_dir)
            resume_session_id, session_store = self._resolve_session(
                request.resume_session_id, runtime_dir, transcript, "planning",
            )
            options = ClaudeAgentOptions(
                tools=PLANNING_TOOLS,
                allowed_tools=PLANNING_TOOLS,
                disallowed_tools=PLANNING_DISALLOWED_TOOLS,
                permission_mode="dontAsk",
                hooks={"PreToolUse": [HookMatcher(hooks=[read_only_gate])]},
                model=self.model_profile.model or None,
                max_turns=self.engine_options.max_turns,
                max_buffer_size=self.engine_options.max_buffer_size,
                cwd=workspace.root,
                settings=str(settings_path),
                setting_sources=["project"],
                strict_mcp_config=True,
                skills=[],
                env=self._sdk_env(runtime_dir),
                user=sdk_user,
                resume=resume_session_id,
                # SDK 原生从持久 Store 物化会话；本机旧 runtime 仅作升级兼容。
                session_store=session_store,
                session_store_flush="eager",
                enable_file_checkpointing=False,
                output_format={
                    "type": "json_schema",
                    "schema": CodingPlanProposal.model_json_schema(),
                },
                stderr=lambda line: self._write(transcript, {"type": "sdk_stderr", "message": line}),
            )
            self._write(transcript, {
                "type": "planning_start", "revision": request.plan_revision,
                "feedback": request.feedback, "started_at": datetime.now(UTC).isoformat(),
            })
            async for message in self.query(
                prompt=self._prompt_stream(self._planning_prompt(request, specs)), options=options,
            ):
                self._heartbeat(type(message).__name__, "developer-planning")
                if isinstance(message, AssistantMessage):
                    assistant_turns += 1
                elif isinstance(message, ResultMessage):
                    result_message = message
            if result_message is None:
                raise RuntimeError("Claude SDK Planning failed: result_missing")
            if result_message.subtype == "error_max_structured_output_retries":
                raise PlanningOutputError("structured_output_retry_exhausted")
            if result_message.subtype != "success" or result_message.is_error:
                raise RuntimeError(f"Claude SDK Planning failed: {result_message.subtype}")
            if not result_message.structured_output:
                raise PlanningOutputError("structured_output_missing")
            try:
                proposal = CodingPlanProposal.model_validate(result_message.structured_output)
            except ValidationError as error:
                raise PlanningOutputError("structured_output_invalid") from error
            unknown = {task.repo for task in proposal.tasks} - set(workspace.repos)
            if unknown:
                raise PlanningOutputError("unknown_repo")
            # 系统字段只在结构化候选通过校验后生成，模型无法覆盖身份和 Git 基线。
            tasks = [
                CodingPlanTask(task_id=f"task-{index:02d}", **task.model_dump())
                for index, task in enumerate(proposal.tasks, start=1)
            ]
            plan = CodingPlanDraft(
                summary=proposal.summary,
                tasks=tasks,
                risks=proposal.risks,
                open_questions=proposal.open_questions,
                revision=request.plan_revision,
                session_id=result_message.session_id,
                base_revisions=base_revisions,
            )
            self._write(transcript, {
                "type": "planning_result",
                **plan.model_dump(),
                "usage": result_message.usage or {},
            })
            log.info(
                "Claude SDK 规划完成 work_item=%s revision=%s tasks=%s",
                request.work_item_id, request.plan_revision, len(plan.tasks),
            )
            return plan
        finally:
            context_path.unlink(missing_ok=True)
            self._restore_service_owner(workspace.root, runtime_dir, sdk_user, service_owner)
            if not workspace.persistent:
                shutil.rmtree(runtime_dir, ignore_errors=True)

    def _planning_prompt(self, request: CodingPlanRequest, specs: list) -> str:
        criteria = "\n".join(
            f"AC-{index + 1}: {value}" for index, value in enumerate(request.acceptance_criteria)
        ) or "无"
        repositories = "\n".join(
            f"- {spec.repo.repo_id}（目录 {spec.policy.writable_roots[0].name}，类型 {spec.repo.kind}）"
            for spec in specs
        )
        previous = json.dumps(
            request.previous_plan.model_dump(exclude={"session_id"}) if request.previous_plan else {},
            ensure_ascii=False,
        )
        return (
            "你是同一个 Coding Supervisor 的规划回合。只使用 Read、Glob、Grep 检查真实仓库，禁止修改文件、"
            "禁止创建子 Agent。围绕目标和关键符号定向搜索，证据充分后立即结束，不做无关探索。"
            "若系统提示证据窗口结束或证据已读取，不要继续请求工具，直接使用已有证据提交执行计划。"
            "每项任务必须关联一个仓库 ID、目标、验收标准引用和源码证据；同时说明风险和未决问题。"
            "计划里的路径只是证据和建议，写权限仍由系统仓库策略决定。\n\n"
            f"目标：\n{request.goal}\n\n验收标准：\n{criteria}\n\n仓库：\n{repositories}\n\n"
            f"人工打回意见：\n{request.feedback or '无'}\n\n上一版计划：\n{previous}\n\n"
            f"Supervisor 补充约束：\n{self.supervisor.prompt or '无'}\n"
        )

    def _planning_transcript_path(self, request: CodingPlanRequest):
        safe_id = re.sub(r"[^a-zA-Z0-9_.-]", "_", request.work_item_id)
        safe_case = re.sub(r"[^a-zA-Z0-9_.-]", "_", request.case_id)
        path = self.artifacts_root / safe_id / f"coding-plan-{safe_case}.jsonl"
        path.parent.mkdir(parents=True, exist_ok=True)
        return path
