import logging

from asterism_worker.contracts import CodingAttemptRequest, CodingAttemptResult, RepoChangeResult, SubagentRun
from asterism_worker.providers.base import ExecutionProvider
from asterism_worker.repo_source import TeamWorkspace

log = logging.getLogger(__name__)


class FakeExecutionProvider(ExecutionProvider):
    async def run(self, request: CodingAttemptRequest, _workspace: TeamWorkspace) -> CodingAttemptResult:
        log.info("fake provider 生成 diff", extra={"work_item_id": request.work_item_id})
        repo = request.repos[0].repo_id
        return CodingAttemptResult(
            summary="fake diff generated",
            repo_changes=[RepoChangeResult(
                repo=repo,
                summary="fake diff generated",
                changed_paths=["README.md"],
                diff_patch=(
                    "diff --git a/README.md b/README.md\n"
                    "--- a/README.md\n"
                    "+++ b/README.md\n"
                    "@@ -1 +1 @@\n"
                    "-asterism\n"
                    "+Asterism\n"
                ),
            )],
            subagent_runs=[SubagentRun(agent_id="fake-repo", agent_type="fake-repo", repo=repo)],
            execution_provider="fake",
        )
