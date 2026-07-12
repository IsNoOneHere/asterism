import asyncio
import contextlib
import logging

import typer
from temporalio.client import Client
from temporalio.contrib.pydantic import pydantic_data_converter
from temporalio.worker import Worker

from agent_team_v5.activities.execution import (
    apply_patch_to_repo,
    plan_execution,
    revert_patch,
    run_execution,
    run_release,
    run_validation,
    summarize_repo,
    validate_plan_targets_activity,
)
from agent_team_v5.activities.projections import fetch_context, send_projection_event
from agent_team_v5.config.settings import load_settings
from agent_team_v5.readiness import readiness_loop
from agent_team_v5.workflows.case_lifecycle import AgentTeamV5CaseWorkflow

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
        workflows=[AgentTeamV5CaseWorkflow],
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
            send_projection_event,
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

    log.info("agent-team-v5 worker ok")
