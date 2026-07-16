import asyncio
import json

import httpx
from asterism_worker.agent_config import available_agent_metadata, resolve_agent_config
from asterism_worker.config.settings import Settings


def client_for(data: dict) -> httpx.AsyncClient:
    return httpx.AsyncClient(transport=httpx.MockTransport(lambda request: httpx.Response(200, json=data)))


def test_resolved_agent_config_uses_agent_engine_and_profile_without_exposing_key():
    data = {
        "model_profiles": [
            {"id": "mp-claude", "provider": "anthropic", "model": "claude", "api_key": "secret-a"},
            {"id": "mp-openai", "provider": "openai-compat", "model": "deepseek", "api_key": "secret-b"},
        ],
        "agents": [
            {"name": "developer", "kind": "builtin", "engine": "claude_sdk", "model_profile_ref": "mp-claude"},
            {"name": "frontend", "kind": "custom", "engine": "claude_sdk", "model_profile_ref": "mp-claude", "path_scope": ["web"], "max_turns": 8},
            {"name": "backend", "kind": "custom", "engine": "deepagents", "model_profile_ref": "mp-openai", "path_scope": ["api"]},
        ],
    }

    async def resolve():
        async with client_for(data) as client:
            first = await resolve_agent_config(Settings(), "sys", role_id="frontend", client=client)
        async with client_for(data) as client:
            second = await resolve_agent_config(Settings(), "sys", role_id="backend", client=client)
        async with client_for(data) as client:
            metadata = await available_agent_metadata(Settings(), "sys", client)
        return first, second, metadata

    first, second, metadata = asyncio.run(resolve())
    assert (first.engine.name, first.model_profile.api_key, first.constraints.path_scope) == ("claude_sdk", "secret-a", ("web",))
    assert (second.engine.name, second.model_profile.api_key) == ("deepagents", "secret-b")
    assert metadata[0] == {"name": "frontend", "engine": "claude_sdk", "path_scope": ["web"]}
    assert "secret" not in json.dumps(metadata)


def test_developer_with_empty_profile_falls_back_to_global_profile():
    async def resolve():
        async with client_for({"model_profiles": [], "agents": [
            {"name": "developer", "kind": "builtin", "engine": "deepagents", "model_profile_ref": ""},
        ]}) as client:
            return await resolve_agent_config(
                Settings(default_engine="deepagents", default_model="global-model", default_model_api_key="global-key"),
                "legacy", client=client,
            )

    resolved = asyncio.run(resolve())
    assert resolved.engine.name == "deepagents"
    assert resolved.model_profile.model == "global-model"
    assert resolved.model_profile.source == "worker_env"


def test_custom_agent_with_empty_profile_uses_global_profile():
    data = {"model_profiles": [], "agents": [
        {"name": "broken", "kind": "custom", "engine": "deepagents", "model_profile_ref": ""},
    ]}

    async def resolve():
        async with client_for(data) as client:
            return await resolve_agent_config(
                Settings(default_model="global", default_model_api_key="global-key"), "sys", "broken", client=client,
            )

    assert asyncio.run(resolve()).model_profile.api_key == "global-key"


def test_planner_only_sees_custom_agents():
    data = {
        "agents": [
            {"name": "developer", "kind": "builtin", "engine": "deepagents"},
            {"name": "frontend", "kind": "custom", "engine": "deepagents", "path_scope": ["web"]},
        ],
    }

    async def resolve():
        async with client_for(data) as client:
            return await available_agent_metadata(Settings(), "sys", client)

    assert asyncio.run(resolve()) == [{"name": "frontend", "engine": "deepagents", "path_scope": ["web"]}]
