import asyncio
import json
from types import SimpleNamespace
from uuid import uuid4

from temporalio import activity
from temporalio.client import WorkflowExecutionStatus, WorkflowFailureError
from temporalio.contrib.pydantic import pydantic_data_converter
from temporalio.testing import WorkflowEnvironment
from temporalio.worker import Worker
import pytest

from asterism_worker.activities import product_agent
from asterism_worker.activities.product_agent import (
    generate_product_draft,
    prepare_product_agent_input,
    send_product_agent_event,
)
from asterism_worker.cli.main import REGISTERED_ACTIVITIES, REGISTERED_WORKFLOWS
from asterism_worker.contracts import ProductAgentExecutionInput
from asterism_worker.workflows.product_agent import (
    AsterismProductAgentWorkflow,
    CALLBACK_RETRY_POLICY,
    MODEL_ACTIVITY_RETRY_POLICY,
    MODEL_ACTIVITY_TIMEOUT,
)


def _request(attachment_ids: list[str] | None = None) -> ProductAgentExecutionInput:
    return ProductAgentExecutionInput(
        execution_id="exec-1",
        workflow_id="product-agent-exec-1",
        system_id="sys-1",
        prd_id="prd-1",
        conversation_id="conv-1",
        input_message_id="msg-1",
        context_bundle_id="bundle-1",
        content="补充业务目标",
        attachment_ids=attachment_ids or [],
        current_draft={"title": "标题", "acceptanceCriteria": []},
        missing_fields=["goal"],
        conversation_history=[{"sender_type": "user", "content": "上一轮"}],
        context_items=[{"refId": "MEM:1", "type": "memory", "content": "上下文"}],
        attempt=2,
    )


def test_product_workflow_and_activities_are_registered_with_long_model_timeout():
    assert AsterismProductAgentWorkflow in REGISTERED_WORKFLOWS
    assert {prepare_product_agent_input, generate_product_draft, send_product_agent_event} <= set(
        REGISTERED_ACTIVITIES,
    )
    assert MODEL_ACTIVITY_TIMEOUT.total_seconds() > 120
    assert MODEL_ACTIVITY_RETRY_POLICY.maximum_attempts == 3
    assert CALLBACK_RETRY_POLICY.maximum_attempts == 0


def test_prepare_preserves_execution_and_attachment_contract():
    prepared = asyncio.run(prepare_product_agent_input(_request(["att-1"]).model_dump()))

    assert prepared["execution_id"] == "exec-1"
    assert prepared["attachment_ids"] == ["att-1"]
    assert prepared["current_draft"]["acceptanceCriteria"] == []
    assert prepared["missing_fields"] == ["goal"]


def test_generate_uses_runner_timeout_and_periodic_heartbeat(monkeypatch):
    captured = {"control_plane_heartbeats": []}

    class Response:
        def raise_for_status(self):
            return None

        def json(self):
            return {
                "patch": {"goal": "明确业务目标"},
                "assistant_message": "已补充",
                "citations": {},
            }

    class Client:
        def __init__(self, timeout):
            captured["timeout"] = timeout

        async def __aenter__(self):
            return self

        async def __aexit__(self, *_args):
            return None

        async def post(self, url, json, headers):
            captured.update(url=url, json=json, headers=headers)
            await asyncio.sleep(0.035)
            return Response()

    async def heartbeat(operation, details, interval_seconds):
        captured.update(details=details, interval_seconds=interval_seconds)
        return await operation

    async def post_execution_heartbeat(_settings, execution_id, event):
        captured["control_plane_heartbeats"].append((execution_id, dict(event)))

    monkeypatch.setattr(product_agent.httpx, "AsyncClient", Client)
    monkeypatch.setattr(product_agent, "run_with_periodic_heartbeat", heartbeat)
    monkeypatch.setattr(product_agent, "_post_product_agent_event", post_execution_heartbeat)
    monkeypatch.setattr(product_agent, "load_settings", lambda: SimpleNamespace(
        agent_service_url="http://runner:8090",
        control_plane_url="http://control-plane:8085",
        worker_callback_token="token",
        product_agent_http_timeout_seconds=660,
        activity_heartbeat_interval_seconds=0.01,
    ))

    result = asyncio.run(generate_product_draft(asyncio.run(
        prepare_product_agent_input(_request().model_dump()),
    )))

    assert result["result"]["assistant_message"] == "已补充"
    assert result["observations"] == []
    assert result["image_analysis_failed"] is False
    assert captured["timeout"].read == 660
    assert captured["url"] == "http://runner:8090/prd-draft"
    assert captured["headers"] == {"Authorization": "Bearer token"}
    assert "execution_id" not in captured["json"]
    assert captured["details"] == {"execution_id": "exec-1", "stage": "GENERATING_RESPONSE"}
    assert captured["interval_seconds"] == 0.01
    heartbeats = [event for _, event in captured["control_plane_heartbeats"]]
    assert len(heartbeats) >= 2
    assert all(event["stage"] == "GENERATING_RESPONSE" for event in heartbeats)
    assert all(":activity-1:tick-" in event["event_id"] for event in heartbeats)
    assert all(event["idempotency_key"] == event["event_id"] for event in heartbeats)


def test_attachment_success_adds_observation_without_returning_image_bytes(monkeypatch):
    captured = {"draft": None, "image_seen": False}

    class Response:
        def __init__(self, body, *, content=b"", content_type="application/json", status=200):
            self.body = body
            self.content = content
            self.headers = {"content-type": content_type}
            self.status = status

        def raise_for_status(self):
            if self.status >= 400:
                request = product_agent.httpx.Request("GET", "http://test")
                raise product_agent.httpx.HTTPStatusError(
                    "failed", request=request, response=product_agent.httpx.Response(self.status),
                )

        def json(self):
            return self.body

    class Client:
        def __init__(self, timeout):
            captured["timeout"] = timeout

        async def __aenter__(self):
            return self

        async def __aexit__(self, *_args):
            return None

        async def get(self, url, params, headers):
            captured["attachment_url"] = url
            captured["attachment_params"] = params
            return Response({}, content=b"image-secret", content_type="image/png")

        async def post(self, url, headers, json=None, params=None, content=None):
            if url.endswith("/analyze-image"):
                captured["image_seen"] = content == b"image-secret"
                captured["image_headers"] = headers
                return Response({
                    "page_title": "订单页",
                    "text_anchors": ["提交订单"],
                    "ui_elements": [{"type": "button", "description": "提交"}],
                    "error_messages": [],
                    "user_visible_summary": "订单提交页面",
                })
            captured["draft"] = json
            return Response({
                "patch": {"goal": "明确业务目标"},
                "assistant_message": "已补充",
                "citations": {},
            })

    monkeypatch.setattr(product_agent.httpx, "AsyncClient", Client)
    monkeypatch.setattr(product_agent, "load_settings", lambda: _settings())

    prepared = asyncio.run(prepare_product_agent_input(_request(["att-1"]).model_dump()))
    result = asyncio.run(generate_product_draft(prepared))

    assert captured["attachment_params"] == {"systemId": "sys-1"}
    assert captured["image_seen"] is True
    assert captured["image_headers"]["Content-Type"] == "image/png"
    assert captured["draft"]["content"].startswith("补充业务目标")
    assert "订单提交页面" in captured["draft"]["content"]
    assert "订单页、提交订单" in captured["draft"]["content"]
    assert "attachment_ids" not in captured["draft"]
    assert result["observations"][0]["page_title"] == "订单页"
    assert result["image_analysis_failed"] is False
    assert "image-secret" not in json.dumps(result, ensure_ascii=False)


def test_attachment_failure_downgrades_to_text_and_continues_draft(monkeypatch):
    captured = {}

    class Response:
        content = b""
        headers = {"content-type": "image/png"}

        def __init__(self, body=None, status=200):
            self.body = body or {}
            self.status = status

        def raise_for_status(self):
            if self.status >= 400:
                request = product_agent.httpx.Request("GET", "http://test")
                raise product_agent.httpx.HTTPStatusError(
                    "failed", request=request, response=product_agent.httpx.Response(self.status),
                )

        def json(self):
            return self.body

    class Client:
        def __init__(self, timeout):
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, *_args):
            return None

        async def get(self, url, params, headers):
            return Response(status=404)

        async def post(self, url, headers, json=None, params=None, content=None):
            captured["draft"] = json
            return Response({
                "patch": {"goal": "仅按文字处理"},
                "assistant_message": "已处理文字",
                "citations": {},
            })

    monkeypatch.setattr(product_agent.httpx, "AsyncClient", Client)
    monkeypatch.setattr(product_agent, "load_settings", lambda: _settings())

    prepared = asyncio.run(prepare_product_agent_input(_request(["att-broken"]).model_dump()))
    result = asyncio.run(generate_product_draft(prepared))

    assert captured["draft"]["content"] == "补充业务目标"
    assert result["result"]["assistant_message"] == "已处理文字"
    assert result["observations"] == []
    assert result["image_analysis_failed"] is True


def test_workflow_retries_model_and_completed_callback_with_deterministic_event_id():
    result, events, model_calls, callback_attempts = asyncio.run(_run_workflow(
        model_failures=2,
        completed_callback_failures=2,
    ))

    assert result == "COMPLETED"
    assert model_calls == 3
    assert [event["event_type"] for event in events] == [
        "Started", "Heartbeat", "Heartbeat", "Completed",
    ]
    completed_ids = [
        event["event_id"] for event in callback_attempts if event["event_type"] == "Completed"
    ]
    assert completed_ids == ["exec-1:Completed:2:3"] * 3
    assert events[-1]["idempotency_key"] == events[-1]["event_id"]


def test_workflow_emits_failed_only_after_model_retry_exhaustion():
    result, events, model_calls, _ = asyncio.run(_run_workflow(model_failures=3))

    assert result == "FAILED"
    assert model_calls == 3
    assert events[-1]["event_type"] == "Failed"
    assert events[-1]["failure_code"] == "PRODUCT_AGENT_FAILED"


def test_workflow_completed_maps_observations_and_degradation_flag():
    result, events, model_calls, _ = asyncio.run(_run_workflow(attachment_ids=["att-1"]))

    assert result == "COMPLETED"
    assert model_calls == 1
    assert events[-1]["observations"][0]["page_title"] == "订单页"
    assert events[-1]["image_analysis_failed"] is True


def test_cancelled_workflow_best_effort_projects_cancelled_event():
    events = asyncio.run(_run_cancelled_workflow())

    assert events[-1]["event_type"] == "Cancelled"
    assert events[-1]["event_id"] == "exec-1:Cancelled:2:3"


async def _run_workflow(
    model_failures: int = 0,
    completed_callback_failures: int = 0,
    attachment_ids: list[str] | None = None,
) -> tuple[str, list[dict], int, list[dict]]:
    events: list[dict] = []
    callback_attempts: list[dict] = []
    counters = {"model": 0, "completed_callback": 0}

    @activity.defn(name="generate_product_draft")
    async def generate(_request: dict) -> dict:
        counters["model"] += 1
        if counters["model"] <= model_failures:
            raise RuntimeError("transient model failure")
        return {
            "result": {
                "patch": {"goal": "明确业务目标"},
                "assistant_message": "已补充",
                "citations": {},
            },
            "observations": ([{"page_title": "订单页"}] if attachment_ids else []),
            "image_analysis_failed": bool(attachment_ids),
        }

    @activity.defn(name="send_product_agent_event")
    async def send(request: dict) -> None:
        event = dict(request["event"])
        callback_attempts.append(event)
        if event["event_type"] == "Completed":
            counters["completed_callback"] += 1
            if counters["completed_callback"] <= completed_callback_failures:
                raise RuntimeError("transient callback failure")
        events.append(event)

    async with await WorkflowEnvironment.start_time_skipping(
        data_converter=pydantic_data_converter,
    ) as environment:
        task_queue = f"product-agent-{uuid4()}"
        async with Worker(
            environment.client,
            task_queue=task_queue,
            workflows=[AsterismProductAgentWorkflow],
            activities=[prepare_product_agent_input, generate, send],
        ):
            result = await environment.client.execute_workflow(
                AsterismProductAgentWorkflow.run,
                _request(attachment_ids),
                id=f"product-agent-{uuid4()}",
                task_queue=task_queue,
            )
    return result, events, counters["model"], callback_attempts


async def _run_cancelled_workflow() -> list[dict]:
    events: list[dict] = []
    model_started = asyncio.Event()

    @activity.defn(name="generate_product_draft")
    async def generate(_request: dict) -> dict:
        model_started.set()
        await activity.wait_for_cancelled()
        raise asyncio.CancelledError

    @activity.defn(name="send_product_agent_event")
    async def send(request: dict) -> None:
        events.append(dict(request["event"]))

    async with await WorkflowEnvironment.start_time_skipping(
        data_converter=pydantic_data_converter,
    ) as environment:
        task_queue = f"product-agent-cancel-{uuid4()}"
        async with Worker(
            environment.client,
            task_queue=task_queue,
            workflows=[AsterismProductAgentWorkflow],
            activities=[prepare_product_agent_input, generate, send],
        ):
            handle = await environment.client.start_workflow(
                AsterismProductAgentWorkflow.run,
                _request(),
                id=f"product-agent-cancel-{uuid4()}",
                task_queue=task_queue,
            )
            await asyncio.wait_for(model_started.wait(), timeout=2)
            await handle.cancel()
            with pytest.raises(WorkflowFailureError):
                await handle.result()
            assert (await handle.describe()).status == WorkflowExecutionStatus.CANCELED, events
    return events


def _settings():
    return SimpleNamespace(
        agent_service_url="http://runner:8090",
        control_plane_url="http://control-plane:8085",
        worker_callback_token="token",
        product_agent_http_timeout_seconds=660,
        activity_heartbeat_interval_seconds=30,
    )
