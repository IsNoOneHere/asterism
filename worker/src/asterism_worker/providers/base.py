from typing import Protocol

from asterism_worker.contracts import ExecutionRequest, ExecutionResult


class ExecutionProvider(Protocol):
    """执行 provider 协议；真实 DeepAgents/OpenAI-Agents 按此替换 fake。"""

    async def run(self, request: ExecutionRequest) -> ExecutionResult:
        ...

