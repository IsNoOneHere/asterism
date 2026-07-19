import asyncio
from dataclasses import dataclass
from datetime import timedelta

from temporalio import workflow
from temporalio.common import RetryPolicy
from temporalio.exceptions import ApplicationError

from asterism_worker.contracts import (
    CaseInput,
    ContextSnapshot,
    ExecutionResult,
    LifecycleStatus,
    MergeRequestRef,
    ProjectionEvent,
)
from asterism_worker.workflows.coding import (
    CLAUDE_SDK_TEAM_ARCHITECTURE,
    CodingWorkflow,
    ExecutionPhase,
)
from asterism_worker.workflows.publishing import PublishingWorkflow
from asterism_worker.workflows.state_machine import CaseState, TERMINAL_STATUSES
from asterism_worker.workflows.validation import ValidationWorkflow

MERGE_POLL_INTERVAL = timedelta(seconds=60)


@dataclass(frozen=True, slots=True)
class ActionSpec:
    """手动动作的唯一注册表条目。"""

    handler: str
    statuses: frozenset[str]
    transition: str = ""
    feedback_mode: str = ""
    feedback_label: str = ""
    retry_failed_phase: bool = False
    refresh_configuration: bool = False
    note_required_statuses: frozenset[str] = frozenset()


def _statuses(*values: str) -> frozenset[str]:
    return frozenset(values)


ACTION_SPECS = {
    "owner_approved": ActionSpec(
        "_transition_action", _statuses("waiting_owner_approval"), transition="owner_approved",
    ),
    "owner_rejected": ActionSpec(
        "_transition_action", _statuses("waiting_owner_approval"), transition="owner_rejected",
    ),
    "start_modification": ActionSpec("_start_modification_action", _statuses("activated")),
    "patch_apply_approved": ActionSpec("_apply_patch_action", _statuses("modification_completed")),
    "patch_apply_rejected": ActionSpec(
        "_patch_rejected_action",
        _statuses("modification_completed"),
        feedback_mode="replace",
        feedback_label="人工审核反馈",
        note_required_statuses=_statuses("modification_completed"),
    ),
    "validation_passed": ActionSpec(
        "_transition_action", _statuses("patch_applied"), transition="validation_passed",
    ),
    "validation_rejected": ActionSpec(
        "_validation_rejected_action",
        _statuses("patch_applied"),
        feedback_mode="replace",
        feedback_label="人工验证反馈",
    ),
    "release_approved": ActionSpec("_release_action", _statuses("validation_passed")),
    "rework": ActionSpec(
        "_recovery_action",
        _statuses("worker_blocked", "validation_failed", "waiting_merge"),
        feedback_mode="append",
        feedback_label="重试补充",
        note_required_statuses=_statuses("waiting_merge"),
    ),
    "retry_current_phase": ActionSpec(
        "_recovery_action",
        _statuses("worker_blocked"),
        feedback_mode="append",
        feedback_label="重试补充",
        retry_failed_phase=True,
    ),
    "rework_with_latest_config": ActionSpec(
        "_recovery_action",
        _statuses("worker_blocked"),
        feedback_mode="append",
        feedback_label="重试补充",
        retry_failed_phase=True,
        refresh_configuration=True,
    ),
    "check_merge_status": ActionSpec("_poll_merge_action", _statuses("waiting_merge")),
    "cancel_case": ActionSpec(
        "_cancel_action",
        _statuses(
            "waiting_owner_approval",
            "activated",
            "worker_blocked",
            "modification_completed",
            "patch_rejected",
            "validation_failed",
            "validation_passed",
            "waiting_merge",
        ),
    ),
}


def feedback_text(context: dict) -> str:
    """从动作上下文提取用户可审计的反馈。"""

    return "\n".join(
        str(context.get(key, "")).strip()
        for key in ("note", "evidence")
        if str(context.get(key, "")).strip()
    )


def error_detail(error: BaseException) -> str:
    messages: list[str] = []
    current: BaseException | None = error
    while current is not None:
        if text := str(current):
            messages.append(text)
        current = current.__cause__
    return " | ".join(messages)


@workflow.defn(name="AsterismCaseWorkflow")
class AsterismCaseWorkflow(CodingWorkflow, PublishingWorkflow, ValidationWorkflow):
    def __init__(self) -> None:
        self.state = CaseState()
        self.case_input: CaseInput | None = None
        self.pending_actions: list[tuple[str, str, dict]] = []
        self.processed_signal_ids: set[str] = set()
        self.context_snapshot: ContextSnapshot | None = None
        self.completed_stage_results: list[ExecutionResult] = []
        self.merge_requests: list[MergeRequestRef] = []
        self.merged_repos: set[str] = set()
        self.gitlab_releases: list[dict] = []
        self.local_releases: list[dict] = []
        self.expected_remote_commits: dict[str, str] = {}
        self.validation_commands: list[dict] = []
        self.resume_phase = ""
        self.failed_phase = ""
        self.rework_feedback = ""
        self.revision = 0
        self.revision_mode = "full"

    @workflow.run
    async def run(self, case_input: CaseInput) -> str:
        if case_input.execution_architecture != CLAUDE_SDK_TEAM_ARCHITECTURE:
            raise ApplicationError(
                "仅支持 execution_architecture=claude_sdk_team，请清理旧测试 Case 后重建",
                non_retryable=True,
            )
        self.case_input = case_input
        while self.state.status not in TERMINAL_STATUSES:
            if self._is_gitlab() and self.state.status.value == "waiting_merge":
                try:
                    await workflow.wait_condition(
                        lambda: bool(self.pending_actions) or self.state.status in TERMINAL_STATUSES,
                        timeout=MERGE_POLL_INTERVAL,
                        timeout_summary="gitlab-mr-poll",
                    )
                except asyncio.TimeoutError:
                    await self._poll_merge_requests("temporal-timer")
            else:
                await workflow.wait_condition(
                    lambda: bool(self.pending_actions) or self.state.status in TERMINAL_STATUSES,
                )
            while self.pending_actions:
                action, signal_id, context = self.pending_actions.pop(0)
                self.processed_signal_ids.add(signal_id)
                accepted = await self._handle_action(action, signal_id, context)
                await self._emit("TemporalActionCompleted", signal_id, {
                    "action": action,
                    "signalId": signal_id,
                    "accepted": accepted,
                    "status": self.state.status.value,
                    **context,
                })
        return self.state.status.value

    @workflow.signal
    async def owner_approved(self, signal: dict) -> None:
        self._enqueue("owner_approved", signal)

    @workflow.signal
    async def start_modification(self, signal: dict) -> None:
        self._enqueue("start_modification", signal)

    @workflow.signal
    async def patch_apply_approved(self, signal: dict) -> None:
        self._enqueue("patch_apply_approved", signal)

    @workflow.signal
    async def patch_apply_rejected(self, signal: dict) -> None:
        self._enqueue("patch_apply_rejected", signal)

    @workflow.signal
    async def validation_passed(self, signal: dict) -> None:
        self._enqueue("validation_passed", signal)

    @workflow.signal
    async def validation_rejected(self, signal: dict) -> None:
        self._enqueue("validation_rejected", signal)

    @workflow.signal
    async def rework(self, signal: dict) -> None:
        self._enqueue("rework", signal)

    @workflow.signal
    async def retry_current_phase(self, signal: dict) -> None:
        self._enqueue("retry_current_phase", signal)

    @workflow.signal
    async def rework_with_latest_config(self, signal: dict) -> None:
        self._enqueue("rework_with_latest_config", signal)

    @workflow.signal
    async def release_approved(self, signal: dict) -> None:
        self._enqueue("release_approved", signal)

    @workflow.signal
    async def check_merge_status(self, signal: dict) -> None:
        self._enqueue("check_merge_status", signal)

    @workflow.signal
    async def cancel_case(self, signal: dict) -> None:
        self._enqueue("cancel_case", signal)

    @workflow.signal
    async def owner_rejected(self, signal: dict) -> None:
        self._enqueue("owner_rejected", signal)

    @workflow.query
    def current_status(self) -> str:
        return self.state.status.value

    async def _handle_action(self, action: str, signal_id: str, context: dict) -> bool:
        spec = ACTION_SPECS.get(action)
        if spec is None or self.state.status.value not in spec.statuses:
            workflow.logger.warning(
                "非法或过期 signal，已拒绝", extra={"action": action, "status": self.state.status.value},
            )
            return False
        if self.state.status.value in spec.note_required_statuses and not str(context.get("note", "")).strip():
            workflow.logger.warning("动作缺少必填意见", extra={"action": action})
            return False
        feedback = feedback_text(context)
        if feedback and spec.feedback_mode:
            self._capture_feedback(spec, feedback)
        return await getattr(self, spec.handler)(action, spec, signal_id, context)

    def _capture_feedback(self, spec: ActionSpec, feedback: str) -> None:
        """审核意见建立修订基线，重试备注只追加上下文。"""

        previous = self.rework_feedback if spec.feedback_mode == "append" else ""
        self.rework_feedback = "\n".join(
            item for item in (previous, f"{spec.feedback_label}：{feedback}") if item
        )

    async def _transition_action(
        self, _action: str, spec: ActionSpec, signal_id: str, context: dict,
    ) -> bool:
        await self._emit(getattr(self.state, spec.transition)(), signal_id, context)
        return True

    async def _start_modification_action(
        self, _action: str, _spec: ActionSpec, signal_id: str, _context: dict,
    ) -> bool:
        await self._start_modification(signal_id)
        return True

    async def _apply_patch_action(
        self, _action: str, _spec: ActionSpec, signal_id: str, _context: dict,
    ) -> bool:
        await self._apply_patch(signal_id)
        return True

    async def _release_action(
        self, _action: str, _spec: ActionSpec, signal_id: str, _context: dict,
    ) -> bool:
        await self._release(signal_id)
        return True

    async def _poll_merge_action(
        self, _action: str, _spec: ActionSpec, signal_id: str, _context: dict,
    ) -> bool:
        await self._poll_merge_requests(signal_id)
        return True

    async def _validation_rejected_action(
        self, _action: str, _spec: ActionSpec, signal_id: str, context: dict,
    ) -> bool:
        failed = await self._revert_if_needed(signal_id)
        if failed:
            await self._block_worker(signal_id, "patch_revert_failed", RuntimeError(failed))
            return True
        await self._emit(self.state.validation_rejected(), signal_id, context)
        return True

    async def _cancel_action(
        self, _action: str, _spec: ActionSpec, signal_id: str, context: dict,
    ) -> bool:
        if self.state.status == LifecycleStatus.validation_passed:
            failed = await self._revert_if_needed(signal_id)
            if failed:
                await self._block_worker(signal_id, "patch_revert_failed", RuntimeError(failed))
                return True
        await self._emit(self.state.cancel_case(), signal_id, context)
        return True

    def _enqueue(self, action: str, signal: dict) -> None:
        context = dict(signal)
        signal_id = str(context.pop("signal_id", ""))
        if not signal_id:
            workflow.logger.warning("忽略缺少 signal_id 的手动动作", extra={"action": action})
            return
        duplicate = signal_id in self.processed_signal_ids or any(
            item[1] == signal_id for item in self.pending_actions
        )
        if duplicate:
            workflow.logger.info("忽略重复手动动作", extra={"action": action, "signal_id": signal_id})
            return
        self.pending_actions.append((action, signal_id, context))

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
            # 投影必须最终送达，控制面短暂不可用不能丢事件。
            retry_policy=RetryPolicy(
                initial_interval=timedelta(seconds=1),
                backoff_coefficient=2,
                maximum_interval=timedelta(seconds=60),
            ),
        )

    async def _block_worker(
        self,
        signal_id: str,
        reason: str,
        error: BaseException,
        extra: dict | None = None,
        phase: ExecutionPhase | None = None,
    ) -> None:
        self.failed_phase = phase.value if phase is not None else ""
        phase_payload = {"failedPhase": self.failed_phase} if self.failed_phase else {}
        revision_payload = {
            "revision": self.revision,
            "revisionMode": self.revision_mode,
        } if self.revision else {}
        await self._emit(self.state.worker_blocked_on(reason), signal_id, {
            "reason": reason,
            "detail": self._error_detail(error),
            **phase_payload,
            **revision_payload,
            **(extra or {}),
        })

    def _error_detail(self, error: BaseException) -> str:
        return error_detail(error)

    def _case_input(self) -> CaseInput:
        if self.case_input is None:
            raise RuntimeError("case input is not initialized")
        return self.case_input

    def _is_gitlab(self) -> bool:
        return self._case_input().release_mode == "gitlab"
