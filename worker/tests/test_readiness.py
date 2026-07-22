import asyncio
import json
import subprocess

from asterism_worker.config.settings import Settings
from asterism_worker import readiness


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
            if "/internal/systems/" in url:
                return Response({
                    "model_profiles": [],
                    "agents": [{"name": "developer", "engine": "claude_sdk_team", "model_profile_ref": ""}],
                })
            return Response({"ready": True, "model": "model-1"})

        async def post(self, url, **kwargs):
            posted.update(kwargs["json"])
            return Response({})

    monkeypatch.setattr(readiness.httpx, "AsyncClient", lambda **_: Client())
    settings = Settings(default_model_api_key="top-secret", default_engine="claude_sdk_team", worker_id="worker-1")

    asyncio.run(readiness.report_readiness(settings))

    assert {"fake", "claude_sdk_team"}.issubset(posted["capabilities"])
    assert posted["targets"][0]["gitRepository"] is True
    assert posted["targets"][0]["configSource"] == "worker_env"
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
            if "/internal/systems/" in url:
                return Response({
                    "model_profiles": [{"id": "mp-1", "model": "deepseek-v4-pro", "api_key": "system-secret"}],
                    "agents": [{"name": "developer", "kind": "builtin", "engine": "claude_sdk_team", "model_profile_ref": "mp-1"}],
                })
            return Response({"ready": True, "model": "business-model"})

        async def post(self, url, **kwargs):
            posted.update(kwargs["json"])
            return Response({})

    monkeypatch.setattr(readiness.httpx, "AsyncClient", lambda **_: Client())
    asyncio.run(readiness.report_readiness(Settings(worker_id="worker-1")))

    assert "claude_sdk_team" in posted["capabilities"]
    assert posted["targets"][0]["claudeSdkTeamReady"] is True
    assert posted["targets"][0]["configSource"] == "system"
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
            if "/internal/systems/" in url:
                return Response({
                    "model_profiles": [{"id": "mp-1", "model": "deepseek-v4-pro", "api_key": ""}],
                    "agents": [{"name": "developer", "kind": "builtin", "engine": "claude_sdk_team", "model_profile_ref": "mp-1"}],
                })
            return Response({"ready": True, "stages": {
                "prd": {"ready": True, "name": "需求模型", "api_key_configured": True},
            }})
        async def post(self, url, **kwargs):
            posted.update(kwargs["json"])
            return Response({})

    monkeypatch.setattr(readiness.httpx, "AsyncClient", lambda **_: Client())
    asyncio.run(readiness.report_readiness(Settings(worker_id="worker-1")))

    target = posted["targets"][0]
    assert target["prdModel"] == "需求模型"
    assert target["claudeSdkTeamReady"] is False
    assert target["configSource"] == "system"
    assert "api_key" not in json.dumps(posted)


def test_model_readiness_sends_internal_token():
    captured = {}

    class Client:
        async def get(self, url, **kwargs):
            captured.update(url=url, **kwargs)
            return Response({"ready": True})

    settings = Settings(
        agent_service_url="http://runner:8090",
        worker_callback_token="internal-token",
    )

    result = asyncio.run(readiness._model_readiness(Client(), settings, "sys-1"))

    assert result["ready"] is True
    assert captured["headers"]["Authorization"] == "Bearer internal-token"
    assert captured["params"] == {"system_id": "sys-1"}
