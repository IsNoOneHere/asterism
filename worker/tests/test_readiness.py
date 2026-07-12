import asyncio
import json
import subprocess

from agent_team_v5.config.settings import Settings
from agent_team_v5 import readiness


class Response:
    def __init__(self, data, status_code=200):
        self.data = data
        self.status_code = status_code

    def json(self):
        return self.data

    def raise_for_status(self):
        if self.status_code >= 400:
            raise RuntimeError(self.status_code)


def test_report_contains_capabilities_but_never_secret(monkeypatch, tmp_path):
    subprocess.run(["git", "init", "-q", str(tmp_path)], check=True)
    posted = {}

    class Client:
        async def __aenter__(self):
            return self

        async def __aexit__(self, *_):
            return None

        async def get(self, url, **kwargs):
            if url.endswith("execution-targets"):
                return Response([{"systemId": "sys-1", "repoPath": str(tmp_path)}])
            if url.endswith("healthz"):
                return Response({"ok": True})
            return Response({"ready": True, "model": "model-1"})

        async def post(self, url, **kwargs):
            posted.update(kwargs["json"])
            return Response({})

    monkeypatch.setattr(readiness.httpx, "AsyncClient", lambda **_: Client())
    settings = Settings(default_model_api_key="top-secret", default_engine="claude_sdk", worker_id="worker-1")

    asyncio.run(readiness.report_readiness(settings))

    assert {"fake", "http", "claude_sdk"}.issubset(posted["capabilities"])
    assert posted["targets"][0]["gitRepository"] is True
    assert posted["targets"][0]["claudeConfigSource"] == "worker_env"
    assert "top-secret" not in json.dumps(posted)


def test_report_uses_system_claude_config_without_uploading_key(monkeypatch, tmp_path):
    subprocess.run(["git", "init", "-q", str(tmp_path)], check=True)
    posted = {}

    class Client:
        async def __aenter__(self):
            return self

        async def __aexit__(self, *_):
            return None

        async def get(self, url, **kwargs):
            if url.endswith("execution-targets"):
                return Response([{"systemId": "sys-1", "repoPath": str(tmp_path)}])
            if url.endswith("healthz"):
                return Response({"ok": True})
            if "/internal/systems/" in url:
                return Response({
                    "model_profiles": [{"id": "mp-1", "model": "deepseek-v4-pro", "api_key": "system-secret"}],
                    "agent_roles": [{"id": "role-1", "engine": "claude_sdk", "model_profile_ref": "mp-1"}],
                })
            return Response({"ready": True, "model": "business-model"})

        async def post(self, url, **kwargs):
            posted.update(kwargs["json"])
            return Response({})

    monkeypatch.setattr(readiness.httpx, "AsyncClient", lambda **_: Client())
    asyncio.run(readiness.report_readiness(Settings(worker_id="worker-1")))

    assert "claude_sdk" in posted["capabilities"]
    assert posted["targets"][0]["claudeReady"] is True
    assert posted["targets"][0]["claudeConfigSource"] == "system"
    assert "system-secret" not in json.dumps(posted)


def test_report_contains_stage_models_without_keys(monkeypatch, tmp_path):
    subprocess.run(["git", "init", "-q", str(tmp_path)], check=True)
    posted = {}

    class Client:
        async def __aenter__(self): return self
        async def __aexit__(self, *_): return None
        async def get(self, url, **kwargs):
            if url.endswith("execution-targets"):
                return Response([{"systemId": "sys-1", "repoPath": str(tmp_path)}])
            if url.endswith("healthz"):
                return Response({"ok": True})
            if "/internal/systems/" in url:
                return Response({
                    "model_profiles": [{"id": "mp-1", "model": "deepseek-v4-pro", "api_key": ""}],
                    "agent_roles": [{"id": "role-1", "engine": "claude_sdk", "model_profile_ref": "mp-1"}],
                })
            return Response({"ready": True, "stages": {
                "prd": {"ready": True, "name": "需求模型", "api_key_configured": True},
                "planning": {"ready": True, "name": "规划模型", "api_key_configured": True},
                "diff": {"ready": True, "name": "Diff 模型", "api_key_configured": True},
            }})
        async def post(self, url, **kwargs):
            posted.update(kwargs["json"])
            return Response({})

    monkeypatch.setattr(readiness.httpx, "AsyncClient", lambda **_: Client())
    asyncio.run(readiness.report_readiness(Settings(worker_id="worker-1")))

    target = posted["targets"][0]
    assert (target["prdModel"], target["planningModel"], target["diffModel"]) == ("需求模型", "规划模型", "Diff 模型")
    assert target["claudeReady"] is False
    assert target["claudeConfigSource"] == "system"
    assert "api_key" not in json.dumps(posted)
