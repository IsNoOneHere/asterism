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

        target_reports = []
        for target in targets:
            repo_path = str(target.get("repoPath", ""))
            repos = target.get("repos") or [{"cloneMode": "local", "localPath": repo_path}]
            local_paths = [str(repo.get("localPath", "")) for repo in repos if repo.get("cloneMode") != "gitlab"]
            model = await _model_readiness(client, settings, str(target["systemId"]))
            agents = await _agent_readiness(client, settings, str(target["systemId"]))
            prd = model.get("stages", {}).get("prd", {})
            target_reports.append({
                "systemId": target["systemId"],
                "repositoryAccessible": all(Path(path).is_dir() for path in local_paths),
                "gitRepository": all(_is_git_repository(path) for path in local_paths),
                "prdModelReady": bool(prd.get("ready", model.get("ready"))),
                "prdModel": str(prd.get("name") or prd.get("model", model.get("model", ""))),
                "claudeSdkTeamReady": agents["ready"],
                "claudeSdkTeamModel": agents["model"],
                "configSource": agents["source"],
            })

        capabilities = ["fake"]
        if importlib.util.find_spec("claude_agent_sdk"):
            capabilities.append("claude_sdk_team")
        payload = {
            "workerId": settings.worker_id,
            "taskQueue": settings.temporal_task_queue,
            "defaultExecutionProvider": settings.default_engine,
            "capabilities": capabilities,
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
        agents = [item for item in data.get("agents", []) if isinstance(item, dict)]
        developer = next((item for item in agents if item.get("name") == "developer"), {})

        def configured(engine: str) -> tuple[bool, str]:
            if developer.get("engine") != engine:
                return False, ""
            profile = profiles.get(str(developer.get("model_profile_ref", "")), {})
            if profile.get("model") and profile.get("api_key"):
                return True, str(profile.get("model"))
            if not developer.get("model_profile_ref") and settings.default_model_api_key:
                return True, settings.default_model
            return False, ""

        ready, model = configured("claude_sdk_team")
        return {
            "ready": ready,
            "model": model,
            "source": "system" if developer.get("model_profile_ref") else "worker_env",
        }
    except httpx.HTTPError:
        # 心跳失败不能泄露配置或影响 Worker 主轮询。
        return {"ready": False, "model": "", "source": "unconfigured"}


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
