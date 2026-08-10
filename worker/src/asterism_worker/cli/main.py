import asyncio
import contextlib
import logging

import typer
import uvicorn
from agent_service.app import create_app
from temporalio.client import Client
from temporalio.contrib.pydantic import pydantic_data_converter
from temporalio.worker import Worker

from asterism_worker.activities.execution import (
    apply_patch_to_repo,
    capture_case_revisions,
    generate_coding_plan,
    revert_patch,
    run_coding_attempt,
    run_release,
    run_validation,
)
from asterism_worker.activities.projections import fetch_context, send_projection_event
from asterism_worker.activities.gitlab import check_merge_requests, publish_merge_request, ready_merge_requests
from asterism_worker.activities.product_agent import (
    generate_product_draft,
    prepare_product_agent_input,
    send_product_agent_event,
)
from asterism_worker.activities.route_index import index_system_routes, send_knowledge_candidates
from asterism_worker.config.settings import load_settings
from asterism_worker.readiness import readiness_loop
from asterism_worker.workflows.lifecycle import AsterismCaseWorkflow
from asterism_worker.workflows.product_agent import AsterismProductAgentWorkflow
from asterism_worker.workflows.route_index import AsterismRouteIndexWorkflow

app = typer.Typer()
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger(__name__)

REGISTERED_WORKFLOWS = [
    AsterismCaseWorkflow,
    AsterismRouteIndexWorkflow,
    AsterismProductAgentWorkflow,
]
REGISTERED_ACTIVITIES = [
    capture_case_revisions,
    fetch_context,
    generate_coding_plan,
    run_coding_attempt,
    apply_patch_to_repo,
    run_release,
    revert_patch,
    run_validation,
    publish_merge_request,
    check_merge_requests,
    ready_merge_requests,
    send_projection_event,
    index_system_routes,
    send_knowledge_candidates,
    prepare_product_agent_input,
    generate_product_draft,
    send_product_agent_event,
]


@app.command()
def worker() -> None:
    """启动 Temporal worker。"""

    asyncio.run(_worker())


@app.command()
def runner() -> None:
    """在同一事件循环内启动内部模型 HTTP 服务和 Temporal Worker。"""

    asyncio.run(_runner())


async def _runner() -> None:
    server = uvicorn.Server(uvicorn.Config(
        create_app(), host="0.0.0.0", port=8090, log_level="info", access_log=False,
    ))
    http_task = asyncio.create_task(server.serve(), name="runner-http")
    worker_task = asyncio.create_task(_worker(), name="runner-temporal-worker")
    log.info("Asterism Runner 启动", extra={"http_port": 8090})
    try:
        done, _ = await asyncio.wait({http_task, worker_task}, return_when=asyncio.FIRST_COMPLETED)
        for task in done:
            task.result()
        # 任一子服务意外结束都退出 Runner，交给容器重启策略恢复整体服务。
        if http_task in done and not server.should_exit:
            raise RuntimeError("Runner HTTP 服务意外停止")
        if worker_task in done and not server.should_exit:
            raise RuntimeError("Temporal Worker 意外停止")
    finally:
        server.should_exit = True
        for task in (http_task, worker_task):
            if not task.done():
                task.cancel()
        await asyncio.gather(http_task, worker_task, return_exceptions=True)


async def _worker() -> None:
    settings = load_settings()
    client = await Client.connect(
        settings.temporal_target,
        namespace=settings.temporal_namespace,
        data_converter=pydantic_data_converter,
    )
    log.info("Temporal worker 启动", extra={"task_queue": settings.temporal_task_queue})
    worker = Worker(
        client,
        task_queue=settings.temporal_task_queue,
        workflows=REGISTERED_WORKFLOWS,
        activities=REGISTERED_ACTIVITIES,
    )
    heartbeat = asyncio.create_task(readiness_loop(settings))
    try:
        await worker.run()
    finally:
        heartbeat.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await heartbeat


@app.command()
def self_check() -> None:
    """最小 CLI 自检，不连接外部服务。"""

    log.info("asterism worker ok")
