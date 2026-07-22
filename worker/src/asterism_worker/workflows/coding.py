import asyncio
from dataclasses import dataclass
from datetime import timedelta
from enum import StrEnum

from temporalio import workflow
from temporalio.common import RetryPolicy
from temporalio.exceptions import ActivityError, ApplicationError

from asterism_worker.contracts import (
    AgentConfigSnapshot,
    CodingAttemptResult,
    ContextSnapshot,
    ExecutionResult,
    LifecycleStatus,
    PLAN_BASE_CHANGED_ERROR,
)
from asterism_worker.workflows.coding_support import (
    attempt_stage_results,
    candidate_summary,
    combined_attempt_result,
    discard_candidate_checkpoint,
    workflow_previous_candidates,
)
from asterism_worker.workflows.workflow_support import has_application_error_type

CLAUDE_SDK_TEAM_ARCHITECTURE = "claude_sdk_team"

class ExecutionPhase(StrEnum):
    """可独立恢复的真实执行阶段。"""

    planning = "planning"
    coding = "coding"
    patch = "patch"
    validation = "validation"
    release = "release"


@dataclass(frozen=True, slots=True)
class PhaseRecovery:
    """阶段恢复策略；新增阶段只注册策略，不扩充分支链。"""

    runner: str
    restore_modification: bool = False
    checkpoints: tuple[str, ...] = ()


PHASE_RECOVERIES = {
    ExecutionPhase.planning: PhaseRecovery("_retry_planning_phase"),
    ExecutionPhase.coding: PhaseRecovery("_retry_coding_phase"),
    ExecutionPhase.patch: PhaseRecovery("_apply_patch", restore_modification=True),
    ExecutionPhase.validation: PhaseRecovery(
        "_run_validation", restore_modification=True, checkpoints=("patch_apply_approved",),
    ),
    ExecutionPhase.release: PhaseRecovery(
        "_release",
        restore_modification=True,
        checkpoints=("patch_apply_approved", "validation_passed"),
    ),
}


class CodingWorkflow:
    async def _interrupt_attempt_action(
        self, _action: str, _spec, signal_id: str, context: dict,
    ) -> bool:
        """把人工停止收敛为可恢复阻塞，不引入额外生命周期状态。"""

        if not self.coding_interrupt_requested:
            workflow.logger.warning("当前没有可停止的 Coding Attempt")
            return False
        self.coding_interrupt_requested = False
        await self._block_worker(
            signal_id,
            "attempt_interrupted",
            RuntimeError(str(context.get("note", "")).strip() or "负责人停止了当前 Coding Attempt"),
            {"interruptedBy": str(context.get("actor_id", ""))},
            phase=ExecutionPhase.coding,
        )
        return True

    async def _patch_rejected_action(
        self, _action: str, _spec, signal_id: str, context: dict,
    ) -> bool:
        return await self._request_revision(signal_id, context, "review", reject_patch=True)

    async def _request_revision(
        self, signal_id: str, context: dict, phase: str, reject_patch: bool = False,
    ) -> bool:
        """记录人工意见并在同一个 signal 内自动启动下一轮修订。"""

        limit = self._case_input().max_revisions
        if self.revision >= limit:
            if phase == "merge":
                self._prepare_merge_rework()
            await self._block_worker(
                signal_id,
                "revision_limit_reached",
                RuntimeError(f"已达到最大修订轮次 {limit}"),
                {"revision": self.revision, "maxRevisions": limit, "phase": phase, "note": context.get("note", "")},
            )
            return True
        if reject_patch:
            await self._emit(self.state.patch_apply_rejected(), signal_id, context)
        candidates = workflow_previous_candidates(self)
        next_revision = self.revision + 1
        self.revision_mode = "incremental" if candidates else "full"
        await self._emit(self.state.rework(), signal_id, {
            "retryScope": "revision",
            "revision": next_revision,
            "revisionMode": self.revision_mode,
        })
        if phase == "merge":
            self._prepare_merge_rework()
        self.revision = next_revision
        await self._emit("RevisionRequested", signal_id, {
            "note": str(context.get("note", "")).strip(),
            "revision": self.revision,
            "requestedBy": str(context.get("actor_id", "")),
            "phase": phase,
            "revisionMode": self.revision_mode,
            "diffSummary": candidate_summary(candidates),
        })
        self.failed_phase = ""
        await self._start_supervised_modification(signal_id, reuse_context=True)
        return True

    async def _recovery_action(self, action: str, spec, signal_id: str, context: dict) -> bool:
        previous_status = self.state.status.value
        if previous_status == "waiting_merge":
            return await self._request_revision(signal_id, context, "merge")
        phase = self._failed_execution_phase() if spec.retry_failed_phase else ExecutionPhase.planning
        if phase is None:
            workflow.logger.warning("缺少可恢复阶段", extra={"action": action})
            return False
        if spec.refresh_configuration and phase not in {ExecutionPhase.planning, ExecutionPhase.coding}:
            workflow.logger.warning("当前阶段不消费 Agent 配置", extra={"phase": phase.value})
            return False
        configuration_refreshed = False
        if spec.refresh_configuration:
            snapshot = self._agent_config_snapshot(context)
            if snapshot is None:
                return False
            # 刷新配置只替换执行快照，保留候选代码和上下文。
            self.case_input = self._case_input().model_copy(update={"agent_config_snapshot": snapshot})
            configuration_refreshed = True
        context.pop("resume_failed_stage", None)
        event = self.state.rework()
        await self._emit(event, signal_id, {
            "configurationRefreshed": configuration_refreshed,
            "retryPhase": phase.value,
            "retryScope": "phase" if spec.retry_failed_phase else "full",
        })
        if event is None:
            return False
        self.failed_phase = ""
        if not spec.retry_failed_phase:
            self.revision = 0
            self.revision_mode = "full"
            discard_candidate_checkpoint(self)
            await self._propose_coding_plan(
                signal_id,
                reuse_context=True,
                refresh_workspace=True,
                new_session=True,
            )
            return True
        return await self._retry_phase(signal_id, phase)

    async def _retry_phase(self, signal_id: str, phase: ExecutionPhase) -> bool:
        recovery = PHASE_RECOVERIES[phase]
        workflow.logger.info("重试失败阶段", extra={"phase": phase.value, "runner": recovery.runner})
        if recovery.restore_modification and not await self._restore_modification_checkpoint(signal_id, phase):
            return False
        for checkpoint in recovery.checkpoints:
            event_type = getattr(self.state, checkpoint)()
            if event_type is None:
                return False
            await self._emit(event_type, signal_id, {
                "reused": True,
                "recoveryPhase": phase.value,
            }, suffix=f"checkpoint:{checkpoint}")
        await getattr(self, recovery.runner)(signal_id)
        return True

    async def _restore_modification_checkpoint(self, signal_id: str, phase: ExecutionPhase) -> bool:
        if self.context_snapshot is None or not self.state.diff_patch.strip():
            await self._block_worker(
                signal_id, "recovery_artifact_missing", RuntimeError("缺少候选代码或上下文快照"),
            )
            return False
        result = ExecutionResult(
            summary=f"复用候选代码并恢复 {phase.value} 阶段",
            diff_patch=self.state.diff_patch,
            execution_provider="workflow-checkpoint",
        )
        await self._finish_modification(signal_id, result, self.context_snapshot)
        return self.state.status == LifecycleStatus.modification_completed

    async def _retry_coding_phase(self, signal_id: str) -> None:
        if self.coding_plan is None:
            await self._propose_coding_plan(signal_id, reuse_context=True)
            return
        await self._start_supervised_modification(signal_id, reuse_context=True)

    def _failed_execution_phase(self) -> ExecutionPhase | None:
        try:
            return ExecutionPhase(self.failed_phase)
        except ValueError:
            return None

    def _agent_config_snapshot(self, context: dict) -> AgentConfigSnapshot | None:
        raw_snapshot = context.pop("agent_config_snapshot", None)
        try:
            return AgentConfigSnapshot.model_validate(raw_snapshot)
        except Exception as error:
            workflow.logger.warning("最新 Agent 配置快照无效", extra={"type": type(error).__name__})
            return None

    async def _start_supervised_modification(
        self,
        signal_id: str,
        reuse_context: bool = False,
        reuse_candidate: bool = True,
    ) -> None:
        """启动 Claude SDK Supervisor，仓库分工由 SDK 会话内部完成。"""

        case_input = self._case_input()
        if self.state.status.value != "activated":
            workflow.logger.warning("非法 start_modification，已忽略", extra={"status": self.state.status.value})
            return
        if self.coding_plan is None and not self.revision:
            workflow.logger.warning("Coding Plan 未批准，拒绝启动仓库 Agent")
            return
        snapshot = self.context_snapshot if reuse_context else None
        if snapshot is None:
            try:
                result_payload = await workflow.execute_activity(
                    "fetch_context",
                    {"system_id": case_input.system_id, "work_item_id": case_input.work_item_id},
                    start_to_close_timeout=timedelta(seconds=20),
                    retry_policy=RetryPolicy(maximum_attempts=3),
                )
            except (ActivityError, ApplicationError) as error:
                await self._block_worker(signal_id, "context_fetch_failed", error, phase=ExecutionPhase.coding)
                return
            snapshot = ContextSnapshot.model_validate(result_payload)
        self.context_snapshot = snapshot
        previous_candidate = workflow_previous_candidates(self) if reuse_candidate else []
        repos = case_input.effective_repos()
        await self._emit("CodingAttemptStarted", signal_id, {
            "architecture": CLAUDE_SDK_TEAM_ARCHITECTURE,
            "supervisor": {"role": "developer", "engine": "claude_sdk_team"},
            "repositories": [repo.repo_id for repo in repos],
            "contextManifestId": snapshot.manifest_id,
            "candidateReused": bool(previous_candidate),
            "revision": self.revision,
            "revisionMode": self.revision_mode,
        })
        request = {
            "case_id": case_input.case_id,
            "work_item_id": case_input.work_item_id,
            "system_id": case_input.system_id,
            "repos": [repo.model_dump() for repo in repos],
            "goal": case_input.prd.goal,
            "acceptance_criteria": case_input.prd.acceptance_criteria,
            "feedback": self.rework_feedback,
            "memories": snapshot.approved_memories,
            "context_manifest_id": snapshot.manifest_id,
            "previous_candidate": previous_candidate,
        }
        request["resume_session_id"] = self.coding_session_id
        if self.coding_plan is not None:
            request["approved_plan"] = self.coding_plan.model_dump()
        if self.revision:
            request["revision_context"] = {
                "revision": self.revision,
                "revision_mode": self.revision_mode,
                "feedback": self.rework_feedback,
                "previous_diff_summary": candidate_summary(previous_candidate),
            }
        if case_input.agent_config_snapshot is not None:
            request["agent_config_snapshot"] = case_input.agent_config_snapshot.model_dump()
        # 业务上不再以 15 分钟截断；24 小时只是 Activity 失控保护上限。
        start_to_close_timeout = timedelta(hours=24)
        heartbeat_timeout = timedelta(minutes=10)
        retry_policy = RetryPolicy(maximum_attempts=2)
        try:
            handle = workflow.start_activity(
                "run_coding_attempt",
                request,
                start_to_close_timeout=start_to_close_timeout,
                heartbeat_timeout=heartbeat_timeout,
                retry_policy=retry_policy,
                cancellation_type=workflow.ActivityCancellationType.WAIT_CANCELLATION_COMPLETED,
            )
            self.active_coding_activity = handle
            payload = await handle
            attempt = CodingAttemptResult.model_validate(payload)
        except asyncio.CancelledError:
            if self.coding_interrupt_requested:
                workflow.logger.info("Coding Attempt 已按人工请求停止")
                return
            raise
        except (ActivityError, ApplicationError) as error:
            if self.coding_interrupt_requested:
                workflow.logger.info("Coding Attempt 已停止", extra={"type": type(error).__name__})
                return
            if has_application_error_type(error, PLAN_BASE_CHANGED_ERROR):
                await self._emit("CodingPlanInvalidated", signal_id, {
                    "reason": "repository_base_changed",
                    "planRevision": self.coding_plan.revision if self.coding_plan else 0,
                    "baseRevisions": self.coding_plan.base_revisions if self.coding_plan else {},
                })
                self.rework_feedback = "系统检测到代码基线已更新，请在最新仓库上复核并重生成计划"
                await self._propose_coding_plan(
                    signal_id, reuse_context=True, refresh_workspace=True, new_session=True,
                )
                return
            await self._block_worker(signal_id, "coding_attempt_failed", error, {
                "failed_stage": {"index": 0, "role": "developer"},
                "executionArchitecture": CLAUDE_SDK_TEAM_ARCHITECTURE,
            }, phase=ExecutionPhase.coding)
            return
        finally:
            self.active_coding_activity = None
        if attempt.revision_mode:
            self.revision_mode = attempt.revision_mode
        if attempt.session_id:
            self.coding_session_id = attempt.session_id
        changes = [change for change in attempt.repo_changes if change.diff_patch.strip()]
        self.context_snapshot = snapshot
        # blocked 也保留局部 Diff，下一次 Coding retry 可继续复用而不是从零开始。
        self.completed_stage_results = attempt_stage_results(attempt, changes)
        if attempt.outcome is None:
            await self._block_worker(
                signal_id, "coding_attempt_blocked", RuntimeError("Coding Attempt 缺少结构化 outcome"),
                phase=ExecutionPhase.coding,
            )
            return
        if attempt.outcome.status == "blocked":
            blockers = attempt.outcome.blockers or ["Root Supervisor 报告执行受阻"]
            await self._block_worker(
                signal_id,
                "coding_attempt_blocked",
                RuntimeError("；".join(blockers)),
                {
                    "executionArchitecture": CLAUDE_SDK_TEAM_ARCHITECTURE,
                    "executionOutcome": attempt.outcome.model_dump(),
                    "partialChanges": [
                        {"repo": change.repo, "changedPaths": change.changed_paths}
                        for change in changes
                    ],
                },
                phase=ExecutionPhase.coding,
            )
            return
        if not changes:
            await self._block_worker(
                signal_id,
                "coding_attempt_failed",
                RuntimeError("Coding Attempt 未生成代码变更"),
                {
                    "failed_stage": {"index": 0, "role": "developer"},
                    "executionArchitecture": CLAUDE_SDK_TEAM_ARCHITECTURE,
                },
                phase=ExecutionPhase.coding,
            )
            return

        self.local_releases = []
        self.gitlab_releases = []
        self.merge_requests = []
        self.validation_commands = []
        self.resume_phase = ""
        changed_by_repo = {change.repo: change.changed_paths for change in changes}
        for index, run in enumerate(attempt.subagent_runs, start=1):
            await self._emit("AgentStageCompleted", signal_id, {
                "stageIndex": index,
                "role": run.agent_type,
                "repo": run.repo,
                "engine": "claude_sdk_team",
                "summary": f"{run.agent_type} {run.status}",
                "changedPaths": changed_by_repo.get(run.repo, []),
                "tokenUsage": {},
                "agentId": run.agent_id,
            }, suffix=f"subagent:{index}:{run.agent_id}")
        combined = combined_attempt_result(attempt, changes)
        await self._finish_modification(
            signal_id, combined, snapshot, attempt.outcome,
        )

    async def _finish_modification(
        self, signal_id: str, result: ExecutionResult, snapshot: ContextSnapshot, outcome=None,
    ) -> None:
        self.failed_phase = ""
        case_input = self._case_input()
        payload = {
            "summary": result.summary,
            "diffPatch": result.diff_patch,
            "goal": case_input.prd.goal,
            "contextManifestId": snapshot.manifest_id,
            "executionProvider": result.execution_provider,
            "turns": result.turns,
            "tokenUsage": result.token_usage,
            "revision": self.revision,
            "revisionMode": self.revision_mode,
        }
        if result.session_id:
            payload["sessionId"] = result.session_id
        if result.subagent_runs:
            payload["subagentRuns"] = [run.model_dump() for run in result.subagent_runs]
        if outcome is not None:
            payload["executionOutcome"] = outcome.model_dump()
        payload["repoDiffs"] = [
            {"repo": repo.repo_id, "diffPatch": diff}
            for repo, diff in self._repo_diffs()
        ]
        await self._emit(self.state.modification_finished(result), signal_id, payload)
