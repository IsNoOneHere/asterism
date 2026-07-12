import logging

import httpx

from agent_team_v5.contracts import ExecutionPlan, ExecutionRequest, ExecutionResult, PlanRequest
from agent_team_v5.providers.base import ExecutionProvider
from agent_team_v5.providers.planner import PlannerProvider

log = logging.getLogger(__name__)


class HttpExecutionProvider(ExecutionProvider):
    """真实执行引擎 adapter；DeepAgents/OpenAI-Agents 可挂在这个 HTTP 边界后。"""

    def __init__(self, endpoint: str) -> None:
        self.endpoint = endpoint

    async def run(self, request: ExecutionRequest) -> ExecutionResult:
        async with httpx.AsyncClient(timeout=120) as client:
            response = await client.post(self.endpoint, json=request.model_dump())
            response.raise_for_status()
        log.info("HTTP execution provider 返回", extra={"work_item_id": request.work_item_id})
        return ExecutionResult.model_validate(response.json())


class HttpPlannerProvider(PlannerProvider):
    """HTTP planner adapter；真实 LLM/规则规划器挂在这个边界后。"""

    def __init__(self, endpoint: str) -> None:
        self.endpoint = endpoint

    async def plan(self, request: PlanRequest) -> ExecutionPlan:
        async with httpx.AsyncClient(timeout=60) as client:
            response = await client.post(self.endpoint, json=request.model_dump())
            response.raise_for_status()
        log.info("HTTP planner provider 返回", extra={"manifest_id": request.context_manifest_id})
        return ExecutionPlan.model_validate(response.json())
