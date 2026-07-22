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

    async def fetch_context(self, request: dict) -> ContextSnapshot:
        url = f"{self.control_plane_url}/api/v5/context-snapshots"
        headers = {"Authorization": f"Bearer {self.worker_callback_token}"}
        async with httpx.AsyncClient(timeout=10) as client:
            response = await client.post(url, json={
                "systemId": request["system_id"],
                "prdId": request["prd_id"],
                "workItemId": request["work_item_id"],
                "requirementManifestId": request["requirement_manifest_id"],
                "goal": request.get("goal", ""),
                "draft": request.get("draft", {}),
            }, headers=headers)
            response.raise_for_status()
        data = response.json()
        # Java API 使用 camelCase，worker 内部统一转 snake_case。
        return ContextSnapshot(
            system_id=data["systemId"],
            requirement_manifest_id=data["requirementManifestId"],
            requirement_items=data.get("requirementItems", []),
            execution_bundle_id=data.get("executionBundleId") or "",
            execution_items=data.get("executionItems", []),
            stale_references=data.get("staleReferences", []),
        )


@activity.defn
async def send_projection_event(event: dict) -> None:
    settings = load_settings()
    await ProjectionClient(settings.control_plane_url, settings.worker_callback_token).send(ProjectionEvent.model_validate(event))


@activity.defn
async def fetch_context(request: dict) -> dict:
    settings = load_settings()
    snapshot = await ProjectionClient(settings.control_plane_url, settings.worker_callback_token).fetch_context(request)
    log.info("上下文快照已获取", extra={
        "requirement_manifest_id": snapshot.requirement_manifest_id,
        "execution_bundle_id": snapshot.execution_bundle_id,
        "work_item_id": request["work_item_id"],
    })
    return snapshot.model_dump()
