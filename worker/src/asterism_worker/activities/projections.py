import logging

import httpx
from temporalio import activity

from asterism_worker.config.settings import load_settings
from asterism_worker.contracts import ArtifactRef, ContextSnapshot, ProjectionEvent, ProjectionResult

log = logging.getLogger(__name__)


class ProjectionClient:
    def __init__(self, control_plane_url: str, worker_callback_token: str) -> None:
        self.control_plane_url = control_plane_url.rstrip("/")
        self.worker_callback_token = worker_callback_token

    async def send(self, event: ProjectionEvent) -> ProjectionResult:
        url = f"{self.control_plane_url}/api/v5/projections"
        headers = {"Authorization": f"Bearer {self.worker_callback_token}"}
        async with httpx.AsyncClient(timeout=10) as client:
            response = await client.post(
                url, json=event.model_dump(by_alias=True, exclude_none=True), headers=headers,
            )
            response.raise_for_status()
        log.info("投影回调已发送", extra={
            "event_type": event.event_type, "work_item_id": event.work_item_id,
        })
        return ProjectionResult.model_validate(response.json())

    async def fetch_context(self, request: dict) -> ContextSnapshot:
        url = f"{self.control_plane_url}/api/v5/context-snapshots"
        headers = {"Authorization": f"Bearer {self.worker_callback_token}"}
        async with httpx.AsyncClient(timeout=10) as client:
            response = await client.post(url, json={
                "systemId": request["system_id"],
                "prdId": request["prd_id"],
                "workItemId": request["work_item_id"],
                "requirementManifestId": request["requirement_manifest_id"],
                "phase": request["phase"],
                "productArtifact": ArtifactRef.model_validate(
                    request["product_artifact"],
                ).model_dump(by_alias=True),
                "planningArtifact": _artifact_ref_payload(request.get("planning_artifact")),
                "previousArtifact": _artifact_ref_payload(request.get("previous_artifact")),
                "gitBaseRevisions": request.get("git_base_revisions", {}),
            }, headers=headers)
            response.raise_for_status()
        return ContextSnapshot.model_validate(response.json())


def _artifact_ref_payload(value: object) -> dict | None:
    if value is None:
        return None
    return ArtifactRef.model_validate(value).model_dump(by_alias=True)


@activity.defn
async def send_projection_event(event: dict) -> dict:
    settings = load_settings()
    result = await ProjectionClient(
        settings.control_plane_url, settings.worker_callback_token,
    ).send(ProjectionEvent.model_validate(event))
    return result.model_dump()


@activity.defn
async def fetch_context(request: dict) -> dict:
    settings = load_settings()
    snapshot = await ProjectionClient(settings.control_plane_url, settings.worker_callback_token).fetch_context(request)
    log.info("上下文快照已获取", extra={
        "requirement_manifest_id": snapshot.requirement_manifest_id,
        "execution_bundle_id": snapshot.execution_bundle_id,
        "product_artifact_id": snapshot.product_artifact.artifact_id,
        "planning_artifact_id": (
            snapshot.planning_artifact.artifact_id if snapshot.planning_artifact else ""
        ),
        "work_item_id": request["work_item_id"],
    })
    return snapshot.model_dump()
