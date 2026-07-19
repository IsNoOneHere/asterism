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
)

CLAUDE_SDK_TEAM_ARCHITECTURE = "claude_sdk_team"


class ExecutionPhase(StrEnum):
    """可独立恢复的真实执行阶段。"""

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
        candidates = self._previous_candidate()
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
            "diffSummary": self._candidate_summary(candidates),
        })
        self.failed_phase = ""
        await self._start_supervised_modification(signal_id, reuse_context=True)
        return True

    async def _recovery_action(self, action: str, spec, signal_id: str, context: dict) -> bool:
        previous_status = self.state.status.value
        if previous_status == "waiting_merge":
            return await self._request_revision(signal_id, context, "merge")
        phase = self._failed_execution_phase() if spec.retry_failed_phase else ExecutionPhase.coding
        if phase is None:
            workflow.logger.warning("缺少可恢复阶段", extra={"action": action})
            return False
        if spec.refresh_configuration and phase != ExecutionPhase.coding:
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
            await self._start_supervised_modification(signal_id, reuse_candidate=False)
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

    async def _start_modification(self, signal_id: str) -> None:
        await self._start_supervised_modification(signal_id)

    async def _start_supervised_modification(
        self, signal_id: str, reuse_context: bool = False, reuse_candidate: bool = True,
    ) -> None:
        """启动 Claude SDK Supervisor，仓库分工由 SDK 会话内部完成。"""

        case_input = self._case_input()
        if self.state.status.value != "activated":
            workflow.logger.warning("非法 start_modification，已忽略", extra={"status": self.state.status.value})
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
        previous_candidate = self._previous_candidate() if reuse_candidate else []
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
        if self.revision:
            request["revision_context"] = {
                "revision": self.revision,
                "revision_mode": self.revision_mode,
                "feedback": self.rework_feedback,
                "previous_diff_summary": self._candidate_summary(previous_candidate),
            }
        if case_input.agent_config_snapshot is not None:
            request["agent_config_snapshot"] = case_input.agent_config_snapshot.model_dump()
        try:
            payload = await workflow.execute_activity(
                "run_coding_attempt",
                request,
                start_to_close_timeout=timedelta(seconds=self._coding_timeout()),
                heartbeat_timeout=timedelta(minutes=2),
                retry_policy=RetryPolicy(maximum_attempts=1),
            )
            attempt = CodingAttemptResult.model_validate(payload)
        except (ActivityError, ApplicationError) as error:
            await self._block_worker(signal_id, "coding_attempt_failed", error, {
                "failed_stage": {"index": 0, "role": "developer"},
                "executionArchitecture": CLAUDE_SDK_TEAM_ARCHITECTURE,
            }, phase=ExecutionPhase.coding)
            return
        if attempt.revision_mode:
            self.revision_mode = attempt.revision_mode
        changes = [change for change in attempt.repo_changes if change.diff_patch.strip()]
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

        self.context_snapshot = snapshot
        self.completed_stage_results = [
            ExecutionResult(
                summary=change.summary or attempt.summary,
                diff_patch=change.diff_patch,
                execution_provider=attempt.execution_provider,
                engine="claude_sdk_team",
                repo=change.repo,
                changed_paths=change.changed_paths,
                token_usage={},
            )
            for change in changes
        ]
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
        combined = ExecutionResult(
            summary=attempt.summary,
            diff_patch="\n".join(change.diff_patch.rstrip() for change in changes) + "\n",
            execution_provider=attempt.execution_provider,
            engine="claude_sdk_team",
            turns=attempt.turns,
            token_usage=attempt.token_usage,
            changed_paths=sorted({path for change in changes for path in change.changed_paths}),
            session_id=attempt.session_id,
            subagent_runs=attempt.subagent_runs,
        )
        await self._finish_modification(signal_id, combined, snapshot)

    async def _finish_modification(
        self, signal_id: str, result: ExecutionResult, snapshot: ContextSnapshot,
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
        payload["repoDiffs"] = [
            {"repo": repo.repo_id, "diffPatch": diff}
            for repo, diff in self._repo_diffs()
        ]
        await self._emit(self.state.modification_finished(result), signal_id, payload)

    def _candidate_summary(self, candidates: list[dict]) -> list[dict]:
        return [{
            "repo": item.get("repo", ""),
            "summary": item.get("summary", ""),
            "changedPaths": item.get("changed_paths", []),
        } for item in candidates]

    def _previous_candidate(self) -> list[dict]:
        if self.completed_stage_results:
            return [
                {
                    "repo": result.repo or self._case_input().effective_repos()[0].repo_id,
                    "diff_patch": result.diff_patch,
                    "changed_paths": result.changed_paths or self._diff_paths(result.diff_patch),
                    "summary": result.summary,
                }
                for result in self.completed_stage_results
                if result.diff_patch.strip()
            ]
        if self.state.diff_patch.strip():
            repo = self._case_input().effective_repos()[0]
            return [{
                "repo": repo.repo_id,
                "diff_patch": self.state.diff_patch,
                "changed_paths": self._diff_paths(self.state.diff_patch),
                "summary": "上一版候选",
            }]
        return []

    def _coding_timeout(self) -> int:
        snapshot = self._case_input().agent_config_snapshot
        if snapshot is None:
            return 3600
        developer = next((agent for agent in snapshot.agents if agent.name == "developer"), None)
        return (developer.timeout_seconds if developer and developer.timeout_seconds else 3600) + 30
