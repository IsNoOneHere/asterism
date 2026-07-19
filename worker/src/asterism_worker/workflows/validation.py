from datetime import timedelta

from temporalio import workflow
from temporalio.common import RetryPolicy
from temporalio.exceptions import ActivityError, ApplicationError

from asterism_worker.contracts import ValidationResult
from asterism_worker.workflows.coding import ExecutionPhase


class ValidationWorkflow:
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
                    signal_id,
                    "validation_activity_failed",
                    error,
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
                    await self._block_worker(
                        signal_id, "patch_revert_failed", RuntimeError(failed), {"repo": repo.repo_id},
                    )
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
                workflow.logger.warning(
                    "回滚 patch activity 失败", extra={"signal_id": signal_id, "repo": repo.repo_id},
                )
                return f"{repo.repo_id}: {self._error_detail(error)}"
        return ""
