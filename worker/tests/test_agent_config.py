import asyncio
import json

import httpx
import pytest

from agent_team_v5.agent_config import available_role_metadata, resolve_agent_config
from agent_team_v5.config.settings import Settings


def client_for(data: dict) -> httpx.AsyncClient:
    return httpx.AsyncClient(transport=httpx.MockTransport(lambda request: httpx.Response(200, json=data)))


def test_resolved_agent_config_uses_role_engine_and_profile_without_exposing_key():
    data = {
        "model_profiles": [
            {"id": "mp-claude", "provider": "anthropic", "model": "claude", "api_key": "secret-a"},
            {"id": "mp-openai", "provider": "openai-compat", "model": "deepseek", "api_key": "secret-b"},
        ],
        "agent_roles": [
            {"id": "frontend", "name": "前端", "engine": "claude_sdk", "model_profile_ref": "mp-claude", "path_scope": ["web"], "max_turns": 8},
            {"id": "backend", "name": "后端", "engine": "deepagents", "model_profile_ref": "mp-openai", "path_scope": ["api"]},
        ],
        "default_role_id": "frontend",
    }

    async def resolve():
        async with client_for(data) as client:
            first = await resolve_agent_config(Settings(), "sys", role_id="frontend", client=client)
        async with client_for(data) as client:
            second = await resolve_agent_config(Settings(), "sys", role_id="backend", client=client)
        async with client_for(data) as client:
            metadata = await available_role_metadata(Settings(), "sys", client)
        return first, second, metadata

    first, second, metadata = asyncio.run(resolve())
    assert (first.engine.name, first.model_profile.api_key, first.constraints.path_scope) == ("claude_sdk", "secret-a", ("web",))
    assert (second.engine.name, second.model_profile.api_key) == ("deepagents", "secret-b")
    assert metadata[0] == {"id": "frontend", "name": "前端", "engine": "claude_sdk", "path_scope": ["web"]}
    assert "secret" not in json.dumps(metadata)


def test_resolved_agent_config_falls_back_to_single_global_profile():
    async def resolve():
        async with client_for({"model_profiles": [], "agent_roles": [], "default_role_id": ""}) as client:
            return await resolve_agent_config(
                Settings(default_engine="deepagents", default_model="global-model", default_model_api_key="global-key"),
                "legacy", client=client,
            )

    resolved = asyncio.run(resolve())
    assert resolved.engine.name == "deepagents"
    assert resolved.model_profile.model == "global-model"
    assert resolved.model_profile.source == "worker_env"


def test_real_role_with_missing_profile_is_configuration_error():
    data = {"model_profiles": [], "agent_roles": [{"id": "broken", "engine": "deepagents", "model_profile_ref": ""}]}

    async def resolve():
        async with client_for(data) as client:
            return await resolve_agent_config(Settings(default_model_api_key="must-not-fallback"), "sys", "broken", client=client)

    with pytest.raises(RuntimeError, match="缺少有效模型 Profile"):
        asyncio.run(resolve())
