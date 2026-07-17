import asyncio
import contextlib
import logging

import typer
from temporalio.client import Client
from temporalio.contrib.pydantic import pydantic_data_converter
from temporalio.worker import Worker

from asterism_worker.activities.execution import (
    apply_patch_to_repo,
    plan_execution,
    revert_patch,
    run_execution,
    run_release,
    run_validation,
    summarize_repo,
    validate_plan_targets_activity,
)
from asterism_worker.activities.projections import fetch_context, send_projection_event
from asterism_worker.activities.gitlab import check_merge_requests, publish_merge_request, ready_merge_requests
from asterism_worker.activities.route_index import index_system_routes, send_knowledge_candidates
from asterism_worker.config.settings import load_settings
from asterism_worker.readiness import readiness_loop
from asterism_worker.workflows.case_lifecycle import AsterismCaseWorkflow
from asterism_worker.workflows.route_index import AsterismRouteIndexWorkflow

app = typer.Typer()
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger(__name__)


@app.command()
def worker() -> None:
    """启动 Temporal worker。"""

    asyncio.run(_worker())


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
        workflows=[AsterismCaseWorkflow, AsterismRouteIndexWorkflow],
        activities=[
            fetch_context,
            summarize_repo,
            plan_execution,
            validate_plan_targets_activity,
            run_execution,
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
        ],
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
