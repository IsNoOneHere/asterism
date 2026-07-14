import logging

from asterism_worker.contracts import ExecutionRequest, ExecutionResult
from asterism_worker.providers.base import ExecutionProvider

log = logging.getLogger(__name__)


class FakeExecutionProvider(ExecutionProvider):
    async def run(self, request: ExecutionRequest) -> ExecutionResult:
        log.info("fake provider 生成 diff", extra={"work_item_id": request.work_item_id})
        return ExecutionResult(
            summary="fake diff generated",
            diff_patch=(
                "diff --git a/README.md b/README.md\n"
                "--- a/README.md\n"
                "+++ b/README.md\n"
                "@@ -1 +1 @@\n"
                "-asterism\n"
                "+Asterism\n"
            ),
        )

