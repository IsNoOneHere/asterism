import asyncio
from dataclasses import dataclass
from datetime import timedelta

from temporalio import workflow
from temporalio.common import RetryPolicy
from temporalio.exceptions import ApplicationError

from asterism_worker.contracts import (
    ArtifactEvidenceRequest,
    ArtifactRef,
    ArtifactTransitionRequest,
    CaseInput,
    CodingPlanDraft,
    ContextSnapshot,
    ExecutionResult,
    LifecycleStatus,
    MergeRequestRef,
    ProjectionEvent,
    ProjectionResult,
)
from asterism_worker.workflows.coding import (
    CLAUDE_SDK_TEAM_ARCHITECTURE,
    CodingWorkflow,
    ExecutionPhase,
)
from asterism_worker.workflows.coding_support import discard_candidate_checkpoint
from asterism_worker.workflows.planning import PlanningWorkflow
from asterism_worker.workflows.publishing import PublishingWorkflow
from asterism_worker.workflows.state_machine import CaseState, TERMINAL_STATUSES
from asterism_worker.workflows.validation import ValidationWorkflow
from asterism_worker.workflows.workflow_support import error_detail, feedback_text

MERGE_POLL_INTERVAL = timedelta(seconds=60)
EVIDENCE_EVENTS = frozenset({
    "WorkerBlocked",
    "ReworkStarted",
    "RevisionRequested",
    "PatchApplied",
    "PatchApplyBlocked",
    "PatchRejected",
    "ValidationPassed",
    "ValidationFailed",
    "RepositoryReleasePrepared",
    "MergeRequestCreated",
    "MergeRequestMerged",
    "MergeRequestClosed",
    "ReleaseCompleted",
})
ARTIFACT_ACTIONS = {
    "coding_plan_approved": ("PLANNING", frozenset({"PROPOSED"})),
    "coding_plan_rejected": ("PLANNING", frozenset({"PROPOSED"})),
    "patch_apply_approved": ("CODING", frozenset({"PROPOSED", "APPROVED"})),
    "patch_apply_rejected": ("CODING", frozenset({"PROPOSED", "APPROVED"})),
    "validation_passed": ("CODING", frozenset({"PROPOSED", "APPROVED"})),
    "validation_rejected": ("CODING", frozenset({"PROPOSED", "APPROVED"})),
    "validation_retry": ("VALIDATION", frozenset({"APPROVED"})),
    "validation_rework_coding": ("VALIDATION", frozenset({"APPROVED"})),
    "validation_rework_planning": ("VALIDATION", frozenset({"APPROVED"})),
    "release_approved": ("VALIDATION", frozenset({"APPROVED"})),
    "release_retry": ("VALIDATION", frozenset({"APPROVED"})),
    "release_revalidate": ("VALIDATION", frozenset({"APPROVED"})),
    "release_rework_coding": ("VALIDATION", frozenset({"APPROVED"})),
}
SELECTED_CODING_ACTIONS = frozenset({
    "patch_apply_approved", "patch_apply_rejected",
    "validation_passed", "validation_rejected",
})
VALIDATION_REWORK_ACTIONS = frozenset({
    "validation_rework_coding", "validation_rework_planning",
})
RESULT_ARTIFACT_PATCH = "validation-release-artifacts-v1"
RESULT_ARTIFACT_VERSION = 1


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
    refresh_requirement_context: bool = False
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
    "artifact_version_selected": ActionSpec(
        "_artifact_version_selected_action",
        _statuses(
            "waiting_owner_approval",
            "activated",
            "worker_blocked",
            "modification_completed",
            "patch_rejected",
            "patch_applied",
            "validation_failed",
            "validation_passed",
            "waiting_merge",
        ),
    ),
    "start_modification": ActionSpec("_start_modification_action", _statuses("activated")),
    "coding_plan_approved": ActionSpec("_plan_approved_action", _statuses("activated")),
    "coding_plan_rejected": ActionSpec(
        "_plan_rejected_action",
        _statuses("activated"),
        feedback_mode="replace",
        feedback_label="计划打回意见",
        note_required_statuses=_statuses("activated"),
    ),
    "interrupt_attempt": ActionSpec(
        "_interrupt_attempt_action",
        _statuses("activated"),
        feedback_mode="append",
        feedback_label="停止原因",
    ),
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
    "validation_retry": ActionSpec(
        "_validation_retry_action", _statuses("validation_failed"),
    ),
    "validation_rework_coding": ActionSpec(
        "_validation_rework_coding_action", _statuses("validation_failed", "validation_passed"),
        feedback_mode="replace", feedback_label="代码返工意见",
        note_required_statuses=_statuses("validation_failed", "validation_passed"),
    ),
    "validation_rework_planning": ActionSpec(
        "_validation_rework_planning_action", _statuses("validation_failed", "validation_passed"),
        feedback_mode="replace", feedback_label="规划返工意见",
        note_required_statuses=_statuses("validation_failed", "validation_passed"),
    ),
    "release_approved": ActionSpec("_release_action", _statuses("validation_passed")),
    "release_retry": ActionSpec("_release_retry_action", _statuses("worker_blocked")),
    "release_revalidate": ActionSpec(
        "_release_revalidate_action", _statuses("worker_blocked", "waiting_merge"),
    ),
    "release_rework_coding": ActionSpec(
        "_release_rework_coding_action", _statuses("worker_blocked", "waiting_merge"),
        feedback_mode="replace", feedback_label="发布返工意见",
        note_required_statuses=_statuses("worker_blocked", "waiting_merge"),
    ),
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
    "rework_with_latest_context": ActionSpec(
        "_recovery_action",
        _statuses("worker_blocked"),
        feedback_mode="append",
        feedback_label="上下文刷新说明",
        refresh_requirement_context=True,
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


@workflow.defn(name="AsterismCaseWorkflow")
class AsterismCaseWorkflow(PlanningWorkflow, CodingWorkflow, PublishingWorkflow, ValidationWorkflow):
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
        self.coding_plan: CodingPlanDraft | None = None
        self.plan_revision = 0
        self.coding_session_id = ""
        self.product_artifact: ArtifactRef | None = None
        self.planning_artifact: ArtifactRef | None = None
        self.coding_artifact: ArtifactRef | None = None
        self.validation_artifact: ArtifactRef | None = None
        self.release_artifact: ArtifactRef | None = None
        self.effective_heads: dict[str, ArtifactRef] = {}
        self.artifact_mode = False
        self.result_artifact_mode = False
        self.active_coding_activity = None
        self.coding_interrupt_requested = False
        self.coding_attempt_running = False

    @workflow.run
    async def run(self, case_input: CaseInput) -> str:
        if case_input.execution_architecture != CLAUDE_SDK_TEAM_ARCHITECTURE:
            raise ApplicationError(
                "仅支持 execution_architecture=claude_sdk_team，请清理旧测试 Case 后重建",
                non_retryable=True,
            )
        self.case_input = case_input
        self.product_artifact = case_input.prd.product_artifact
        self.artifact_mode = self.product_artifact is not None
        # 旧历史重放返回 False；新 Case 才写入五类结果产物命令。
        self.result_artifact_mode = self.artifact_mode and workflow.patched(RESULT_ARTIFACT_PATCH)
        if self.artifact_mode:
            if (
                self.product_artifact.artifact_type != "PRODUCT"
                or self.product_artifact.status != "APPROVED"
                or self.product_artifact.parent_artifact_id is not None
            ):
                raise ApplicationError("Case 必须携带有效 Approved ProductArtifactRef", non_retryable=True)
            self.effective_heads = {"PRODUCT": self.product_artifact}
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
    async def artifact_version_selected(self, signal: dict) -> None:
        self._enqueue("artifact_version_selected", signal)

    @workflow.signal
    async def coding_plan_approved(self, signal: dict) -> None:
        self._enqueue("coding_plan_approved", signal)

    @workflow.signal
    async def coding_plan_rejected(self, signal: dict) -> None:
        self._enqueue("coding_plan_rejected", signal)

    @workflow.signal
    async def interrupt_attempt(self, signal: dict) -> None:
        """停止当前 Coding Activity；业务收敛仍由主循环按动作顺序处理。"""

        self._enqueue("interrupt_attempt", signal)
        handle = self.active_coding_activity
        if self.coding_attempt_running and handle is None:
            self.coding_interrupt_requested = True
        if handle is not None and not handle.done():
            self.coding_interrupt_requested = True
            handle.cancel()

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
    async def validation_retry(self, signal: dict) -> None:
        self._enqueue("validation_retry", signal)

    @workflow.signal
    async def validation_rework_coding(self, signal: dict) -> None:
        self._enqueue("validation_rework_coding", signal)

    @workflow.signal
    async def validation_rework_planning(self, signal: dict) -> None:
        self._enqueue("validation_rework_planning", signal)

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
    async def rework_with_latest_context(self, signal: dict) -> None:
        self._enqueue("rework_with_latest_context", signal)

    @workflow.signal
    async def release_approved(self, signal: dict) -> None:
        self._enqueue("release_approved", signal)

    @workflow.signal
    async def release_retry(self, signal: dict) -> None:
        self._enqueue("release_retry", signal)

    @workflow.signal
    async def release_revalidate(self, signal: dict) -> None:
        self._enqueue("release_revalidate", signal)

    @workflow.signal
    async def release_rework_coding(self, signal: dict) -> None:
        self._enqueue("release_rework_coding", signal)

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
        if not self._validate_action_artifact(action, context):
            return False
        if context.pop("_restore_selected_coding", False):
            # 版本确认前先从持久化 CodingArtifact 恢复精确 Diff，不能沿用切换前的 Workflow 缓存。
            if not await self._restore_selected_coding_candidate(signal_id):
                return True
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
        event_type = getattr(self.state, spec.transition)()
        transition = None
        if event_type == "ValidationPassed" and not self.result_artifact_mode:
            coding = self._require_artifact(self.coding_artifact, "CODING")
            if coding.status == "PROPOSED":
                transition = ArtifactTransitionRequest(
                    kind="ApproveCodingArtifact",
                    transition_id=f"{self._case_input().case_id}:approve-coding:{signal_id}",
                    artifact=coding,
                    expected_head=self._head("CODING"),
                    note=str(context.get("evidence", "")).strip(),
                )
        elif event_type == "ValidationPassed":
            coding = self._require_artifact(self.coding_artifact, "CODING")
            if coding.status != "APPROVED":
                raise ApplicationError(
                    "新结果模式要求 CodingArtifact 已在 PatchApplied 批准",
                    non_retryable=True,
                )
        await self._emit(event_type, signal_id, context, artifact_transition=transition)
        return True

    async def _start_modification_action(
        self, _action: str, _spec: ActionSpec, signal_id: str, _context: dict,
    ) -> bool:
        await self._start_modification(signal_id)
        return True

    async def _artifact_version_selected_action(
        self, _action: str, _spec: ActionSpec, signal_id: str, context: dict,
    ) -> bool:
        """同步控制面选中的完整有效链路，不增加新的生命周期状态。"""

        try:
            selected = self._sync_selected_artifact_route(context)
        except Exception:
            workflow.logger.warning("Artifact 版本切换缺少完整有效路线")
            return False
        workflow.logger.info(
            "Artifact 有效路线已同步",
            extra={
                "selected_type": selected.artifact_type,
                "selected_version": selected.version,
            },
        )
        if (
            self.state.status == LifecycleStatus.activated
            and signal_id.startswith("artifact-version-continue-")
        ):
            planning = self.planning_artifact
            coding = self.coding_artifact
            if planning is None:
                await self._propose_coding_plan(signal_id, new_session=True)
            else:
                await self._start_supervised_modification(
                    signal_id, reuse_context=False, reuse_candidate=coding is not None,
                )
        return True

    def _sync_selected_artifact_route(self, context: dict) -> ArtifactRef:
        """把控制面选中的完整有效路线同步到 Workflow 内存。"""

        product = ArtifactRef.model_validate(context.get("product_artifact"))
        planning = self._optional_selected_artifact(context.get("planning_artifact"), "PLANNING")
        coding = self._optional_selected_artifact(context.get("coding_artifact"), "CODING")
        validation = self._optional_selected_artifact(context.get("validation_artifact"), "VALIDATION")
        release = self._optional_selected_artifact(context.get("release_artifact"), "RELEASE")
        selected = ArtifactRef.model_validate(context.get("selected_artifact"))
        if (
            product.artifact_type != "PRODUCT"
            or product.status != "APPROVED"
            or product.parent_artifact_id is not None
            or planning is not None and planning.parent_artifact_id != product.artifact_id
            or coding is not None and (
                planning is None or coding.parent_artifact_id != planning.artifact_id
            )
            or validation is not None and (
                coding is None or validation.parent_artifact_id != coding.artifact_id
            )
            or release is not None and (
                validation is None or release.parent_artifact_id != validation.artifact_id
            )
        ):
            raise ValueError("Artifact 版本切换路线不完整")
        route = {
            "PRODUCT": product,
            **({"PLANNING": planning} if planning is not None else {}),
            **({"CODING": coding} if coding is not None else {}),
            **({"VALIDATION": validation} if validation is not None else {}),
            **({"RELEASE": release} if release is not None else {}),
        }
        if route.get(selected.artifact_type) != selected:
            raise ValueError("Artifact 版本切换目标不属于有效路线")
        manifest_id = str(context.get("requirement_manifest_id", "")).strip()
        if not manifest_id:
            raise ValueError("Artifact 版本切换缺少需求上下文版本")

        previous_product = self.product_artifact
        previous_planning = self.planning_artifact
        previous_coding = self.coding_artifact
        self.product_artifact = product
        self.planning_artifact = planning
        self.coding_artifact = coding
        self.validation_artifact = validation
        self.release_artifact = release
        self.effective_heads = route
        if product != previous_product or planning != previous_planning or coding != previous_coding:
            self.context_snapshot = None
        # 历史 Case 在第一次选定有效路线后切入 Artifact 模式，之后直接读取持久化产物。
        self.artifact_mode = True
        case_input = self._case_input()
        self.case_input = case_input.model_copy(update={
            "prd": case_input.prd.model_copy(update={
                "requirement_manifest_id": manifest_id,
                "product_artifact": product,
            }),
        })
        if product != previous_product or planning != previous_planning:
            # 上游版本变化后不再复用旧 Agent Session，后续 Context 从选中链路重建。
            self.coding_session_id = ""
        return selected

    def _optional_selected_artifact(
        self, value: object, artifact_type: str,
    ) -> ArtifactRef | None:
        if value is None:
            return None
        reference = ArtifactRef.model_validate(value)
        if reference.artifact_type != artifact_type or reference.status != "APPROVED":
            raise ValueError("Artifact 有效路线类型或状态不正确")
        return reference

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

    async def _validation_retry_action(
        self, _action: str, _spec: ActionSpec, signal_id: str, _context: dict,
    ) -> bool:
        if not await self._begin_result_rework(signal_id, "validation_retry"):
            return False
        return await self._restore_and_apply_coding(signal_id)

    async def _validation_rework_coding_action(
        self, _action: str, _spec: ActionSpec, signal_id: str, context: dict,
    ) -> bool:
        if self.state.status == LifecycleStatus.validation_passed:
            failed = await self._revert_if_needed(signal_id)
            if failed:
                await self._block_worker(signal_id, "patch_revert_failed", RuntimeError(failed))
                return True
        accepted = await self._request_revision(signal_id, context, "validation")
        if accepted:
            self.validation_artifact = None
            self.release_artifact = None
            self.effective_heads.pop("VALIDATION", None)
            self.effective_heads.pop("RELEASE", None)
        return accepted

    async def _validation_rework_planning_action(
        self, _action: str, _spec: ActionSpec, signal_id: str, _context: dict,
    ) -> bool:
        if self.state.status == LifecycleStatus.validation_passed:
            failed = await self._revert_if_needed(signal_id)
            if failed:
                await self._block_worker(signal_id, "patch_revert_failed", RuntimeError(failed))
                return True
        previous_planning = self.planning_artifact
        if previous_planning is None or not await self._begin_result_rework(signal_id, "planning"):
            return False
        self.coding_artifact = None
        self.validation_artifact = None
        self.release_artifact = None
        self.effective_heads.pop("CODING", None)
        self.effective_heads.pop("VALIDATION", None)
        self.effective_heads.pop("RELEASE", None)
        self.coding_session_id = ""
        discard_candidate_checkpoint(self)
        await self._propose_coding_plan(
            signal_id, previous_artifact=previous_planning, new_session=True,
        )
        return True

    async def _release_retry_action(
        self, _action: str, _spec: ActionSpec, signal_id: str, _context: dict,
    ) -> bool:
        if self.failed_phase != ExecutionPhase.release.value:
            workflow.logger.warning("当前阻塞不是发布阶段，拒绝重试发布")
            return False
        if not await self._begin_result_rework(signal_id, "release_retry"):
            return False
        self.release_artifact = None
        self.effective_heads.pop("RELEASE", None)
        return await self._retry_phase(signal_id, ExecutionPhase.release)

    async def _release_revalidate_action(
        self, _action: str, _spec: ActionSpec, signal_id: str, _context: dict,
    ) -> bool:
        if not await self._begin_result_rework(signal_id, "release_revalidate"):
            return False
        self.release_artifact = None
        self.effective_heads.pop("RELEASE", None)
        # 发布阶段 Patch 仍在工作区或已提交，只恢复检查点并重新验证，不能重复 apply。
        return await self._retry_phase(signal_id, ExecutionPhase.validation)

    async def _release_rework_coding_action(
        self, _action: str, _spec: ActionSpec, signal_id: str, context: dict,
    ) -> bool:
        accepted = await self._request_revision(signal_id, context, "merge")
        if accepted:
            self.validation_artifact = None
            self.release_artifact = None
            self.effective_heads.pop("VALIDATION", None)
            self.effective_heads.pop("RELEASE", None)
        return accepted

    async def _begin_result_rework(self, signal_id: str, target: str) -> bool:
        event_type = self.state.rework()
        await self._emit(event_type, signal_id, {
            "retryScope": target,
            "codingArtifactId": self.coding_artifact.artifact_id if self.coding_artifact else "",
            "validationArtifactId": (
                self.validation_artifact.artifact_id if self.validation_artifact else ""
            ),
        })
        self.failed_phase = ""
        return event_type is not None

    async def _restore_and_apply_coding(self, signal_id: str) -> bool:
        """失败验证已回滚工作区，必须先按精确 CodingArtifact 重放 Patch。"""

        result = await self._load_coding_artifact_candidate(signal_id, ExecutionPhase.patch)
        if result is None:
            return False
        if not await self._restore_lifecycle_checkpoint(
            signal_id,
            ExecutionPhase.patch,
            LifecycleStatus.modification_completed,
            result.diff_patch,
        ):
            return False
        await self._apply_patch(signal_id)
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
        coding = self._require_artifact(self.coding_artifact, "CODING")
        transition = None
        if coding.status == "PROPOSED":
            transition = ArtifactTransitionRequest(
                kind="RejectCodingArtifact",
                transition_id=f"{self._case_input().case_id}:reject-coding:{signal_id}",
                artifact=coding,
                expected_head=self._head("CODING"),
                note=self.rework_feedback
                or str(context.get("note") or context.get("evidence") or "人工验证未通过"),
            )
        await self._emit(
            self.state.validation_rejected(),
            signal_id,
            context,
            artifact_transition=transition,
        )
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

    async def _emit(
        self,
        event_type: str | None,
        signal_id: str,
        payload: dict,
        suffix: str = "",
        artifact_transition: ArtifactTransitionRequest | None = None,
        artifact_evidence: ArtifactEvidenceRequest | None = None,
    ) -> None:
        if event_type is None:
            return
        case_input = self._case_input()
        causation_id = f"{signal_id}:{suffix}" if suffix else signal_id
        if not self.artifact_mode:
            # 旧历史中的 Activity 返回值为空，且没有 Artifact Transition 契约。
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
                event.model_dump(
                    by_alias=True,
                    exclude_none=True,
                    exclude={"artifact_transition", "artifact_evidence"},
                ),
                start_to_close_timeout=timedelta(seconds=20),
                retry_policy=RetryPolicy(
                    initial_interval=timedelta(seconds=1),
                    backoff_coefficient=2,
                    maximum_interval=timedelta(seconds=60),
                ),
            )
            return
        event_payload = dict(payload)
        if self.product_artifact:
            event_payload.setdefault("productArtifactId", self.product_artifact.artifact_id)
        if self.planning_artifact:
            event_payload.setdefault("planningArtifactId", self.planning_artifact.artifact_id)
        if self.coding_artifact:
            event_payload.setdefault("codingArtifactId", self.coding_artifact.artifact_id)
        if self.validation_artifact:
            event_payload.setdefault("validationArtifactId", self.validation_artifact.artifact_id)
        if self.release_artifact:
            event_payload.setdefault("releaseArtifactId", self.release_artifact.artifact_id)
        materializes_result = self._materializes_result_artifact(event_type, event_payload)
        if materializes_result:
            # 时间来自 Temporal，重放时保持完全一致，控制面可严格校验幂等命令。
            event_payload["artifactResultVersion"] = RESULT_ARTIFACT_VERSION
            event_payload["completedAt"] = workflow.now().isoformat().replace("+00:00", "Z")
            event_payload.setdefault("validationMode", self._case_input().validation_mode.upper())
            event_payload.setdefault("releaseMode", self._case_input().release_mode)
            if event_type == "ReleaseCompleted":
                event_payload.setdefault("releaseId", f"{case_input.case_id}:release:{causation_id}")
                event_payload.setdefault("targetKey", "default")
            else:
                event_payload.setdefault(
                    "validationRunId", f"{case_input.case_id}:validation:{causation_id}",
                )
        if artifact_transition is None:
            artifact_transition = self._event_artifact_transition(
                event_type, causation_id, event_payload,
            )
        if artifact_evidence is None and event_type in EVIDENCE_EVENTS:
            target = (
                None if materializes_result
                else artifact_transition.artifact if artifact_transition
                else self._evidence_target()
            )
            artifact_evidence = ArtifactEvidenceRequest(
                evidence_id=f"{case_input.case_id}:{event_type}:{causation_id}:evidence",
                artifact=target,
                evidence_type=event_type,
                transition_id=artifact_transition.transition_id if artifact_transition else None,
                payload=event_payload,
            )
        event = ProjectionEvent(
            eventType=event_type,
            systemId=case_input.system_id,
            caseId=case_input.case_id,
            prdId=case_input.prd_id,
            workItemId=case_input.work_item_id,
            payload=event_payload,
            correlationId=case_input.case_id,
            causationId=causation_id,
            idempotencyKey=f"{case_input.case_id}:{event_type}:{causation_id}",
            artifactTransition=artifact_transition,
            artifactEvidence=artifact_evidence,
        )
        saved = await workflow.execute_activity(
            "send_projection_event",
            event.model_dump(by_alias=True, exclude_none=True),
            start_to_close_timeout=timedelta(seconds=20),
            # 投影必须最终送达，控制面短暂不可用不能丢事件。
            retry_policy=RetryPolicy(
                initial_interval=timedelta(seconds=1),
                backoff_coefficient=2,
                maximum_interval=timedelta(seconds=60),
            ),
        )
        result = ProjectionResult.model_validate(saved)
        if artifact_transition is not None:
            self._accept_transition(artifact_transition, result)
        elif materializes_result:
            self._accept_result_artifact(event_type, result)

    async def _fetch_artifact_context(
        self, phase: str, previous: ArtifactRef | None = None,
    ) -> ContextSnapshot:
        """每次 Snapshot 都携带精确链和当前真实 Git Head。"""

        case_input = self._case_input()
        revisions = await workflow.execute_activity(
            "capture_case_revisions",
            {
                "system_id": case_input.system_id,
                "work_item_id": case_input.work_item_id,
                "repos": [repo.model_dump() for repo in case_input.effective_repos()],
            },
            start_to_close_timeout=timedelta(minutes=3),
            retry_policy=RetryPolicy(maximum_attempts=2),
        )
        payload = await workflow.execute_activity(
            "fetch_context",
            {
                "system_id": case_input.system_id,
                "prd_id": case_input.prd_id,
                "work_item_id": case_input.work_item_id,
                "requirement_manifest_id": case_input.prd.requirement_manifest_id,
                "phase": phase,
                "product_artifact": self._require_artifact(self.product_artifact, "PRODUCT").model_dump(),
                "planning_artifact": (
                    self.planning_artifact.model_dump() if phase == "coding" and self.planning_artifact else None
                ),
                "previous_artifact": previous.model_dump() if previous else None,
                "git_base_revisions": dict(revisions),
            },
            start_to_close_timeout=timedelta(seconds=20),
            retry_policy=RetryPolicy(maximum_attempts=3),
        )
        snapshot = ContextSnapshot.model_validate(payload)
        self._accept_snapshot(snapshot, phase)
        return snapshot

    def _accept_snapshot(self, snapshot: ContextSnapshot, phase: str) -> None:
        product = self._require_artifact(snapshot.product_artifact, "PRODUCT")
        current_product = self._require_artifact(self.product_artifact, "PRODUCT")
        if product != current_product or snapshot.root_artifact_id != current_product.root_artifact_id:
            raise ApplicationError("Artifact Snapshot 返回了不同 Product 链", non_retryable=True)
        if phase == "coding":
            planning = self._require_artifact(snapshot.planning_artifact, "PLANNING")
            if planning != self._require_artifact(self.planning_artifact, "PLANNING"):
                raise ApplicationError("Artifact Snapshot 返回了不同 Planning 版本", non_retryable=True)
            if planning.parent_artifact_id != product.artifact_id:
                raise ApplicationError("PlanningArtifact 父链不一致", non_retryable=True)
        self.product_artifact = product
        self.planning_artifact = snapshot.planning_artifact or self.planning_artifact
        self.effective_heads = dict(snapshot.effective_heads)

    def _accept_transition(
        self, request: ArtifactTransitionRequest, result: ProjectionResult,
    ) -> None:
        transition_id = (
            str(result.transition.get("transitionId", ""))
            if result.transition is not None else ""
        )
        if transition_id != request.transition_id:
            raise ApplicationError("Artifact Transition 返回 transitionId 不一致", non_retryable=True)
        reference = result.artifact_ref
        if reference is None:
            raise ApplicationError("Artifact Transition 未返回 ArtifactRef", non_retryable=True)
        kind = request.kind
        expected_type = (
            self._require_artifact(request.artifact, request.artifact.artifact_type).artifact_type
            if kind == "SupersedeArtifact" and request.artifact is not None
            else "PLANNING" if "Planning" in kind else "CODING"
        )
        expected_status = (
            "PROPOSED" if kind.startswith("Propose")
            else "APPROVED" if kind.startswith("Approve")
            else "REJECTED" if kind.startswith("Reject")
            else "SUPERSEDED"
        )
        if reference.artifact_type != expected_type or reference.status != expected_status:
            raise ApplicationError("Artifact Transition 返回类型或状态不一致", non_retryable=True)
        if not reference.content_hash or reference.version < 1:
            raise ApplicationError("Artifact Transition 返回版本或 Hash 无效", non_retryable=True)
        if kind.startswith("Propose"):
            parent = self._require_artifact(request.parent, "PRODUCT" if expected_type == "PLANNING" else "PLANNING")
            if (
                reference.root_artifact_id != parent.root_artifact_id
                or reference.parent_artifact_id != parent.artifact_id
                or reference.supersedes_artifact_id
                != (request.supersedes.artifact_id if request.supersedes else None)
            ):
                raise ApplicationError("Artifact Proposal 返回父链或替代关系不一致", non_retryable=True)
        else:
            current = self._require_artifact(request.artifact, expected_type)
            if (
                reference.artifact_id != current.artifact_id
                or reference.version != current.version
                or reference.content_hash != current.content_hash
                or reference.root_artifact_id != current.root_artifact_id
                or reference.parent_artifact_id != current.parent_artifact_id
                or reference.supersedes_artifact_id != current.supersedes_artifact_id
            ):
                raise ApplicationError("Artifact Transition 返回的精确版本不一致", non_retryable=True)
        previous = self.effective_heads.get(expected_type)
        if expected_type == "PLANNING":
            self.planning_artifact = reference
        else:
            self.coding_artifact = reference
        if expected_status == "APPROVED":
            self.effective_heads[expected_type] = reference
            if previous is None or previous.artifact_id != reference.artifact_id:
                self._clear_downstream(expected_type)
        elif expected_status == "SUPERSEDED":
            head = self.effective_heads.get(expected_type)
            if head and head.artifact_id == reference.artifact_id:
                self.effective_heads.pop(expected_type, None)
                self._clear_downstream(expected_type)

    def _accept_result_artifact(self, event_type: str, result: ProjectionResult) -> None:
        reference = result.artifact_ref
        if reference is None or reference.status != "APPROVED":
            raise ApplicationError("结果事件未返回 Approved ArtifactRef", non_retryable=True)
        if event_type == "ReleaseCompleted":
            validation = self._require_artifact(self.validation_artifact, "VALIDATION")
            if (
                reference.artifact_type != "RELEASE"
                or reference.root_artifact_id != validation.root_artifact_id
                or reference.parent_artifact_id != validation.artifact_id
            ):
                raise ApplicationError("ReleaseArtifact 返回的精确 Validation 父链不一致", non_retryable=True)
            self.release_artifact = reference
            self.effective_heads["RELEASE"] = reference
            return
        coding = self._require_artifact(self.coding_artifact, "CODING")
        if (
            reference.artifact_type != "VALIDATION"
            or reference.root_artifact_id != coding.root_artifact_id
            or reference.parent_artifact_id != coding.artifact_id
        ):
            raise ApplicationError("ValidationArtifact 返回的精确 Coding 父链不一致", non_retryable=True)
        self.validation_artifact = reference
        self.release_artifact = None
        self.effective_heads["VALIDATION"] = reference
        self.effective_heads.pop("RELEASE", None)

    def _clear_downstream(self, artifact_type: str) -> None:
        order = ["PRODUCT", "PLANNING", "CODING", "VALIDATION", "RELEASE"]
        if artifact_type not in order:
            return
        for value in order[order.index(artifact_type) + 1:]:
            self.effective_heads.pop(value, None)
        if artifact_type in {"PRODUCT", "PLANNING"}:
            self.coding_artifact = None
        if artifact_type in {"PRODUCT", "PLANNING", "CODING"}:
            self.validation_artifact = None
        if artifact_type != "RELEASE":
            self.release_artifact = None

    def _validate_action_artifact(self, action: str, context: dict) -> bool:
        if not self.artifact_mode:
            return True
        requirement = ARTIFACT_ACTIONS.get(action)
        if requirement is None:
            return True
        if (
            not self.result_artifact_mode
            and (action == "release_approved" or action in VALIDATION_REWORK_ACTIONS)
        ):
            # Temporal patch 之前的在途 Case 没有 ValidationArtifact，沿用精确 Coding Head。
            requirement = ("CODING", frozenset({"APPROVED"}))
        raw = context.pop("artifact_ref", None)
        try:
            provided = ArtifactRef.model_validate(raw)
        except Exception:
            workflow.logger.warning("人工动作缺少精确 ArtifactRef", extra={"action": action})
            return False
        if (
            provided.status == "APPROVED"
            and requirement[0] == "CODING"
            and (
                action in SELECTED_CODING_ACTIONS
                or action == "release_approved"
                or action in VALIDATION_REWORK_ACTIONS
            )
        ):
            try:
                selected = self._sync_selected_artifact_route(context)
            except Exception:
                workflow.logger.warning("人工动作缺少当前 CodingArtifact 有效路线", extra={"action": action})
                return False
            if selected != provided:
                workflow.logger.warning("人工动作与当前 CodingArtifact 路线不一致", extra={"action": action})
                return False
            if action in {"patch_apply_approved", "patch_apply_rejected"}:
                context["_restore_selected_coding"] = True
            for key in (
                "selected_type", "selected_artifact", "product_artifact",
                "planning_artifact", "coding_artifact", "validation_artifact",
                "release_artifact", "requirement_manifest_id",
            ):
                context.pop(key, None)
        elif requirement[0] == "VALIDATION":
            try:
                selected = self._sync_selected_artifact_route(context)
            except Exception:
                workflow.logger.warning("人工动作缺少当前 ValidationArtifact 有效路线", extra={"action": action})
                return False
            if selected != provided:
                workflow.logger.warning("人工动作与当前 ValidationArtifact 路线不一致", extra={"action": action})
                return False
            for key in (
                "selected_type", "selected_artifact", "product_artifact",
                "planning_artifact", "coding_artifact", "validation_artifact",
                "release_artifact", "requirement_manifest_id",
            ):
                context.pop(key, None)
        current = {
            "PLANNING": self.planning_artifact,
            "CODING": self.coding_artifact,
            "VALIDATION": self.validation_artifact,
            "RELEASE": self.release_artifact,
        }.get(requirement[0])
        if (
            provided != current
            or provided.artifact_type != requirement[0]
            or provided.status not in requirement[1]
        ):
            workflow.logger.warning("人工动作 ArtifactRef 已过期", extra={"action": action})
            return False
        return True

    def _materializes_result_artifact(self, event_type: str, payload: dict) -> bool:
        if not self.result_artifact_mode:
            return False
        return event_type in {"ValidationPassed", "ValidationFailed", "ReleaseCompleted"} or (
            event_type == "WorkerBlocked" and payload.get("failedPhase") == "validation"
        )

    def _evidence_target(self) -> ArtifactRef:
        target = (
            self.release_artifact or self.validation_artifact
            or self.coding_artifact or self.planning_artifact or self.product_artifact
        )
        if target is None:
            raise ApplicationError("Artifact Evidence 缺少目标", non_retryable=True)
        return target

    def _event_artifact_transition(
        self, event_type: str, causation_id: str, payload: dict,
    ) -> ArtifactTransitionRequest | None:
        """自动验证和 Patch 打回同样必须走类型化 Transition 主动作。"""

        allowed = (
            {"PatchApplied", "PatchRejected"}
            if self.result_artifact_mode
            else {"PatchRejected", "ValidationPassed", "ValidationFailed"}
        )
        if event_type not in allowed:
            return None
        coding = self._require_artifact(self.coding_artifact, "CODING")
        if coding.status != "PROPOSED":
            # 已切换为有效 Head 的代码无需重复审批或打回状态，只记录本次阶段证据。
            return None
        approving = event_type in {"PatchApplied", "ValidationPassed"}
        note = str(
            (self.rework_feedback if event_type == "PatchRejected" else "")
            or payload.get("note")
            or payload.get("evidence")
            or payload.get("failedCommand")
            or payload.get("stderrTail")
            or ("验证通过" if approving else "代码结果未通过")
        ).strip()
        return ArtifactTransitionRequest(
            kind="ApproveCodingArtifact" if approving else "RejectCodingArtifact",
            transition_id=(
                f"{self._case_input().case_id}:{event_type}:{causation_id}:artifact"
            ),
            artifact=coding,
            expected_head=self._head("CODING"),
            note=note,
        )

    def _head(self, artifact_type: str) -> ArtifactRef | None:
        return self.effective_heads.get(artifact_type)

    def _require_artifact(self, value: ArtifactRef | None, artifact_type: str) -> ArtifactRef:
        if value is None or value.artifact_type != artifact_type:
            raise ApplicationError(f"缺少精确 {artifact_type} ArtifactRef", non_retryable=True)
        return value

    async def _block_worker(
        self,
        signal_id: str,
        reason: str,
        error: BaseException,
        extra: dict | None = None,
        phase: ExecutionPhase | None = None,
        execution_evidence: dict | None = None,
    ) -> None:
        self.failed_phase = phase.value if phase is not None else ""
        phase_payload = {"failedPhase": self.failed_phase} if self.failed_phase else {}
        revision_payload = {
            "revision": self.revision,
            "revisionMode": self.revision_mode,
        } if self.revision else {}
        payload = {
            "reason": reason,
            "detail": self._error_detail(error),
            **phase_payload,
            **revision_payload,
            **(extra or {}),
        }
        transition = self._blocked_coding_transition(signal_id, payload)
        case_id = self._case_input().case_id
        transition_id = transition.transition_id if transition else None
        materializes_validation = (
            self.result_artifact_mode and phase == ExecutionPhase.validation
        )
        # 受阻原因留在事件投影，可恢复 Session 和执行用量只进入 Evidence。
        evidence = ArtifactEvidenceRequest(
            evidence_id=f"{case_id}:WorkerBlocked:{signal_id}:evidence",
            artifact=(
                None if materializes_validation
                else transition.artifact if transition else self._evidence_target()
            ),
            evidence_type="WorkerBlocked",
            transition_id=None if materializes_validation else transition_id,
            payload={**payload, **(execution_evidence or {})},
        )
        await self._emit(
            self.state.worker_blocked_on(reason), signal_id, payload,
            artifact_transition=transition,
            artifact_evidence=evidence,
        )

    def _blocked_coding_transition(
        self, signal_id: str, payload: dict,
    ) -> ArtifactTransitionRequest | None:
        repo_changes = [
            value for value in payload.get("repoDiffs", [])
            if "diff --git" in str(value.get("diffPatch", ""))
        ]
        if self.failed_phase != ExecutionPhase.coding.value or not repo_changes or not self.planning_artifact:
            return None
        return self._propose_coding_transition(signal_id, payload, repo_changes)

    def _propose_coding_transition(
        self, signal_id: str, payload: dict, repo_changes: list[dict],
    ) -> ArtifactTransitionRequest:
        planning = self._require_artifact(self.planning_artifact, "PLANNING")
        supersedes = (
            self.coding_artifact
            if self.revision_mode == "incremental"
            and self.coding_artifact is not None
            and self.coding_artifact.parent_artifact_id == planning.artifact_id
            else None
        )
        outcome = dict(payload.get("executionOutcome") or {})
        content = {
            "summary": str(payload.get("summary", "")),
            "repoChanges": [
                {
                    "repo": str(value.get("repo", "")),
                    "diffPatch": str(value.get("diffPatch", "")),
                    "changedPaths": list(value.get("changedPaths", [])),
                    "summary": str(value.get("summary", "")),
                }
                for value in repo_changes
            ],
            "executionOutcome": {
                "status": str(outcome.get("status", "blocked")),
                "blockers": list(outcome.get("blockers", [])),
            },
            "baseRevisions": (
                dict(self.context_snapshot.git_base_revisions) if self.context_snapshot else {}
            ),
        }
        return ArtifactTransitionRequest(
            kind="ProposeCodingArtifact",
            transition_id=f"{self._case_input().case_id}:coding:{signal_id}:{self.revision}",
            parent=planning,
            supersedes=supersedes,
            expected_head=self._head("CODING"),
            content=content,
        )

    def _error_detail(self, error: BaseException) -> str:
        return error_detail(error)

    def _case_input(self) -> CaseInput:
        if self.case_input is None:
            raise RuntimeError("case input is not initialized")
        return self.case_input

    def _is_gitlab(self) -> bool:
        return self._case_input().release_mode == "gitlab"
