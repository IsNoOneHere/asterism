import logging

from asterism_worker.contracts import (
    CodingAttemptRequest,
    CodingAttemptResult,
    CodingPlanDraft,
    CodingPlanRequest,
    ExecutionOutcome,
    RepoChangeResult,
    SubagentRun,
)
from asterism_worker.providers.base import ExecutionProvider
from asterism_worker.repo_source import TeamWorkspace

log = logging.getLogger(__name__)


class FakeExecutionProvider(ExecutionProvider):
    async def plan(self, request: CodingPlanRequest, _workspace: TeamWorkspace) -> CodingPlanDraft:
        return CodingPlanDraft(
            plan_markdown=f"# 执行计划\n\n{request.goal}",
            revision=request.plan_revision,
            session_id="fake-session",
            acceptance_criteria_refs=[
                f"AC-{index + 1}" for index in range(len(request.acceptance_criteria))
            ],
            repositories=[repo.repo_id for repo in request.repos],
        )

    async def run(self, request: CodingAttemptRequest, _workspace: TeamWorkspace) -> CodingAttemptResult:
        log.info("fake provider 生成 diff", extra={"work_item_id": request.work_item_id})
        repo = request.repos[0].repo_id
        return CodingAttemptResult(
            summary="fake diff generated",
            outcome=ExecutionOutcome(
                status="completed",
                changed_paths=["README.md"],
            ),
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
