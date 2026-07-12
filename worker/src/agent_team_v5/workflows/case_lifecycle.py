from datetime import timedelta

from temporalio.common import RetryPolicy
from temporalio import workflow
from temporalio.exceptions import ActivityError, ApplicationError

from agent_team_v5.contracts import AgentAssignment, CaseInput, ContextSnapshot, ExecutionPlan, ExecutionResult, PatchApplyResult, ProjectionEvent, ValidationResult
from agent_team_v5.workflows.state_machine import CaseState, TERMINAL_STATUSES


@workflow.defn(name="AgentTeamV5CaseWorkflow")
class AgentTeamV5CaseWorkflow:
    def __init__(self) -> None:
        self.state = CaseState()
        self.case_input: CaseInput | None = None
        self.pending_actions: list[tuple[str, str]] = []

    @workflow.run
    async def run(self, case_input: CaseInput) -> str:
        self.case_input = case_input
        while self.state.status not in TERMINAL_STATUSES:
            await workflow.wait_condition(lambda: bool(self.pending_actions) or self.state.status in TERMINAL_STATUSES)
            while self.pending_actions:
                action, signal_id = self.pending_actions.pop(0)
                await self._handle_action(action, signal_id)
        return self.state.status.value

    @workflow.signal
    async def owner_approved(self, signal_id: str) -> None:
        self.pending_actions.append(("owner_approved", signal_id))

    @workflow.signal
    async def start_modification(self, signal_id: str) -> None:
        self.pending_actions.append(("start_modification", signal_id))

    @workflow.signal
    async def patch_apply_approved(self, signal_id: str) -> None:
        self.pending_actions.append(("patch_apply_approved", signal_id))

    @workflow.signal
    async def patch_apply_rejected(self, signal_id: str) -> None:
        self.pending_actions.append(("patch_apply_rejected", signal_id))

    @workflow.signal
    async def validation_passed(self, signal_id: str) -> None:
        self.pending_actions.append(("validation_passed", signal_id))

    @workflow.signal
    async def validation_rejected(self, signal_id: str) -> None:
        self.pending_actions.append(("validation_rejected", signal_id))

    @workflow.signal
    async def rework(self, signal_id: str) -> None:
        self.pending_actions.append(("rework", signal_id))

    @workflow.signal
    async def release_approved(self, signal_id: str) -> None:
        self.pending_actions.append(("release_approved", signal_id))

    @workflow.signal
    async def cancel_case(self, signal_id: str) -> None:
        self.pending_actions.append(("cancel_case", signal_id))

    @workflow.signal
    async def owner_rejected(self, signal_id: str) -> None:
        self.pending_actions.append(("owner_rejected", signal_id))

    @workflow.query
    def current_status(self) -> str:
        return self.state.status.value

    async def _handle_action(self, action: str, signal_id: str) -> None:
        if action == "owner_approved":
            await self._emit(self.state.owner_approved(), signal_id, {})
            return
        if action == "start_modification":
            await self._start_modification(signal_id)
            return
        if action == "patch_apply_approved":
            await self._apply_patch(signal_id)
            return
        if action == "release_approved":
            await self._release(signal_id)
            return
        if action in {"patch_apply_rejected", "cancel_case", "owner_rejected"}:
            await self._revert_if_needed(signal_id)
        events = {
            "patch_apply_rejected": self.state.patch_apply_rejected,
            "validation_passed": self.state.validation_passed,
            "validation_rejected": self.state.validation_rejected,
            "rework": self.state.rework,
            "cancel_case": self.state.cancel_case,
            "owner_rejected": self.state.owner_rejected,
        }
        await self._emit(events[action](), signal_id, {})

    async def _start_modification(self, signal_id: str) -> None:
        case_input = self._case_input()
        if self.state.status.value != "activated":
            workflow.logger.warning("非法 start_modification，已忽略", extra={"status": self.state.status.value})
            return
        try:
            result_payload = await workflow.execute_activity(
                "fetch_context",
                {
                    "system_id": case_input.system_id,
                    "work_item_id": case_input.work_item_id,
                },
                start_to_close_timeout=timedelta(seconds=20),
                retry_policy=RetryPolicy(maximum_attempts=3),
            )
        except (ActivityError, ApplicationError) as error:
            await self._block_worker(signal_id, "context_fetch_failed", error)
            return
        snapshot = ContextSnapshot.model_validate(result_payload)
        try:
            repo_summary = await workflow.execute_activity(
                "summarize_repo",
                {"repo_path": case_input.repo_path},
                start_to_close_timeout=timedelta(seconds=20),
                retry_policy=RetryPolicy(maximum_attempts=1),
            )
            plan_payload = await workflow.execute_activity(
                "plan_execution",
                {
                    "system_id": case_input.system_id,
                    "prd": case_input.prd.model_dump(),
                    "repo_summary": repo_summary,
                    "memories": snapshot.approved_memories,
                    "allowed_paths": case_input.allowed_paths,
                    "context_manifest_id": snapshot.manifest_id,
                },
                start_to_close_timeout=timedelta(minutes=2),
                retry_policy=RetryPolicy(maximum_attempts=1),
            )
            plan = ExecutionPlan.model_validate(plan_payload)
            if not plan.steps:
                raise RuntimeError("empty plan")
            await workflow.execute_activity(
                "validate_plan_targets_activity",
                {
                    "repo_path": case_input.repo_path,
                    "target_files": plan.target_files,
                },
                start_to_close_timeout=timedelta(seconds=20),
                retry_policy=RetryPolicy(maximum_attempts=1),
            )
        except Exception as error:
            await self._block_worker(signal_id, "planner_failed", error)
            return
        await self._emit("ExecutionPlanDrafted", signal_id, {
            "plan": plan.model_dump(),
            "contextManifestId": snapshot.manifest_id,
        })
        result = await self._run_execution_plan(signal_id, plan, snapshot)
        if result is None:
            return
        await self._emit(self.state.modification_finished(result), signal_id, {
            "summary": result.summary,
            "diffPatch": result.diff_patch,
            "goal": case_input.prd.goal,
            "contextManifestId": snapshot.manifest_id,
            "executionProvider": result.execution_provider,
            "turns": result.turns,
            "tokenUsage": result.token_usage,
        })

    async def _run_execution_plan(self, signal_id: str, plan: ExecutionPlan,
                                  snapshot: ContextSnapshot) -> ExecutionResult | None:
        assignments = plan.assignments or [AgentAssignment(role="")]
        results: list[ExecutionResult] = []
        used_paths: set[str] = set()
        handoff: list[str] = []
        for index, assignment in enumerate(assignments):
            try:
                payload = await workflow.execute_activity(
                    "run_execution",
                    self._execution_request(plan, snapshot, assignment, index, "\n".join(handoff)),
                    # role 内部 timeout 在 activity 解析，外层留足最大执行窗口。
                    start_to_close_timeout=timedelta(seconds=self._case_input().execution_timeout_seconds or 3600),
                    heartbeat_timeout=timedelta(minutes=2),
                    retry_policy=RetryPolicy(maximum_attempts=2),
                )
            except (ActivityError, ApplicationError) as error:
                await self._block_worker(signal_id, "execution_failed", error)
                return None
            result = ExecutionResult.model_validate(payload)
            if result.blocked_reason:
                await self._block_worker(signal_id, result.blocked_reason,
                                         RuntimeError(result.blocked_detail or result.blocked_reason))
                return None
            if not result.passes_diff_gate:
                await self._block_worker(signal_id, "execution_failed", RuntimeError("Agent stage did not return valid diff"))
                return None
            paths = set(result.changed_paths or self._diff_paths(result.diff_patch))
            conflict = sorted(used_paths & paths)
            if conflict:
                await self._block_worker(signal_id, "handoff_conflict", RuntimeError(", ".join(conflict)))
                return None
            used_paths.update(paths)
            results.append(result)
            handoff.append(f"{assignment.role or 'default'}: {result.summary}; files={','.join(sorted(paths))}")
            if plan.assignments:
                await self._emit("AgentStageCompleted", signal_id, {
                    "role": assignment.role,
                    "engine": result.engine or result.execution_provider,
                    "summary": result.summary,
                    "changedPaths": sorted(paths),
                    "tokenUsage": result.token_usage,
                }, suffix=f"stage:{index}:{assignment.role}")
        if len(results) == 1:
            return results[0]
        return ExecutionResult(
            summary="；".join(result.summary for result in results),
            diff_patch="\n".join(result.diff_patch.rstrip() for result in results) + "\n",
            execution_provider="handoff",
            engine="handoff",
            changed_paths=sorted(used_paths),
            token_usage=self._merge_usage(results),
        )

    def _execution_request(self, plan: ExecutionPlan, snapshot: ContextSnapshot,
                           assignment: AgentAssignment, index: int, handoff_summary: str) -> dict:
        case_input = self._case_input()
        return {
            "case_id": case_input.case_id,
            "work_item_id": case_input.work_item_id,
            "system_id": case_input.system_id,
            "repo_path": case_input.repo_path,
            "goal": case_input.prd.goal,
            "acceptance_criteria": case_input.prd.acceptance_criteria,
            "plan": plan.model_dump(),
            "memories": snapshot.approved_memories,
            "context_manifest_id": snapshot.manifest_id,
            "allowed_paths": case_input.allowed_paths,
            "forbidden_paths": case_input.forbidden_paths,
            "test_commands": case_input.test_commands,
            "execution_provider": case_input.execution_provider,
            "claude_max_turns": case_input.claude_max_turns,
            "role_id": assignment.role,
            "role_scope": assignment.scope_paths,
            "handoff_summary": handoff_summary,
            "assignment_index": index,
            "step_refs": assignment.step_refs,
        }

    async def _apply_patch(self, signal_id: str) -> None:
        case_input = self._case_input()
        if self.state.status.value != "modification_completed":
            workflow.logger.warning("非法 patch_apply_approved，已忽略", extra={"status": self.state.status.value})
            return
        try:
            result_payload = await workflow.execute_activity(
                "apply_patch_to_repo",
                {
                    "repo_path": case_input.repo_path,
                    "diff_patch": self.state.diff_patch,
                    "allowed_paths": case_input.allowed_paths,
                    "forbidden_paths": case_input.forbidden_paths,
                },
                start_to_close_timeout=timedelta(minutes=2),
                retry_policy=RetryPolicy(maximum_attempts=3),
            )
        except (ActivityError, ApplicationError) as error:
            await self._block_worker(signal_id, "patch_apply_failed", error)
            return
        result = PatchApplyResult.model_validate(result_payload)
        if result.blocked:
            await self._emit(self.state.patch_apply_blocked(), signal_id, {"reason": result.reason})
            return
        await self._emit(self.state.patch_apply_approved(), signal_id, {})
        if case_input.test_commands:
            await self._run_validation(signal_id)

    async def _release(self, signal_id: str) -> None:
        case_input = self._case_input()
        if self.state.status.value != "validation_passed":
            workflow.logger.warning("非法 release_approved，已忽略", extra={"status": self.state.status.value})
            return
        try:
            result = await workflow.execute_activity(
                "run_release",
                {
                    "repo_path": case_input.repo_path,
                    "work_item_id": case_input.work_item_id,
                    "title": case_input.prd.title,
                    "diff_patch": self.state.diff_patch,
                },
                start_to_close_timeout=timedelta(minutes=2),
                retry_policy=RetryPolicy(maximum_attempts=1),
            )
        except (ActivityError, ApplicationError) as error:
            await self._block_worker(signal_id, "release_failed", error)
            return
        await self._emit(self.state.release_approved(), signal_id, {
            "branch": result.get("branch", ""),
            "commitHash": result.get("commit_hash", ""),
            "pushFailed": result.get("push_failed", ""),
        })

    async def _revert_if_needed(self, signal_id: str) -> None:
        if not self.state.diff_patch:
            return
        try:
            await workflow.execute_activity(
                "revert_patch",
                {
                    "repo_path": self._case_input().repo_path,
                    "diff_patch": self.state.diff_patch,
                },
                start_to_close_timeout=timedelta(minutes=1),
                retry_policy=RetryPolicy(maximum_attempts=1),
            )
        except (ActivityError, ApplicationError):
            workflow.logger.warning("回滚 patch activity 失败，继续处理信号", extra={"signal_id": signal_id})

    async def _run_validation(self, signal_id: str) -> None:
        case_input = self._case_input()
        result_payload = await workflow.execute_activity(
            "run_validation",
            {
                "repo_path": case_input.repo_path,
                "test_commands": case_input.test_commands,
            },
            start_to_close_timeout=timedelta(minutes=10),
            retry_policy=RetryPolicy(maximum_attempts=1),
        )
        result = ValidationResult.model_validate(result_payload)
        payload = {
            "commands": [command.model_dump() for command in result.commands],
            "failedCommand": result.failed_command,
            "stderrTail": result.stderr_tail,
        }
        if result.passed:
            await self._emit(self.state.validation_passed(), signal_id, payload)
            return
        await self._emit(self.state.validation_rejected(), signal_id, payload)

    async def _emit(self, event_type: str | None, signal_id: str, payload: dict, suffix: str = "") -> None:
        if event_type is None:
            return
        case_input = self._case_input()
        causation_id = f"{signal_id}:{suffix}" if suffix else signal_id
        event = ProjectionEvent(
            eventType=event_type,
            systemId=case_input.system_id,
            caseId=case_input.case_id,
            prdId=case_input.prd_id,
            workItemId=case_input.work_item_id,
            payload=payload,
            correlationId=case_input.case_id,
            causationId=causation_id,
            idempotencyKey=f"{case_input.case_id}:{event_type}:{causation_id}",
        )
        await workflow.execute_activity(
            "send_projection_event",
            event.model_dump(),
            start_to_close_timeout=timedelta(seconds=20),
            # 控制面短暂不可用时投影必须最终送达，不能因为次数上限丢事件。
            retry_policy=RetryPolicy(
                initial_interval=timedelta(seconds=1),
                backoff_coefficient=2,
                maximum_interval=timedelta(seconds=60),
            ),
        )

    async def _block_worker(self, signal_id: str, reason: str, error: BaseException) -> None:
        await self._emit(self.state.worker_blocked_on(reason), signal_id, {"reason": reason, "detail": self._error_detail(error)})

    def _error_detail(self, error: BaseException) -> str:
        messages: list[str] = []
        current: BaseException | None = error
        while current is not None:
            text = str(current)
            if text:
                messages.append(text)
            current = current.__cause__
        return " | ".join(messages)

    def _case_input(self) -> CaseInput:
        if self.case_input is None:
            raise RuntimeError("case input is not initialized")
        return self.case_input

    def _diff_paths(self, diff_patch: str) -> list[str]:
        paths: list[str] = []
        for line in diff_patch.splitlines():
            if line.startswith("diff --git "):
                parts = line.split()
                if len(parts) >= 4:
                    path = parts[3][2:] if parts[3].startswith("b/") else parts[3]
                    paths.append(path)
        return paths

    def _merge_usage(self, results: list[ExecutionResult]) -> dict:
        merged: dict[str, float] = {}
        for result in results:
            for key, value in result.token_usage.items():
                if isinstance(value, (int, float)):
                    merged[key] = merged.get(key, 0) + value
        return merged
