from typing import Protocol

from asterism_worker.contracts import CodingAttemptRequest, CodingAttemptResult
from asterism_worker.repo_source import TeamWorkspace


class ExecutionProvider(Protocol):
    """Coding Attempt 扩展协议；实现方不得接管 Temporal 生命周期。"""

    async def run(self, request: CodingAttemptRequest, workspace: TeamWorkspace) -> CodingAttemptResult:
        ...
