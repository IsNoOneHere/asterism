import asyncio
import logging

import httpx
from temporalio import activity

from asterism_worker.attempt_reliability import run_with_periodic_heartbeat
from asterism_worker.config.settings import load_settings
from asterism_worker.contracts import (
    ProductAgentActivityResult,
    ProductAgentDraftResult,
    ProductAgentExecutionInput,
    ProductImageObservation,
    ProductDraftActivityInput,
)

log = logging.getLogger(__name__)


@activity.defn
async def prepare_product_agent_input(request: dict) -> dict:
    """冻结本轮模型输入，附件 ID 只作为 Activity 输入传递。"""

    parsed = ProductAgentExecutionInput.model_validate(request)
    return ProductDraftActivityInput(
        execution_id=parsed.execution_id,
        attempt=parsed.attempt,
        system_id=parsed.system_id,
        content=parsed.content,
        attachment_ids=parsed.attachment_ids,
        current_draft=parsed.current_draft,
        missing_fields=parsed.missing_fields,
        conversation_history=parsed.conversation_history,
        context_items=parsed.context_items,
    ).model_dump()


@activity.defn
async def generate_product_draft(request: dict) -> dict:
    """调用同一 Runner，并在长请求期间刷新 Temporal 与 Control Plane heartbeat。"""

    parsed = ProductDraftActivityInput.model_validate(request)
    settings = load_settings()
    heartbeat_details = {"execution_id": parsed.execution_id, "stage": "ANALYZING_REQUIREMENT"}
    log.info("Product Agent 开始生成草稿 execution=%s", parsed.execution_id)
    result = await _run_with_execution_heartbeats(
        parsed,
        settings,
        run_with_periodic_heartbeat(
            _execute_product_request(parsed, settings, heartbeat_details),
            heartbeat_details,
            settings.activity_heartbeat_interval_seconds,
        ),
        heartbeat_details,
    )
    log.info("Product Agent 草稿生成完成 execution=%s", parsed.execution_id)
    return result.model_dump(by_alias=True)


async def _execute_product_request(parsed, settings, heartbeat_details) -> ProductAgentActivityResult:
    headers = {"Authorization": f"Bearer {settings.worker_callback_token}"}
    timeout = httpx.Timeout(settings.product_agent_http_timeout_seconds)
    observations: list[ProductImageObservation] = []
    image_analysis_failed = False
    async with httpx.AsyncClient(timeout=timeout) as client:
        for attachment_id in parsed.attachment_ids:
            try:
                observations.append(await _analyze_attachment(
                    client, settings, headers, parsed.system_id, attachment_id,
                ))
            except (httpx.HTTPError, ValueError, TypeError) as error:
                image_analysis_failed = True
                log.warning(
                    "Product Agent 单张图片分析降级 execution=%s type=%s",
                    parsed.execution_id,
                    error.__class__.__name__,
                )
        heartbeat_details["stage"] = "GENERATING_RESPONSE"
        payload = parsed.model_dump(exclude={"execution_id", "attempt", "attachment_ids"})
        payload["content"] = _content_with_observations(parsed.content, observations)
        response = await client.post(
            settings.agent_service_url.rstrip("/") + "/prd-draft",
            json=payload,
            headers=headers,
        )
        response.raise_for_status()
    return ProductAgentActivityResult(
        result=ProductAgentDraftResult.model_validate(response.json()),
        observations=observations,
        image_analysis_failed=image_analysis_failed,
    )


async def _analyze_attachment(client, settings, headers, system_id, attachment_id):
    attachment = await client.get(
        settings.control_plane_url.rstrip("/") + f"/api/v5/internal/attachments/{attachment_id}",
        params={"systemId": system_id},
        headers=headers,
    )
    attachment.raise_for_status()
    content_type = attachment.headers.get("content-type", "").split(";", 1)[0]
    analysis = await client.post(
        settings.agent_service_url.rstrip("/") + "/analyze-image",
        params={"system_id": system_id},
        content=attachment.content,
        headers={**headers, "Content-Type": content_type},
    )
    analysis.raise_for_status()
    return ProductImageObservation.model_validate(analysis.json())


def _content_with_observations(content: str, observations: list[ProductImageObservation]) -> str:
    image_context = []
    for index, observation in enumerate(observations, start=1):
        summary = observation.user_visible_summary.strip()
        anchors = "、".join(observation.anchors())
        image_context.append(f"图片{index}可见摘要：{summary}；可见文字：{anchors}")
    if not image_context:
        return content
    return content + ("\n\n" if content else "") + "\n".join(image_context)


@activity.defn
async def send_product_agent_event(request: dict) -> None:
    """把生命周期事实回调到 Control Plane；不记录模型正文或附件内容。"""

    settings = load_settings()
    execution_id = str(request["execution_id"])
    event = dict(request["event"])
    await _post_product_agent_event(settings, execution_id, event)
    log.info(
        "Product Agent 生命周期已回调 execution=%s event=%s stage=%s",
        execution_id,
        event.get("event_type", ""),
        event.get("stage", ""),
    )


async def _run_with_execution_heartbeats(parsed, settings, operation, heartbeat_details):
    """模型等待期间同步刷新 Temporal 与 Control Plane 两套存活事实。"""

    stopped = asyncio.Event()

    async def heartbeat_loop() -> None:
        activity_attempt = _activity_attempt()
        tick = 0
        while True:
            try:
                await asyncio.wait_for(
                    stopped.wait(),
                    timeout=settings.activity_heartbeat_interval_seconds,
                )
                return
            except TimeoutError:
                tick += 1
            event_id = (
                f"{parsed.execution_id}:Heartbeat:{parsed.attempt}:"
                f"activity-{activity_attempt}:tick-{tick}"
            )
            event = {
                "event_id": event_id,
                "idempotency_key": event_id,
                "event_type": "Heartbeat",
                "stage": heartbeat_details["stage"],
                "attempt": parsed.attempt,
            }
            await _post_periodic_heartbeat(settings, parsed.execution_id, event)

    heartbeat_task = asyncio.create_task(heartbeat_loop(), name="product-agent-control-plane-heartbeat")
    try:
        return await operation
    finally:
        stopped.set()
        if not heartbeat_task.done():
            heartbeat_task.cancel()
        await asyncio.gather(heartbeat_task, return_exceptions=True)


def _activity_attempt() -> int:
    try:
        return activity.info().attempt
    except RuntimeError:
        return 1


async def _post_periodic_heartbeat(settings, execution_id: str, event: dict) -> None:
    """同一 tick 最多重试一次，并复用 eventId 保持回调幂等。"""

    for callback_attempt in range(2):
        try:
            await _post_product_agent_event(settings, execution_id, event)
            return
        except httpx.HTTPError as error:
            if callback_attempt == 1:
                log.warning(
                    "Product Agent 周期心跳回调失败 execution=%s type=%s",
                    execution_id,
                    error.__class__.__name__,
                )


async def _post_product_agent_event(settings, execution_id: str, event: dict) -> None:
    url = (
        settings.control_plane_url.rstrip("/")
        + f"/api/v5/internal/product-agent-executions/{execution_id}/events"
    )
    headers = {"Authorization": f"Bearer {settings.worker_callback_token}"}
    async with httpx.AsyncClient(timeout=30) as client:
        response = await client.post(url, json=event, headers=headers)
        response.raise_for_status()
