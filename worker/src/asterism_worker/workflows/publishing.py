from datetime import timedelta

from temporalio import workflow
from temporalio.common import RetryPolicy
from temporalio.exceptions import ActivityError, ApplicationError

from asterism_worker.contracts import GitlabPublishResult, MergeRequestRef, PatchApplyResult, RepoSnapshot
from asterism_worker.workflows.coding import ExecutionPhase


def diff_paths(diff_patch: str) -> list[str]:
    paths: list[str] = []
    for line in diff_patch.splitlines():
        if line.startswith("diff --git "):
            parts = line.split()
            if len(parts) >= 4:
                paths.append(parts[3][2:] if parts[3].startswith("b/") else parts[3])
    return paths


class PublishingWorkflow:
    def _repo_diffs(self) -> list[tuple[RepoSnapshot, str]]:
        repos = self._case_input().effective_repos()
        if not self.completed_stage_results:
            return [(repos[0], self.state.diff_patch)]
        by_id = {repo.repo_id: repo for repo in repos}
        # 每仓 Patch 已在 Coding Activity 中校验，发布阶段直接传递不可变制品。
        return [
            (by_id[result.repo or repos[0].repo_id], result.diff_patch)
            for result in self.completed_stage_results
        ]

    def _diff_paths(self, diff_patch: str) -> list[str]:
        return diff_paths(diff_patch)

    def _prepare_merge_rework(self) -> None:
        """保留原分支的远端提交基线，让修订继续推送到同一 MR。"""

        self.expected_remote_commits = {item["repo"]: item["commitHash"] for item in self.gitlab_releases}
        self.gitlab_releases = []
        self.merge_requests = []
        self.merged_repos = set()

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
                await self._block_worker(
                    signal_id,
                    "patch_revert_failed" if failed else "patch_apply_failed",
                    RuntimeError(failed) if failed else error,
                    {"repo": repo.repo_id},
                    phase=None if failed else ExecutionPhase.patch,
                )
                return
            result = PatchApplyResult.model_validate(result_payload)
            if result.blocked:
                failed = await self._revert_changes(applied, signal_id)
                if failed:
                    await self._block_worker(
                        signal_id, "patch_revert_failed", RuntimeError(failed), {"repo": repo.repo_id},
                    )
                else:
                    # PatchApplyBlocked 同样是可恢复失败，必须保留阶段信息供人工原位重试。
                    self.failed_phase = ExecutionPhase.patch.value
                    await self._emit(self.state.patch_apply_blocked(), signal_id, {
                        "reason": result.reason,
                        "repo": repo.repo_id,
                        "failedPhase": self.failed_phase,
                    })
                return
            applied.append((repo, diff_patch))
        await self._emit(
            self.state.patch_apply_approved(),
            signal_id,
            {"repositories": [repo.repo_id for repo, _ in changes]},
        )
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
                await self._block_worker(
                    signal_id, "mr_create_failed", error, {"repo": repo.repo_id}, phase=ExecutionPhase.patch,
                )
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
                await self._block_worker(
                    signal_id,
                    "mr_create_failed",
                    RuntimeError("MR response missing"),
                    {"repo": repo.repo_id},
                    phase=ExecutionPhase.patch,
                )
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
            await self._emit(
                "RepositoryReleasePrepared",
                signal_id,
                release,
                suffix=f"repo:{repo.repo_id}:{result.commit_hash}",
            )

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
            workflow.logger.warning(
                "GitLab MR 轮询失败，等待下次 Temporal timer", extra={"type": type(error).__name__},
            )
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
            await self._emit(
                self.state.merge_request_closed(),
                f"mr-closed-{closed.repo}-{closed.mr_iid}",
                {
                    "repo": closed.repo,
                    "mrIid": closed.mr_iid,
                    "mrUrl": closed.mr_url,
                    "reason": "mr_closed",
                },
            )
            return
        if current and all(item.state == "merged" for item in current):
            first = self.gitlab_releases[0]
            await self._emit(self.state.all_merged(), f"all-merged-{self._case_input().case_id}", {
                "branch": first["branch"],
                "commitHash": first["commitHash"],
                "changedPaths": sorted({path for item in self.gitlab_releases for path in item["changedPaths"]}),
                "repositories": [{**release, "state": "merged"} for release in self.gitlab_releases],
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
                await self._block_worker(
                    signal_id, "release_failed", error, {"repo": repo.repo_id}, phase=ExecutionPhase.release,
                )
                return
            release = {
                "repo": repo.repo_id,
                "branch": result.get("branch", ""),
                "commitHash": result.get("commit_hash", ""),
                "pushFailed": result.get("push_failed", ""),
                "changedPaths": self._diff_paths(diff_patch),
            }
            self.local_releases.append(release)
            await self._emit(
                "RepositoryReleasePrepared",
                signal_id,
                release,
                suffix=f"repo:{repo.repo_id}:{release['commitHash']}",
            )
            if release["pushFailed"]:
                self.resume_phase = "local_release"
                await self._block_worker(
                    signal_id,
                    "push_failed",
                    RuntimeError(release["pushFailed"]),
                    {"repo": repo.repo_id},
                    phase=ExecutionPhase.release,
                )
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
