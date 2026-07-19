import asyncio

import httpx
import pytest

from asterism_worker.agent_config import resolve_agent_config
from asterism_worker.config.settings import Settings
from asterism_worker.contracts import AgentConfigSnapshot


def test_snapshot_resolves_developer_profile_key_from_internal_api():
    snapshot = AgentConfigSnapshot.model_validate({
        "model_profiles": [{
            "id": "mp-worker", "provider": "anthropic", "model": "deepseek-v4-pro",
            "base_url": "https://api.example/anthropic",
        }],
        "agents": [{
            "name": "developer", "kind": "builtin", "engine": "claude_sdk_team",
            "model_profile_ref": "mp-worker", "path_scope": ["src"], "max_turns": 12,
        }],
    })

    async def resolve():
        transport = httpx.MockTransport(lambda request: httpx.Response(200, json={"api_key": "secret"}))
        async with httpx.AsyncClient(transport=transport) as client:
            return await resolve_agent_config(Settings(), "sys-1", snapshot=snapshot, client=client)

    result = asyncio.run(resolve())
    assert result.engine.name == "claude_sdk_team"
    assert result.engine.max_turns == 12
    assert result.model_profile.api_key == "secret"
    assert result.constraints.path_scope == ("src",)


def test_live_config_uses_only_builtin_developer():
    payload = {
        "model_profiles": [{
            "id": "mp-worker", "provider": "anthropic", "model": "claude-sonnet",
            "base_url": "https://api.example", "api_key": "secret",
        }],
        "agents": [
            {"name": "product", "kind": "builtin", "model_profile_ref": ""},
            {"name": "developer", "kind": "builtin", "engine": "claude_sdk_team",
             "model_profile_ref": "mp-worker", "timeout_seconds": 300},
        ],
    }

    async def resolve():
        transport = httpx.MockTransport(lambda request: httpx.Response(200, json=payload))
        async with httpx.AsyncClient(transport=transport) as client:
            return await resolve_agent_config(Settings(), "sys-1", client=client)

    result = asyncio.run(resolve())
    assert result.engine.name == "claude_sdk_team"
    assert result.engine.timeout_seconds == 300
    assert result.model_profile.model == "claude-sonnet"


def test_missing_developer_fails_fast():
    async def resolve():
        transport = httpx.MockTransport(lambda request: httpx.Response(200, json={
            "agents": [{"name": "product", "kind": "builtin"}],
        }))
        async with httpx.AsyncClient(transport=transport) as client:
            return await resolve_agent_config(Settings(), "sys-1", client=client)

    with pytest.raises(RuntimeError, match="developer"):
        asyncio.run(resolve())


def test_unconfigured_local_worker_uses_environment_defaults():
    async def resolve():
        transport = httpx.MockTransport(lambda request: httpx.Response(404))
        async with httpx.AsyncClient(transport=transport) as client:
            return await resolve_agent_config(
                Settings(default_engine="fake", default_model="local-model", default_model_api_key="local-key"),
                "sys-local", client=client,
            )

    result = asyncio.run(resolve())
    assert result.engine.name == "fake"
    assert result.model_profile.model == "local-model"
