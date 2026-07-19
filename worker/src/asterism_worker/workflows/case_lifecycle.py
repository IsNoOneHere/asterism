import asyncio
from dataclasses import dataclass
from datetime import timedelta
from enum import StrEnum

from temporalio.common import RetryPolicy
from temporalio import workflow
from temporalio.exceptions import ActivityError, ApplicationError

from asterism_worker.contracts import AgentConfigSnapshot, CaseInput, CodingAttemptResult, ContextSnapshot, ExecutionResult, GitlabPublishResult, LifecycleStatus, MergeRequestRef, PatchApplyResult, ProjectionEvent, RepoSnapshot, ValidationResult
from asterism_worker.workflows.state_machine import CaseState, TERMINAL_STATUSES

MERGE_POLL_INTERVAL = timedelta(seconds=60)
CLAUDE_SDK_TEAM_ARCHITECTURE = "claude_sdk_team"


class ExecutionPhase(StrEnum):
    """可独立重试的固定执行阶段。"""

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


@dataclass(frozen=True, slots=True)
class RecoveryAction:
    use_failed_phase: bool
    refresh_configuration: bool = False


PHASE_RECOVERIES = {
    ExecutionPhase.planning: PhaseRecovery("_start_modification"),
    ExecutionPhase.coding: PhaseRecovery("_retry_coding_phase"),
    ExecutionPhase.patch: PhaseRecovery("_apply_patch", restore_modification=True),
    ExecutionPhase.validation: PhaseRecovery(
        "_run_validation", restore_modification=True, checkpoints=("patch_apply_approved",),
    ),
    ExecutionPhase.release: PhaseRecovery(
        "_release", restore_modification=True,
        checkpoints=("patch_apply_approved", "validation_passed"),
    ),
}
RECOVERY_ACTIONS = {
    "rework": RecoveryAction(use_failed_phase=False),
    "retry_current_phase": RecoveryAction(use_failed_phase=True),
    "rework_with_latest_config": RecoveryAction(use_failed_phase=True, refresh_configuration=True),
}
CONFIG_REFRESH_PHASES = frozenset({ExecutionPhase.planning, ExecutionPhase.coding})
FEEDBACK_POLICIES = {
    "patch_apply_rejected": ("replace", "人工审核反馈"),
    "validation_rejected": ("replace", "人工验证反馈"),
    **{action: ("append", "重试补充") for action in RECOVERY_ACTIONS},
}
ACTION_STATUSES = {
    "owner_approved": {"waiting_owner_approval"},
    "owner_rejected": {"waiting_owner_approval"},
    "start_modification": {"activated"},
    "patch_apply_approved": {"modification_completed"},
    "patch_apply_rejected": {"modification_completed"},
    "validation_passed": {"patch_applied"},
    "validation_rejected": {"patch_applied"},
    "release_approved": {"validation_passed"},
    "rework": {"worker_blocked", "patch_rejected", "validation_failed", "waiting_merge"},
    "retry_current_phase": {"worker_blocked"},
    "rework_with_latest_config": {"worker_blocked"},
    "check_merge_status": {"waiting_merge"},
    "cancel_case": {"waiting_owner_approval", "activated", "worker_blocked", "modification_completed",
                    "patch_rejected", "validation_failed", "validation_passed", "waiting_merge"},
}


@workflow.defn(name="AsterismCaseWorkflow")
class AsterismCaseWorkflow:
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
                await workflow.wait_condition(lambda: bool(self.pending_actions) or self.state.status in TERMINAL_STATUSES)
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
    async def owner_approved(self, signal: str | dict) -> None:
        self._enqueue("owner_approved", signal)

    @workflow.signal
    async def start_modification(self, signal: str | dict) -> None:
        self._enqueue("start_modification", signal)

    @workflow.signal
    async def patch_apply_approved(self, signal: str | dict) -> None:
        self._enqueue("patch_apply_approved", signal)

    @workflow.signal
    async def patch_apply_rejected(self, signal: str | dict) -> None:
        self._enqueue("patch_apply_rejected", signal)

    @workflow.signal
    async def validation_passed(self, signal: str | dict) -> None:
        self._enqueue("validation_passed", signal)

    @workflow.signal
    async def validation_rejected(self, signal: str | dict) -> None:
        self._enqueue("validation_rejected", signal)

    @workflow.signal
    async def rework(self, signal: str | dict) -> None:
        self._enqueue("rework", signal)

    @workflow.signal
    async def retry_current_phase(self, signal: str | dict) -> None:
        self._enqueue("retry_current_phase", signal)

    @workflow.signal
    async def rework_with_latest_config(self, signal: str | dict) -> None:
        self._enqueue("rework_with_latest_config", signal)

    @workflow.signal
    async def release_approved(self, signal: str | dict) -> None:
        self._enqueue("release_approved", signal)

    @workflow.signal
    async def check_merge_status(self, signal: str | dict) -> None:
        self._enqueue("check_merge_status", signal)

    @workflow.signal
    async def cancel_case(self, signal: str | dict) -> None:
        self._enqueue("cancel_case", signal)

    @workflow.signal
    async def owner_rejected(self, signal: str | dict) -> None:
        self._enqueue("owner_rejected", signal)

    @workflow.query
    def current_status(self) -> str:
        return self.state.status.value

    async def _handle_action(self, action: str, signal_id: str, context: dict) -> bool:
        if self.state.status.value not in ACTION_STATUSES.get(action, set()):
            workflow.logger.warning("非法或过期 signal，已拒绝", extra={"action": action, "status": self.state.status.value})
            return False
        feedback = "\n".join(str(context.get(key, "")).strip() for key in ("note", "evidence")
                             if str(context.get(key, "")).strip())
        if feedback and action in FEEDBACK_POLICIES:
            self._capture_feedback(action, feedback)
        if action == "owner_approved":
            await self._emit(self.state.owner_approved(), signal_id, {})
            return True
        if action == "start_modification":
            await self._start_modification(signal_id)
            return True
        if action == "patch_apply_approved":
            await self._apply_patch(signal_id)
            return True
        if action == "release_approved":
            await self._release(signal_id)
            return True
        recovery = RECOVERY_ACTIONS.get(action)
        if recovery is not None:
            return await self._handle_recovery_action(action, recovery, signal_id, context)
        if action == "check_merge_status":
            await self._poll_merge_requests(signal_id)
            return True
        if action == "validation_rejected" or (action == "cancel_case" and self.state.status == LifecycleStatus.validation_passed):
            failed = await self._revert_if_needed(signal_id)
            if failed:
                await self._block_worker(signal_id, "patch_revert_failed", RuntimeError(failed))
                return True
        events = {
            "patch_apply_rejected": self.state.patch_apply_rejected,
            "validation_passed": self.state.validation_passed,
            "validation_rejected": self.state.validation_rejected,
            "cancel_case": self.state.cancel_case,
            "owner_rejected": self.state.owner_rejected,
        }
        await self._emit(events[action](), signal_id, context)
        return True

    def _capture_feedback(self, action: str, feedback: str) -> None:
        """审核意见建立修订基线，后续重试备注只追加上下文。"""

        mode, label = FEEDBACK_POLICIES[action]
        previous = self.rework_feedback if mode == "append" else ""
        self.rework_feedback = "\n".join(item for item in (previous, f"{label}：{feedback}") if item)

    async def _handle_recovery_action(self, action: str, recovery: RecoveryAction,
                                      signal_id: str, context: dict) -> bool:
        phase = self._failed_execution_phase() if recovery.use_failed_phase else ExecutionPhase.planning
        if phase is None:
            workflow.logger.warning("缺少可恢复阶段", extra={"action": action})
            return False
        if recovery.refresh_configuration and phase not in CONFIG_REFRESH_PHASES:
            workflow.logger.warning("当前阶段不消费 Agent 配置", extra={"phase": phase.value})
            return False

        configuration_refreshed = False
        if recovery.refresh_configuration:
            snapshot = self._agent_config_snapshot(context)
            if snapshot is None:
                return False
            # 配置生成新执行版本，但计划、上下文和已完成 Agent 结果保持不变。
            self.case_input = self._case_input().model_copy(update={"agent_config_snapshot": snapshot})
            configuration_refreshed = True
        context.pop("resume_failed_stage", None)

        previous_status = self.state.status.value
        event = self.state.rework()
        await self._emit(event, signal_id, {
            "configurationRefreshed": configuration_refreshed,
            "retryPhase": phase.value,
            "retryScope": "phase" if recovery.use_failed_phase else "full",
        })
        if event is None:
            return False

        preparer = {"waiting_merge": self._prepare_merge_rework}.get(previous_status)
        if preparer is not None:
            preparer()
        self.failed_phase = ""
        if not recovery.use_failed_phase:
            await self._start_modification(signal_id)
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

    def _prepare_merge_rework(self) -> None:
        self.expected_remote_commits = {item["repo"]: item["commitHash"] for item in self.gitlab_releases}
        self.gitlab_releases = []
        self.merge_requests = []
        self.merged_repos = set()

    def _enqueue(self, action: str, signal: str | dict) -> None:
        context = dict(signal) if isinstance(signal, dict) else {}
        signal_id = str(context.pop("signal_id", "") if context else signal)
        if not signal_id:
            workflow.logger.warning("忽略缺少 signal_id 的手动动作", extra={"action": action})
            return
        duplicate = signal_id in self.processed_signal_ids or any(item[1] == signal_id for item in self.pending_actions)
        if duplicate:
            workflow.logger.info("忽略重复手动动作", extra={"action": action, "signal_id": signal_id})
            return
        self.pending_actions.append((action, signal_id, context))

    async def _start_modification(self, signal_id: str) -> None:
        await self._start_supervised_modification(signal_id)

    async def _start_supervised_modification(self, signal_id: str, reuse_context: bool = False) -> None:
        """新 Case 只启动一个 Claude SDK Coding Attempt，仓库分工留在 SDK 会话内部。"""

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
                await self._block_worker(signal_id, "context_fetch_failed", error,
                                         phase=ExecutionPhase.planning)
                return
            snapshot = ContextSnapshot.model_validate(result_payload)
        self.context_snapshot = snapshot
        previous_candidate = self._previous_candidate()
        repos = case_input.effective_repos()
        await self._emit("CodingAttemptStarted", signal_id, {
            "architecture": CLAUDE_SDK_TEAM_ARCHITECTURE,
            "supervisor": {"role": "developer", "engine": "claude_sdk_team"},
            "repositories": [repo.repo_id for repo in repos],
            "contextManifestId": snapshot.manifest_id,
            "candidateReused": bool(previous_candidate),
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
        changes = [change for change in attempt.repo_changes if change.diff_patch.strip()]
        if not changes:
            await self._block_worker(signal_id, "coding_attempt_failed", RuntimeError("Coding Attempt 未生成代码变更"), {
                "failed_stage": {"index": 0, "role": "developer"},
                "executionArchitecture": CLAUDE_SDK_TEAM_ARCHITECTURE,
            }, phase=ExecutionPhase.coding)
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

    async def _finish_modification(self, signal_id: str, result: ExecutionResult,
                                   snapshot: ContextSnapshot) -> None:
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

    def _repo_diffs(self) -> list[tuple[RepoSnapshot, str]]:
        repos = self._case_input().effective_repos()
        if not self.completed_stage_results:
            return [(repos[0], self.state.diff_patch)]
        grouped: dict[str, list[str]] = {}
        for result in self.completed_stage_results:
            grouped.setdefault(result.repo or repos[0].repo_id, []).append(result.diff_patch.rstrip())
        by_id = {repo.repo_id: repo for repo in repos}
        return [(by_id[repo_id], "\n".join(parts) + "\n") for repo_id, parts in grouped.items()]

    def _is_gitlab(self) -> bool:
        return self._case_input().release_mode == "gitlab"

    async def _apply_patch(self, signal_id: str) -> None:
        case_input = self._case_input()
        if self.state.status.value != "modification_completed":
            workflow.logger.warning("非法 patch_apply_approved，已忽略", extra={"status": self.state.status.value})
            return
        if self._is_gitlab():
            await self._publish_gitlab(signal_id)
            return
        changes = self._repo_diffs()
        applied = []
        for repo, diff_patch in changes:
            try:
                result_payload = await workflow.execute_activity(
                    "apply_patch_to_repo",
                    {
                        "repo_path": repo.local_path,
                        "diff_patch": diff_patch,
                        "allowed_paths": repo.allowed_paths,
                        "forbidden_paths": repo.forbidden_paths,
                    },
                    start_to_close_timeout=timedelta(minutes=2),
                    retry_policy=RetryPolicy(maximum_attempts=3),
                )
            except (ActivityError, ApplicationError) as error:
                failed = await self._revert_changes(applied, signal_id)
                await self._block_worker(signal_id, "patch_revert_failed" if failed else "patch_apply_failed",
                                         RuntimeError(failed) if failed else error,
                                         {"repo": repo.repo_id},
                                         phase=None if failed else ExecutionPhase.patch)
                return
            result = PatchApplyResult.model_validate(result_payload)
            if result.blocked:
                failed = await self._revert_changes(applied, signal_id)
                if failed:
                    await self._block_worker(signal_id, "patch_revert_failed", RuntimeError(failed),
                                             {"repo": repo.repo_id})
                else:
                    await self._emit(self.state.patch_apply_blocked(), signal_id, {
                        "reason": result.reason,
                        "repo": repo.repo_id,
                    })
                return
            applied.append((repo, diff_patch))
        await self._emit(self.state.patch_apply_approved(), signal_id,
                         {"repositories": [repo.repo_id for repo, _ in changes]})
        self.failed_phase = ""
        if case_input.validation_mode == "auto" and any(repo.test_commands for repo, _ in changes):
            await self._run_validation(signal_id)
        elif case_input.validation_mode != "manual":
            await self._emit(self.state.validation_passed(), signal_id, {
                "commands": [], "failedCommand": "", "stderrTail": "", "skipped": True,
            })

    async def _publish_gitlab(self, signal_id: str) -> None:
        case_input = self._case_input()
        changes = self._repo_diffs()
        await self._emit(self.state.patch_apply_approved(), signal_id, {
            "repositories": [repo.repo_id for repo, _ in changes],
        })
        prepared_repos = {item["repo"] for item in self.gitlab_releases}
        for repo, diff_patch in changes:
            if repo.repo_id in prepared_repos:
                continue
            try:
                payload = await workflow.execute_activity(
                    "publish_merge_request",
                    {
                        "system_id": case_input.system_id,
                        "work_item_id": case_input.work_item_id,
                        "title": case_input.prd.title,
                        "goal": case_input.prd.goal,
                        "acceptance_criteria": case_input.prd.acceptance_criteria,
                        "repo": repo.model_dump(),
                        "diff_patch": diff_patch,
                        "validation_mode": case_input.validation_mode,
                        "mr_target_branch": case_input.mr_target_branch,
                        "mr_labels": case_input.mr_labels,
                        "expected_remote_commit": self.expected_remote_commits.get(repo.repo_id, ""),
                    },
                    start_to_close_timeout=timedelta(minutes=15),
                    retry_policy=RetryPolicy(maximum_attempts=3),
                )
            except (ActivityError, ApplicationError) as error:
                self.resume_phase = "gitlab_publish"
                await self._block_worker(signal_id, "mr_create_failed", error, {"repo": repo.repo_id},
                                         phase=ExecutionPhase.patch)
                return
            result = GitlabPublishResult.model_validate(payload)
            if not result.validation.passed:
                self.resume_phase = ""
                await self._emit(self.state.validation_rejected(), signal_id, {
                    "repo": repo.repo_id,
                    "commands": [command.model_dump() for command in result.validation.commands],
                    "failedCommand": result.validation.failed_command,
                    "stderrTail": result.validation.stderr_tail,
                })
                return
            if result.merge_request is None:
                self.resume_phase = "gitlab_publish"
                await self._block_worker(signal_id, "mr_create_failed", RuntimeError("MR response missing"),
                                         {"repo": repo.repo_id}, phase=ExecutionPhase.patch)
                return
            self.validation_commands.extend(
                {**command.model_dump(), "repo": repo.repo_id} for command in result.validation.commands
            )
            release = {
                "repo": repo.repo_id,
                "project": repo.gitlab_project,
                "branch": result.branch,
                "commitHash": result.commit_hash,
                "changedPaths": self._diff_paths(diff_patch),
                "mrIid": result.merge_request.mr_iid,
                "mrUrl": result.merge_request.mr_url,
                "state": result.merge_request.state,
            }
            self.gitlab_releases.append(release)
            self.merge_requests.append(result.merge_request)
            await self._emit("RepositoryReleasePrepared", signal_id, release,
                             suffix=f"repo:{repo.repo_id}:{result.commit_hash}")

        self.resume_phase = ""
        if case_input.validation_mode == "manual":
            return
        await self._emit(self.state.validation_passed(), signal_id, {
            "commands": self.validation_commands,
            "failedCommand": "",
            "stderrTail": "",
            "skipped": case_input.validation_mode == "skip",
        })
        await self._activate_gitlab_mrs(signal_id, ready=False)

    async def _activate_gitlab_mrs(self, signal_id: str, ready: bool) -> None:
        if not self.merge_requests:
            raise ApplicationError("没有可提交的 GitLab MR", non_retryable=True)
        if ready:
            payload = await workflow.execute_activity(
                "ready_merge_requests",
                {
                    "system_id": self._case_input().system_id,
                    "repos": [repo.model_dump() for repo in self._case_input().effective_repos()],
                    "merge_requests": [item.model_dump() for item in self.merge_requests],
                },
                start_to_close_timeout=timedelta(minutes=1),
                retry_policy=RetryPolicy(maximum_attempts=3),
            )
            self.merge_requests = [MergeRequestRef.model_validate(item) for item in payload]
        self.merged_repos = set()
        event_type = self.state.merge_requests_created()
        for merge_request in self.merge_requests:
            release = next(item for item in self.gitlab_releases if item["repo"] == merge_request.repo)
            await self._emit(event_type, signal_id, {
                **release,
                "state": merge_request.state,
            }, suffix=f"mr:{merge_request.repo}:{merge_request.mr_iid}")
            event_type = "MergeRequestCreated"
        await self._poll_merge_requests("merge-created")

    async def _poll_merge_requests(self, causation_id: str) -> None:
        if not self._is_gitlab() or self.state.status.value != "waiting_merge" or not self.merge_requests:
            return
        try:
            payload = await workflow.execute_activity(
                "check_merge_requests",
                {
                    "system_id": self._case_input().system_id,
                    "repos": [repo.model_dump() for repo in self._case_input().effective_repos()],
                    "merge_requests": [item.model_dump() for item in self.merge_requests],
                },
                start_to_close_timeout=timedelta(minutes=1),
                retry_policy=RetryPolicy(maximum_attempts=3),
            )
        except (ActivityError, ApplicationError) as error:
            workflow.logger.warning("GitLab MR 轮询失败，等待下次 Temporal timer",
                                    extra={"type": type(error).__name__})
            return
        current = [MergeRequestRef.model_validate(item) for item in payload]
        self.merge_requests = current
        for item in current:
            if item.state == "merged" and item.repo not in self.merged_repos:
                self.merged_repos.add(item.repo)
                await self._emit("MergeRequestMerged", f"mr-merged-{item.repo}-{item.mr_iid}", {
                    "repo": item.repo,
                    "mrIid": item.mr_iid,
                    "mrUrl": item.mr_url,
                })
        closed = next((item for item in current if item.state == "closed"), None)
        if closed:
            await self._emit(self.state.merge_request_closed(), f"mr-closed-{closed.repo}-{closed.mr_iid}", {
                "repo": closed.repo,
                "mrIid": closed.mr_iid,
                "mrUrl": closed.mr_url,
                "reason": "mr_closed",
            })
            return
        if current and all(item.state == "merged" for item in current):
            first = self.gitlab_releases[0]
            await self._emit(self.state.all_merged(), f"all-merged-{self._case_input().case_id}", {
                "branch": first["branch"],
                "commitHash": first["commitHash"],
                "changedPaths": sorted({path for item in self.gitlab_releases for path in item["changedPaths"]}),
                "repositories": [
                    {**release, "state": "merged"}
                    for release in self.gitlab_releases
                ],
            })

    async def _release(self, signal_id: str) -> None:
        case_input = self._case_input()
        if self.state.status.value != "validation_passed":
            workflow.logger.warning("非法 release_approved，已忽略", extra={"status": self.state.status.value})
            return
        if self._is_gitlab():
            try:
                await self._activate_gitlab_mrs(signal_id, ready=case_input.validation_mode == "manual")
            except (ActivityError, ApplicationError) as error:
                self.resume_phase = "gitlab_ready"
                await self._block_worker(signal_id, "mr_ready_failed", error, phase=ExecutionPhase.release)
            else:
                self.failed_phase = ""
            return
        self.local_releases = [item for item in self.local_releases if not item.get("pushFailed")]
        prepared_repos = {item["repo"] for item in self.local_releases}
        for repo, diff_patch in self._repo_diffs():
            if repo.repo_id in prepared_repos:
                continue
            try:
                result = await workflow.execute_activity(
                    "run_release",
                    {
                        "repo_path": repo.local_path,
                        "work_item_id": case_input.work_item_id,
                        "title": case_input.prd.title,
                        "diff_patch": diff_patch,
                    },
                    start_to_close_timeout=timedelta(minutes=2),
                    retry_policy=RetryPolicy(maximum_attempts=2),
                )
            except (ActivityError, ApplicationError) as error:
                self.resume_phase = "local_release"
                await self._block_worker(signal_id, "release_failed", error,
                                         {"repo": repo.repo_id},
                                         phase=ExecutionPhase.release)
                return
            release = {
                "repo": repo.repo_id,
                "branch": result.get("branch", ""),
                "commitHash": result.get("commit_hash", ""),
                "pushFailed": result.get("push_failed", ""),
                "changedPaths": self._diff_paths(diff_patch),
            }
            self.local_releases.append(release)
            await self._emit("RepositoryReleasePrepared", signal_id, release,
                             suffix=f"repo:{repo.repo_id}:{release['commitHash']}")
            if release["pushFailed"]:
                self.resume_phase = "local_release"
                await self._block_worker(signal_id, "push_failed", RuntimeError(release["pushFailed"]),
                                         {"repo": repo.repo_id},
                                         phase=ExecutionPhase.release)
                return
        releases = self.local_releases
        self.resume_phase = ""
        first = releases[0]
        payload = {
            "branch": first["branch"],
            "commitHash": first["commitHash"],
            "pushFailed": first["pushFailed"],
            "changedPaths": sorted({path for item in releases for path in item["changedPaths"]}),
            "repositories": releases,
        }
        self.failed_phase = ""
        await self._emit(self.state.release_approved(), signal_id, payload)

    async def _revert_if_needed(self, signal_id: str) -> str:
        if not self.state.diff_patch or self._is_gitlab():
            return ""
        return await self._revert_changes(self._repo_diffs(), signal_id)

    async def _run_validation(self, signal_id: str) -> None:
        commands = []
        for repo, _ in self._repo_diffs():
            if not repo.test_commands:
                continue
            try:
                result_payload = await workflow.execute_activity(
                    "run_validation",
                    {"repo_path": repo.local_path, "test_commands": repo.test_commands},
                    start_to_close_timeout=timedelta(minutes=10),
                    retry_policy=RetryPolicy(maximum_attempts=1),
                )
            except (ActivityError, ApplicationError) as error:
                self.validation_commands = commands
                await self._block_worker(
                    signal_id, "validation_activity_failed", error,
                    {"repo": repo.repo_id},
                    phase=ExecutionPhase.validation,
                )
                return
            result = ValidationResult.model_validate(result_payload)
            commands.extend([
                {**command.model_dump(), "repo": repo.repo_id}
                for command in result.commands
            ])
            if not result.passed:
                self.failed_phase = ""
                self.validation_commands = commands
                failed = await self._revert_if_needed(signal_id)
                if failed:
                    await self._block_worker(signal_id, "patch_revert_failed", RuntimeError(failed),
                                             {"repo": repo.repo_id})
                    return
                await self._emit(self.state.validation_rejected(), signal_id, {
                    "commands": commands,
                    "failedCommand": result.failed_command,
                    "stderrTail": result.stderr_tail,
                    "repo": repo.repo_id,
                })
                return
        self.validation_commands = commands
        self.failed_phase = ""
        await self._emit(self.state.validation_passed(), signal_id, {
            "commands": commands,
            "failedCommand": "",
            "stderrTail": "",
        })

    async def _revert_changes(self, changes: list, signal_id: str) -> str:
        for repo, diff_patch in changes:
            try:
                result = await workflow.execute_activity(
                    "revert_patch",
                    {"repo_path": repo.local_path, "diff_patch": diff_patch},
                    start_to_close_timeout=timedelta(minutes=1),
                    retry_policy=RetryPolicy(maximum_attempts=1),
                )
                if result.get("failed"):
                    return f"{repo.repo_id}: {result['failed']}"
            except (ActivityError, ApplicationError) as error:
                workflow.logger.warning("回滚 patch activity 失败",
                                        extra={"signal_id": signal_id, "repo": repo.repo_id})
                return f"{repo.repo_id}: {self._error_detail(error)}"
        return ""

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

    async def _block_worker(self, signal_id: str, reason: str, error: BaseException,
                            extra: dict | None = None, phase: ExecutionPhase | None = None) -> None:
        self.failed_phase = phase.value if phase is not None else ""
        phase_payload = {"failedPhase": self.failed_phase} if self.failed_phase else {}
        await self._emit(self.state.worker_blocked_on(reason), signal_id, {
            "reason": reason,
            "detail": self._error_detail(error),
            **phase_payload,
            **(extra or {}),
        })

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
