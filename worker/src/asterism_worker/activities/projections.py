import logging

import httpx
from temporalio import activity

from asterism_worker.config.settings import load_settings
from asterism_worker.contracts import ContextSnapshot, ProjectionEvent

log = logging.getLogger(__name__)


class ProjectionClient:
    def __init__(self, control_plane_url: str, worker_callback_token: str) -> None:
        self.control_plane_url = control_plane_url.rstrip("/")
        self.worker_callback_token = worker_callback_token

    async def send(self, event: ProjectionEvent) -> None:
        url = f"{self.control_plane_url}/api/v5/projections"
        headers = {"Authorization": f"Bearer {self.worker_callback_token}"}
        async with httpx.AsyncClient(timeout=10) as client:
            response = await client.post(url, json=event.model_dump(), headers=headers)
            response.raise_for_status()
        log.info("投影回调已发送", extra={"event_type": event.eventType, "work_item_id": event.workItemId})

    async def fetch_context(self, system_id: str, work_item_id: str) -> ContextSnapshot:
        url = f"{self.control_plane_url}/api/v5/context-snapshots"
        headers = {"Authorization": f"Bearer {self.worker_callback_token}"}
        async with httpx.AsyncClient(timeout=10) as client:
            response = await client.post(url, json={"systemId": system_id, "workItemId": work_item_id}, headers=headers)
            response.raise_for_status()
        data = response.json()
        # Java API 使用 camelCase，worker 内部统一转 snake_case。
        return ContextSnapshot(
            system_id=data["systemId"],
            manifest_id=data["manifestId"],
            approved_memories=data.get("approvedMemories", []),
        )


@activity.defn
async def send_projection_event(event: dict) -> None:
    settings = load_settings()
    await ProjectionClient(settings.control_plane_url, settings.worker_callback_token).send(ProjectionEvent.model_validate(event))


@activity.defn
async def fetch_context(request: dict) -> dict:
    settings = load_settings()
    snapshot = await ProjectionClient(settings.control_plane_url, settings.worker_callback_token).fetch_context(
        request["system_id"],
        request["work_item_id"],
    )
    log.info("上下文快照已获取", extra={"manifest_id": snapshot.manifest_id, "work_item_id": request["work_item_id"]})
    return snapshot.model_dump()
