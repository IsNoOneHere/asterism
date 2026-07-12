import logging
from typing import Protocol

from agent_team_v5.contracts import ExecutionPlan, PlanRequest

log = logging.getLogger(__name__)


class PlannerProvider(Protocol):
    """Planner provider 协议；真实规划器和 fake 测试实现共享这条边界。"""

    async def plan(self, request: PlanRequest) -> ExecutionPlan:
        ...


class FakePlannerProvider(PlannerProvider):
    async def plan(self, request: PlanRequest) -> ExecutionPlan:
        # fake planner 只基于验收标准生成稳定计划，测试不依赖真实 LLM。
        criteria = request.prd.acceptance_criteria or [request.prd.goal]
        steps = [f"按验收标准修改: {item}" for item in criteria]
        targets = request.allowed_paths[:1] or ["README.md"]
        log.info("fake planner 生成执行计划", extra={"manifest_id": request.context_manifest_id})
        return ExecutionPlan(
            steps=steps,
            target_files=targets,
            test_plan=["运行系统配置的测试命令"],
            risks=[],
        )
