import asyncio
from dataclasses import dataclass
from datetime import timedelta
from enum import StrEnum

from temporalio import workflow
from temporalio.common import RetryPolicy
from temporalio.exceptions import ActivityError, ApplicationError

from asterism_worker.contracts import (
    AgentConfigSnapshot,
    ArtifactEvidenceRequest,
    ArtifactRef,
    ArtifactTransitionRequest,
    CodingAttemptResult,
    CodingPlanDraft,
    ContextSnapshot,
    ExecutionResult,
    LifecycleStatus,
    PLAN_BASE_CHANGED_ERROR,
)
from asterism_worker.workflows.coding_support import (
    attempt_stage_results,
    candidate_summary,
    combine_patch_artifacts,
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


PHASE_RECOVERIES = {
    ExecutionPhase.planning: PhaseRecovery("_retry_planning_phase"),
    ExecutionPhase.coding: PhaseRecovery("_retry_coding_phase"),
    ExecutionPhase.patch: PhaseRecovery("_apply_patch", restore_modification=True),
    ExecutionPhase.validation: PhaseRecovery("_run_validation", restore_modification=True),
    ExecutionPhase.release: PhaseRecovery("_release", restore_modification=True),
}

RECOVERY_CHECKPOINTS = {
    ExecutionPhase.patch: LifecycleStatus.modification_completed,
    ExecutionPhase.validation: LifecycleStatus.patch_applied,
    ExecutionPhase.release: LifecycleStatus.validation_passed,
}

CHECKPOINT_PROJECTION_PATCH = "artifact-checkpoint-projection-v1"
CHECKPOINT_PROJECTION_EVENTS = {
    LifecycleStatus.modification_completed: "ModificationCheckpointRestored",
    LifecycleStatus.patch_applied: "PatchCheckpointRestored",
    LifecycleStatus.validation_passed: "ValidationCheckpointRestored",
}
CHECKPOINT_PROJECTION_ORDER = tuple(CHECKPOINT_PROJECTION_EVENTS)


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
        # 修订关系由精确 CodingArtifact 决定，Workflow 候选只用于即时展示摘要。
        artifact_candidate = (
            self.coding_artifact is not None
            and self.planning_artifact is not None
            and self.coding_artifact.parent_artifact_id == self.planning_artifact.artifact_id
        )
        self.revision_mode = "incremental" if artifact_candidate else "full"
        await self._emit(self.state.rework(), signal_id, {
            "retryScope": "revision",
            "revision": next_revision,
            "revisionMode": self.revision_mode,
        })
        if phase == "merge":
            self._prepare_merge_rework()
        self.revision = next_revision
        transition = None
        if phase == "merge":
            coding = self._require_artifact(self.coding_artifact, "CODING")
            transition = ArtifactTransitionRequest(
                kind="SupersedeArtifact",
                transition_id=f"{self._case_input().case_id}:supersede-coding:{signal_id}",
                artifact=coding,
                expected_head=self._head("CODING"),
                note=str(context.get("note", "")).strip() or "等待合并阶段发起修订",
            )
        await self._emit(
            "RevisionRequested",
            signal_id,
            {
                "note": str(context.get("note", "")).strip(),
                "revision": self.revision,
                "requestedBy": str(context.get("actor_id", "")),
                "phase": phase,
                "revisionMode": self.revision_mode,
                "diffSummary": candidate_summary(candidates),
            },
            artifact_transition=transition,
        )
        self.failed_phase = ""
        await self._start_supervised_modification(signal_id, reuse_context=True)
        return True

    async def _recovery_action(self, action: str, spec, signal_id: str, context: dict) -> bool:
        previous_status = self.state.status.value
        if previous_status == "waiting_merge":
            return await self._request_revision(signal_id, context, "merge")
        phase = self._failed_execution_phase()
        if not spec.retry_failed_phase and phase is None:
            phase = (
                ExecutionPhase.coding
                if self.planning_artifact is not None
                else ExecutionPhase.planning
            )
        if phase is None and spec.retry_failed_phase:
            # 控制面会随恢复信号携带投影确认的阶段，支持 Runner 重启和历史阻塞记录。
            phase = self._execution_phase(context.get("retry_phase"))
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
        previous_planning = None
        if spec.refresh_requirement_context:
            manifest_id = str(context.get("requirement_manifest_id", "")).strip()
            try:
                product_artifact = ArtifactRef.model_validate(context.get("product_artifact"))
            except Exception:
                workflow.logger.warning("缺少刷新后的 ProductArtifact 或 Requirement Manifest")
                return False
            if (
                not manifest_id
                or product_artifact.artifact_type != "PRODUCT"
                or product_artifact.status != "APPROVED"
                or product_artifact.parent_artifact_id is not None
            ):
                workflow.logger.warning("刷新后的 ProductArtifactRef 无效")
                return False
            case_input = self._case_input()
            refreshed_prd = case_input.prd.model_copy(update={
                "requirement_manifest_id": manifest_id,
                "product_artifact": product_artifact,
            })
            previous_planning = self.planning_artifact
            if previous_planning is not None and previous_planning.status == "APPROVED":
                # Product Head 更新会在同一事务中使下游 Approved Head 失效。
                previous_planning = previous_planning.model_copy(update={"status": "SUPERSEDED"})
            self.case_input = case_input.model_copy(update={"prd": refreshed_prd})
            self.product_artifact = product_artifact
            self.planning_artifact = None
            self.coding_artifact = None
            self.effective_heads = {"PRODUCT": product_artifact}
            self.context_snapshot = None
        context.pop("resume_failed_stage", None)
        event = self.state.rework()
        await self._emit(event, signal_id, {
            "configurationRefreshed": configuration_refreshed,
            "retryPhase": phase.value,
            "retryScope": "context" if spec.refresh_requirement_context else "phase" if spec.retry_failed_phase else "full",
            **({"note": self.rework_feedback} if self.rework_feedback else {}),
            **({"requirementManifestId": self._case_input().prd.requirement_manifest_id}
               if spec.refresh_requirement_context else {}),
        })
        if event is None:
            return False
        self.failed_phase = ""
        if spec.refresh_requirement_context:
            self.coding_session_id = ""
            discard_candidate_checkpoint(self)
            await self._propose_coding_plan(
                signal_id, new_session=True, previous_artifact=previous_planning,
            )
            return True
        if not spec.retry_failed_phase:
            self.revision = 0
            self.revision_mode = "full"
            self.coding_session_id = ""
            discard_candidate_checkpoint(self)
            if phase == ExecutionPhase.planning or self.planning_artifact is None:
                await self._propose_coding_plan(
                    signal_id,
                    refresh_workspace=True,
                    new_session=True,
                    previous_artifact=self.planning_artifact,
                )
            else:
                await self._start_supervised_modification(
                    signal_id, reuse_context=False, reuse_candidate=False,
                )
            return True
        return await self._retry_phase(signal_id, phase)

    async def _retry_phase(self, signal_id: str, phase: ExecutionPhase) -> bool:
        recovery = PHASE_RECOVERIES[phase]
        workflow.logger.info("重试失败阶段", extra={"phase": phase.value, "runner": recovery.runner})
        if recovery.restore_modification and not await self._restore_modification_checkpoint(signal_id, phase):
            return False
        await getattr(self, recovery.runner)(signal_id)
        return True

    async def _restore_modification_checkpoint(self, signal_id: str, phase: ExecutionPhase) -> bool:
        result = await self._load_coding_artifact_candidate(signal_id, phase)
        if result is None:
            return False
        checkpoint = RECOVERY_CHECKPOINTS[phase]
        return await self._restore_lifecycle_checkpoint(
            signal_id, phase, checkpoint, result.diff_patch,
        )

    async def _restore_lifecycle_checkpoint(
        self,
        signal_id: str,
        phase: ExecutionPhase,
        checkpoint: LifecycleStatus,
        diff_patch: str,
    ) -> bool:
        """恢复 Temporal 状态，并用独立事件把控制面投影推进到同一检查点。"""

        if not self.state.restore_checkpoint(checkpoint, diff_patch):
            return False
        # 每个 signal 独立选版本：旧恢复记录重放不追加命令，部署后的下一次恢复仍能进入新路径。
        if not workflow.patched(f"{CHECKPOINT_PROJECTION_PATCH}:{signal_id}"):
            return True
        target_index = CHECKPOINT_PROJECTION_ORDER.index(checkpoint)
        for restored in CHECKPOINT_PROJECTION_ORDER[:target_index + 1]:
            await self._emit(
                CHECKPOINT_PROJECTION_EVENTS[restored],
                signal_id,
                {
                    "checkpoint": restored.value,
                    "recoveryPhase": phase.value,
                    "recoveryTarget": checkpoint.value,
                },
                suffix=f"checkpoint:{restored.value}",
            )
        return True

    async def _restore_selected_coding_candidate(self, signal_id: str) -> bool:
        """代码确认前以当前 CodingArtifact 覆盖切换前的 Temporal 候选缓存。"""

        result = await self._load_coding_artifact_candidate(signal_id, ExecutionPhase.patch)
        if result is None:
            return False
        self.state.diff_patch = result.diff_patch
        workflow.logger.info(
            "已从选中 CodingArtifact 恢复待确认代码",
            extra={"artifact_id": self.coding_artifact.artifact_id if self.coding_artifact else ""},
        )
        return True

    async def _load_coding_artifact_candidate(
        self, signal_id: str, phase: ExecutionPhase,
    ) -> ExecutionResult | None:
        if self.coding_artifact is None:
            await self._block_worker(
                signal_id, "recovery_artifact_missing", RuntimeError("缺少候选代码或上下文快照"),
            )
            return None
        try:
            # 阶段恢复必须读取最后提交的精确 CodingArtifact，不能以 Temporal 候选缓存代替。
            snapshot = await self._fetch_artifact_context("coding", self.coding_artifact)
        except (ActivityError, ApplicationError) as error:
            await self._block_worker(
                signal_id, "recovery_artifact_missing", error, phase=phase,
            )
            return None
        if snapshot.stale_references:
            await self._block_worker(
                signal_id,
                "context_stale",
                RuntimeError("需求上下文已变化: " + ",".join(snapshot.stale_references)),
                {"staleReferences": snapshot.stale_references},
                phase=phase,
            )
            return None
        self.context_snapshot = snapshot
        candidates = self._artifact_candidates(snapshot)
        if not candidates:
            await self._block_worker(
                signal_id, "recovery_artifact_missing", RuntimeError("CodingArtifact 缺少正式代码结果"),
                phase=phase,
            )
            return None
        self.completed_stage_results = [
            ExecutionResult(
                summary=str(candidate.get("summary", "")) or "从 CodingArtifact 恢复",
                diff_patch=str(candidate.get("diff_patch", "")),
                execution_provider="artifact-checkpoint",
                repo=str(candidate.get("repo", "")),
                changed_paths=list(candidate.get("changed_paths", [])),
            )
            for candidate in candidates
        ]
        result = ExecutionResult(
            summary=f"复用候选代码并恢复 {phase.value} 阶段",
            diff_patch=combine_patch_artifacts([
                str(candidate.get("diff_patch", "")) for candidate in candidates
            ]),
            execution_provider="artifact-checkpoint",
            changed_paths=sorted({
                path for candidate in candidates
                for path in candidate.get("changed_paths", [])
            }),
        )
        return result

    async def _retry_coding_phase(self, signal_id: str) -> None:
        if not self.planning_artifact:
            await self._propose_coding_plan(signal_id, reuse_context=True)
            return
        await self._start_supervised_modification(signal_id, reuse_context=True)

    def _failed_execution_phase(self) -> ExecutionPhase | None:
        return self._execution_phase(self.failed_phase)

    def _execution_phase(self, value: object) -> ExecutionPhase | None:
        try:
            return ExecutionPhase(str(value or ""))
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
        if not self.planning_artifact:
            workflow.logger.warning("缺少 Approved PlanningArtifact，拒绝启动仓库 Agent")
            return
        previous_artifact = self.coding_artifact
        snapshot = (
            self.context_snapshot
            if reuse_context
            and self.context_snapshot is not None
            and self.context_snapshot.planning_artifact == self.planning_artifact
            and self.context_snapshot.planning_content
            and self.context_snapshot.previous_artifact == previous_artifact
            and not self.rework_feedback
            else None
        )
        if snapshot is None:
            try:
                snapshot = await self._fetch_artifact_context(
                    "coding", previous_artifact,
                )
            except (ActivityError, ApplicationError) as error:
                await self._block_worker(signal_id, "context_fetch_failed", error, phase=ExecutionPhase.coding)
                return
        if snapshot.stale_references:
            await self._block_worker(
                signal_id, "context_stale",
                RuntimeError("需求上下文已变化: " + ",".join(snapshot.stale_references)),
                {"staleReferences": snapshot.stale_references}, phase=ExecutionPhase.coding,
            )
            return
        self.context_snapshot = snapshot
        self.product_artifact = snapshot.product_artifact
        self.planning_artifact = snapshot.planning_artifact
        previous_candidate = workflow_previous_candidates(self) if reuse_candidate else []
        if reuse_candidate and not previous_candidate:
            previous_candidate = self._artifact_candidates(snapshot)
        repos = case_input.effective_repos()
        self.coding_attempt_running = True
        await self._emit("CodingAttemptStarted", signal_id, {
            "architecture": CLAUDE_SDK_TEAM_ARCHITECTURE,
            "supervisor": {"role": "developer", "engine": "claude_sdk_team"},
            "repositories": [repo.repo_id for repo in repos],
            "requirementManifestId": snapshot.requirement_manifest_id,
            "executionContextBundleId": snapshot.execution_bundle_id,
            "candidateReused": bool(previous_candidate),
            "revision": self.revision,
            "revisionMode": self.revision_mode,
            "productArtifactId": snapshot.product_artifact.artifact_id,
            "planningArtifactId": snapshot.planning_artifact.artifact_id,
        })
        # CodingAttemptStarted 与 Activity 启动之间也可能收到停止信号；此时不再启动新进程。
        if self.coding_interrupt_requested:
            self.coding_attempt_running = False
            return
        feedback = self._artifact_feedback(snapshot)
        # 本轮意见已经从持久化 Snapshot 读取，Temporal 字段不再继续充当事实来源。
        self.rework_feedback = ""
        request = {
            "case_id": case_input.case_id,
            "work_item_id": case_input.work_item_id,
            "system_id": case_input.system_id,
            "repos": [repo.model_dump() for repo in repos],
            "goal": str(snapshot.product_content.get("goal", "")),
            "acceptance_criteria": list(snapshot.product_content.get("acceptanceCriteria", [])),
            "feedback": feedback,
            "requirement_context": snapshot.requirement_items,
            "execution_context": snapshot.execution_items,
            "requirement_manifest_id": snapshot.requirement_manifest_id,
            "execution_bundle_id": snapshot.execution_bundle_id,
            "previous_candidate": previous_candidate,
        }
        request["resume_session_id"] = self.coding_session_id
        request["approved_plan"] = self._artifact_plan(snapshot).model_dump()
        if self.revision:
            request["revision_context"] = {
                "revision": self.revision,
                "revision_mode": self.revision_mode,
                "feedback": feedback,
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
                # 人工停止必须先让 Workflow 收敛到可恢复阻塞；Activity 会在下一次 heartbeat 收到取消。
                cancellation_type=workflow.ActivityCancellationType.TRY_CANCEL,
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
                planning = self._require_artifact(self.planning_artifact, "PLANNING")
                await self._emit(
                    "CodingPlanInvalidated",
                    signal_id,
                    {
                        "reason": "repository_base_changed",
                        "planRevision": planning.version,
                        "baseRevisions": snapshot.planning_content.get("baseRevisions", {}),
                    },
                    artifact_transition=ArtifactTransitionRequest(
                        kind="SupersedeArtifact",
                        transition_id=f"{case_input.case_id}:invalidate-planning:{signal_id}",
                        artifact=planning,
                        expected_head=self._head("PLANNING"),
                        note="系统检测到代码基线已更新，请在最新仓库上复核并重生成计划",
                    ),
                )
                self.coding_artifact = None
                await self._propose_coding_plan(
                    signal_id,
                    refresh_workspace=True,
                    new_session=True,
                    previous_artifact=self.planning_artifact,
                )
                return
            await self._block_worker(signal_id, "coding_attempt_failed", error, {
                "failed_stage": {"index": 0, "role": "developer"},
                "executionArchitecture": CLAUDE_SDK_TEAM_ARCHITECTURE,
            }, phase=ExecutionPhase.coding, execution_evidence={
                "executionProvider": CLAUDE_SDK_TEAM_ARCHITECTURE,
                "sessionId": self.coding_session_id,
            })
            return
        finally:
            self.active_coding_activity = None
            self.coding_attempt_running = False
        if attempt.revision_mode:
            self.revision_mode = attempt.revision_mode
        if attempt.session_id:
            self.coding_session_id = attempt.session_id
        # 只有正式 Git Diff 才能进入 CodingArtifact；普通文本结果按无代码产物处理。
        changes = [
            change for change in attempt.repo_changes
            if "diff --git" in change.diff_patch
        ]
        self.context_snapshot = snapshot
        # blocked 也保留局部 Diff，下一次 Coding retry 可继续复用而不是从零开始。
        self.completed_stage_results = attempt_stage_results(attempt, changes)
        if attempt.outcome.status == "blocked":
            blockers = attempt.outcome.blockers or ["系统未能确认 Coding Attempt 完成"]
            await self._block_worker(
                signal_id,
                "coding_attempt_blocked",
                RuntimeError("；".join(blockers)),
                {
                    "executionArchitecture": CLAUDE_SDK_TEAM_ARCHITECTURE,
                    "executionOutcome": attempt.outcome.model_dump(),
                    "summary": attempt.summary,
                    "partialChanges": [
                        {"repo": change.repo, "changedPaths": change.changed_paths}
                        for change in changes
                    ],
                    "repoDiffs": [
                        {
                            "repo": change.repo,
                            "diffPatch": change.diff_patch,
                            "changedPaths": change.changed_paths,
                            "summary": change.summary,
                        }
                        for change in changes
                    ],
                    "changedPaths": sorted({
                        path for change in changes for path in change.changed_paths
                    }),
                    "parentArtifactId": snapshot.planning_artifact.artifact_id,
                    **({
                        "supersedesArtifactId": self.coding_artifact.artifact_id,
                    } if (
                        self.revision_mode == "incremental"
                        and self.coding_artifact is not None
                        and self.coding_artifact.parent_artifact_id
                        == snapshot.planning_artifact.artifact_id
                    ) else {}),
                },
                phase=ExecutionPhase.coding,
                execution_evidence=self._coding_execution_evidence(attempt),
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
                execution_evidence=self._coding_execution_evidence(attempt),
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
                "summary": f"{run.agent_type} {run.status}",
                "changedPaths": changed_by_repo.get(run.repo, []),
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
            "goal": str(snapshot.product_content.get("goal", "")),
            "requirementManifestId": snapshot.requirement_manifest_id,
            "executionContextBundleId": snapshot.execution_bundle_id,
            "revision": self.revision,
            "revisionMode": self.revision_mode,
            "changedPaths": result.changed_paths,
            "productArtifactId": snapshot.product_artifact.artifact_id,
            "planningArtifactId": snapshot.planning_artifact.artifact_id,
            "parentArtifactId": snapshot.planning_artifact.artifact_id,
            **({
                "supersedesArtifactId": self.coding_artifact.artifact_id,
            } if (
                self.revision_mode == "incremental"
                and self.coding_artifact is not None
                and self.coding_artifact.parent_artifact_id
                == snapshot.planning_artifact.artifact_id
            ) else {}),
        }
        if outcome is not None:
            payload["executionOutcome"] = outcome.model_dump()
        summaries = {
            value.repo: value.summary for value in self.completed_stage_results
        }
        payload["repoDiffs"] = [
            {
                "repo": repo.repo_id,
                "diffPatch": diff,
                "changedPaths": self._diff_paths(diff),
                "summary": summaries.get(repo.repo_id, result.summary),
            }
            for repo, diff in self._repo_diffs()
        ]
        event_type = self.state.modification_finished(result)
        transition = self._propose_coding_transition(
            signal_id, payload, payload["repoDiffs"],
        )
        await self._emit(
            event_type,
            signal_id,
            payload,
            artifact_transition=transition,
            # Session、Token 和 Subagent 运行信息只写入不可变执行 Evidence。
            artifact_evidence=ArtifactEvidenceRequest(
                evidence_id=f"{transition.transition_id}:execution",
                evidence_type="CodingExecution",
                transition_id=transition.transition_id,
                payload=self._coding_execution_evidence(result),
            ),
        )

    def _coding_execution_evidence(
        self, result: ExecutionResult | CodingAttemptResult,
    ) -> dict:
        """将执行遥测集中到 Evidence，避免污染 Domain Event 和 Coding Content。"""

        return {
            "executionProvider": result.execution_provider,
            "sessionId": result.session_id,
            "turns": result.turns,
            "tokenUsage": result.token_usage,
            "subagentRuns": [run.model_dump() for run in result.subagent_runs],
        }

    def _artifact_plan(self, snapshot: ContextSnapshot) -> CodingPlanDraft:
        """Coding 只从 Approved PlanningArtifact 重建技术方案，Session 仅用于加速。"""

        content = snapshot.planning_content
        planning = self._require_artifact(snapshot.planning_artifact, "PLANNING")
        return CodingPlanDraft(
            plan_markdown=str(content.get("planMarkdown", "")),
            revision=planning.version,
            base_revisions=dict(content.get("baseRevisions", {})),
            acceptance_criteria_refs=list(content.get("acceptanceCriteriaRefs", [])),
            repositories=list(content.get("repositories", [])),
            evidence_refs=list(content.get("evidenceRefs", [])),
            risks=list(content.get("risks", [])),
            open_questions=list(content.get("openQuestions", [])),
        )

    def _artifact_candidates(self, snapshot: ContextSnapshot) -> list[dict]:
        """Session 或 Workflow 候选丢失时，从上一 CodingArtifact 恢复正式代码结果。"""

        return [
            {
                "repo": str(change.get("repo", "")),
                "diff_patch": str(change.get("diffPatch", "")),
                "changed_paths": list(change.get("changedPaths", [])),
                "summary": str(change.get("summary", "")),
            }
            for change in snapshot.previous_content.get("repoChanges", [])
            if str(change.get("diffPatch", "")).strip()
        ]

    def _artifact_feedback(self, snapshot: ContextSnapshot) -> str:
        """Coding 修订意见只读取 Artifact Snapshot 中已持久化的 Transition/Evidence。"""

        return "\n".join(note for note in snapshot.feedback_notes if note.strip())
