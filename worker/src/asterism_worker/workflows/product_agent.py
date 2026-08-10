import asyncio
from datetime import timedelta

from temporalio import workflow
from temporalio.common import RetryPolicy
from temporalio.exceptions import CancelledError as TemporalCancelledError

from asterism_worker.contracts import ProductAgentExecutionInput

MODEL_ACTIVITY_TIMEOUT = timedelta(minutes=50)
MODEL_ACTIVITY_RETRY_POLICY = RetryPolicy(maximum_attempts=3)
# maximum_attempts=0 表示不限次数，终态回调不会因短暂网络故障丢失。
CALLBACK_RETRY_POLICY = RetryPolicy(
    initial_interval=timedelta(seconds=1),
    maximum_interval=timedelta(seconds=30),
    maximum_attempts=0,
)
CANCEL_CALLBACK_RETRY_POLICY = RetryPolicy(maximum_attempts=3)


@workflow.defn(name="AsterismProductAgentWorkflow")
class AsterismProductAgentWorkflow:
    @workflow.run
    async def run(self, request: ProductAgentExecutionInput) -> str:
        try:
            await self._send_event(request, "Started", "RETRIEVING_CONTEXT", 0)
            await self._send_event(request, "Heartbeat", "RETRIEVING_CONTEXT", 1)
            prepared = await workflow.execute_activity(
                "prepare_product_agent_input",
                request.model_dump(),
                start_to_close_timeout=timedelta(seconds=30),
                retry_policy=RetryPolicy(maximum_attempts=2),
            )
            await self._send_event(request, "Heartbeat", "ANALYZING_REQUIREMENT", 2)
            result = await workflow.execute_activity(
                "generate_product_draft",
                prepared,
                start_to_close_timeout=MODEL_ACTIVITY_TIMEOUT,
                heartbeat_timeout=timedelta(seconds=90),
                retry_policy=MODEL_ACTIVITY_RETRY_POLICY,
            )
        except (asyncio.CancelledError, TemporalCancelledError):
            await asyncio.shield(self._send_cancelled(request))
            raise
        except Exception as error:
            if _is_cancelled(error):
                await asyncio.shield(self._send_cancelled(request))
                raise TemporalCancelledError("Product Agent workflow 已取消")
            await self._send_event(
                request,
                "Failed",
                "FAILED",
                3,
                failure_code=_failure_code(error),
            )
            return "FAILED"

        await self._send_event(
            request,
            "Completed",
            "COMPLETED",
            3,
            result=result["result"],
            observations=result["observations"],
            image_analysis_failed=result["image_analysis_failed"],
        )
        return "COMPLETED"

    async def _send_cancelled(self, request: ProductAgentExecutionInput) -> None:
        try:
            # Workflow 取消已被当前任务接住，此处新建有限重试 Activity 尽力留下终态事实。
            await self._send_event(
                request,
                "Cancelled",
                "CANCELLED",
                3,
                retry_policy=CANCEL_CALLBACK_RETRY_POLICY,
            )
        except Exception as error:
            workflow.logger.warning(
                "Product Agent Cancelled 回调失败",
                extra={"execution_id": request.execution_id, "type": error.__class__.__name__},
            )

    async def _send_event(
        self,
        request: ProductAgentExecutionInput,
        event_type: str,
        stage: str,
        sequence: int,
        retry_policy: RetryPolicy = CALLBACK_RETRY_POLICY,
        **payload: object,
    ) -> None:
        event_id = f"{request.execution_id}:{event_type}:{request.attempt}:{sequence}"
        await workflow.execute_activity(
            "send_product_agent_event",
            {
                "execution_id": request.execution_id,
                "event": {
                    "event_id": event_id,
                    "idempotency_key": event_id,
                    "event_type": event_type,
                    "stage": stage,
                    "attempt": request.attempt,
                    **payload,
                },
            },
            start_to_close_timeout=timedelta(seconds=30),
            retry_policy=retry_policy,
        )


def _failure_code(_error: Exception) -> str:
    return "PRODUCT_AGENT_FAILED"


def _is_cancelled(error: BaseException) -> bool:
    current: BaseException | None = error
    while current is not None:
        if isinstance(current, (asyncio.CancelledError, TemporalCancelledError)):
            return True
        current = getattr(current, "cause", None)
    return False
