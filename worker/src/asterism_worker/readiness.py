import asyncio
from datetime import datetime, timezone
import importlib.util
import logging
from pathlib import Path
import subprocess

import httpx

from asterism_worker.config.settings import Settings


log = logging.getLogger(__name__)


async def readiness_loop(settings: Settings) -> None:
    """周期上报 Worker 能力；控制面短暂不可用不能影响 Temporal 轮询。"""

    while True:
        try:
            await report_readiness(settings)
        except Exception as error:  # noqa: BLE001 - 心跳失败必须被隔离
            log.warning("Worker readiness 上报失败: %s", error)
        await asyncio.sleep(settings.readiness_interval_seconds)


async def report_readiness(settings: Settings) -> None:
    headers = {"Authorization": f"Bearer {settings.worker_callback_token}"}
    async with httpx.AsyncClient(timeout=5) as client:
        targets_response = await client.get(
            settings.control_plane_url.rstrip("/") + "/api/v5/internal/execution-targets",
            headers=headers,
        )
        targets_response.raise_for_status()
        targets = targets_response.json()

        http_ready = await _reachable(client, settings.agent_service_url.rstrip("/") + "/healthz")
        target_reports = []
        for target in targets:
            repo_path = str(target.get("repoPath", ""))
            model = await _model_readiness(client, settings, str(target["systemId"]))
            agents = await _agent_readiness(client, settings, str(target["systemId"]))
            stages = model.get("stages", {})
            prd = stages.get("prd", {})
            planning = stages.get("planning", {})
            diff = stages.get("diff", {})
            target_reports.append({
                "systemId": target["systemId"],
                "repositoryAccessible": Path(repo_path).is_dir(),
                "gitRepository": _is_git_repository(repo_path),
                "modelReady": bool(model.get("ready")),
                "model": str(prd.get("model", model.get("model", ""))),
                "prdModelReady": bool(prd.get("ready", model.get("ready"))),
                "prdModel": str(prd.get("name") or prd.get("model", model.get("model", ""))),
                "planningModelReady": bool(planning.get("ready", model.get("ready"))),
                "planningModel": str(planning.get("name") or planning.get("model", model.get("model", ""))),
                "diffModelReady": bool(diff.get("ready", model.get("ready"))),
                "diffModel": str(diff.get("name") or diff.get("model", model.get("model", ""))),
                "claudeReady": agents["claude_ready"],
                "claudeModel": agents["claude_model"],
                "claudeConfigSource": agents["source"],
                "deepagentsReady": agents["deepagents_ready"],
                "deepagentsModel": agents["deepagents_model"],
            })

        capabilities = ["fake"]
        if http_ready:
            capabilities.append("http")
        if importlib.util.find_spec("claude_agent_sdk"):
            capabilities.append("claude_sdk")
        if importlib.util.find_spec("deepagents"):
            capabilities.append("deepagents")
        payload = {
            "workerId": settings.worker_id,
            "taskQueue": settings.temporal_task_queue,
            "defaultExecutionProvider": settings.default_engine,
            "capabilities": capabilities,
            "httpProviderReachable": http_ready,
            "releasePush": settings.release_push,
            "checkedAt": datetime.now(timezone.utc).isoformat(),
            "targets": target_reports,
        }
        response = await client.post(
            settings.control_plane_url.rstrip("/") + "/api/v5/internal/worker-readiness",
            headers=headers,
            json=payload,
        )
        response.raise_for_status()
        log.info("Worker readiness 已上报，系统数=%s capabilities=%s", len(target_reports), capabilities)


async def _reachable(client: httpx.AsyncClient, url: str) -> bool:
    try:
        return (await client.get(url)).status_code == 200
    except httpx.HTTPError:
        return False


async def _model_readiness(client: httpx.AsyncClient, settings: Settings, system_id: str) -> dict:
    try:
        response = await client.get(
            settings.agent_service_url.rstrip("/") + "/readiness",
            params={"system_id": system_id},
        )
        response.raise_for_status()
        return response.json()
    except httpx.HTTPError:
        return {"ready": False, "model": ""}


async def _agent_readiness(client: httpx.AsyncClient, settings: Settings, system_id: str) -> dict:
    try:
        response = await client.get(
            settings.control_plane_url.rstrip("/") + f"/api/v5/internal/systems/{system_id}/model-config",
            headers={"Authorization": f"Bearer {settings.worker_callback_token}"},
        )
        response.raise_for_status()
        data = response.json()
        profiles = {str(item.get("id")): item for item in data.get("model_profiles", []) if isinstance(item, dict)}
        roles = [item for item in data.get("agent_roles", []) if isinstance(item, dict)]

        def configured(engine: str) -> tuple[bool, str]:
            for role in roles:
                if role.get("engine") != engine:
                    continue
                profile = profiles.get(str(role.get("model_profile_ref", "")), {})
                if profile.get("model") and profile.get("api_key"):
                    return True, str(profile.get("model"))
            return False, ""

        claude_ready, claude_model = configured("claude_sdk")
        deep_ready, deep_model = configured("deepagents")
        if not roles and settings.default_model_api_key:
            claude_ready = settings.default_engine == "claude_sdk"
            deep_ready = settings.default_engine == "deepagents"
            claude_model = settings.default_model if claude_ready else ""
            deep_model = settings.default_model if deep_ready else ""
        return {
            "claude_ready": claude_ready,
            "claude_model": claude_model,
            "deepagents_ready": deep_ready,
            "deepagents_model": deep_model,
            "source": "system" if roles else "worker_env",
        }
    except httpx.HTTPError:
        # 心跳失败不能泄露配置或影响 Worker 主轮询。
        return {"claude_ready": False, "claude_model": "", "deepagents_ready": False,
                "deepagents_model": "", "source": "unconfigured"}


def _is_git_repository(repo_path: str) -> bool:
    if not repo_path or not Path(repo_path).is_dir():
        return False
    result = subprocess.run(
        ["git", "-C", repo_path, "rev-parse", "--is-inside-work-tree"],
        text=True,
        capture_output=True,
        timeout=5,
    )
    return result.returncode == 0 and result.stdout.strip() == "true"
