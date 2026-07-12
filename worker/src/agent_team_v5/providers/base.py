from typing import Protocol

from agent_team_v5.contracts import ExecutionRequest, ExecutionResult


class ExecutionProvider(Protocol):
    """执行 provider 协议；真实 DeepAgents/OpenAI-Agents 按此替换 fake。"""

    async def run(self, request: ExecutionRequest) -> ExecutionResult:
        ...

