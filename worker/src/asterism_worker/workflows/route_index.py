from datetime import timedelta

from temporalio import workflow
from temporalio.common import RetryPolicy

from asterism_worker.contracts import RouteIndexInput


@workflow.defn(name="AsterismRouteIndexWorkflow")
class AsterismRouteIndexWorkflow:
    @workflow.run
    async def run(self, request: RouteIndexInput) -> int:
        entries = await workflow.execute_activity(
            "index_system_routes",
            request.model_dump(),
            start_to_close_timeout=timedelta(minutes=5),
            retry_policy=RetryPolicy(maximum_attempts=2),
        )
        await workflow.execute_activity(
            "send_knowledge_candidates",
            {"system_id": request.system_id, "entries": entries},
            start_to_close_timeout=timedelta(seconds=30),
            retry_policy=RetryPolicy(maximum_attempts=5),
        )
        return len(entries)
