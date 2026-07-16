from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any

import httpx

from asterism_worker.config.settings import Settings
from asterism_worker.contracts import AgentConfigSnapshot


@dataclass(frozen=True, slots=True)
class ModelProfile:
    id: str = ""
    provider: str = "anthropic"
    model: str = ""
    base_url: str = ""
    api_key: str = ""
    source: str = "unconfigured"


@dataclass(frozen=True, slots=True)
class EngineConfig:
    name: str
    max_turns: int = 50
    timeout_seconds: int = 600
    endpoint: str = ""
    effort_level: str = ""


@dataclass(frozen=True, slots=True)
class AgentConstraints:
    role_id: str = ""
    role_name: str = ""
    path_scope: tuple[str, ...] = ()
    prompt: str = ""


@dataclass(frozen=True, slots=True)
class ResolvedAgentConfig:
    engine: EngineConfig
    model_profile: ModelProfile
    constraints: AgentConstraints = field(default_factory=AgentConstraints)
    artifacts_root: str = "runtime/artifacts"
    callbacks: dict[str, Callable[..., Any]] = field(default_factory=dict)


async def resolve_agent_config(
    settings: Settings,
    system_id: str,
    role_id: str = "",
    snapshot: AgentConfigSnapshot | None = None,
    legacy_engine: str = "",
    legacy_max_turns: int | None = None,
    legacy_timeout_seconds: int | None = None,
    callbacks: dict[str, Callable[..., Any]] | None = None,
    client: httpx.AsyncClient | None = None,
) -> ResolvedAgentConfig:
    """完整配置只在 activity 内解析，API key 不进入 workflow history。"""

    if client is None:
        async with httpx.AsyncClient(timeout=5) as owned:
            return await resolve_agent_config(
                settings, system_id, role_id, snapshot, legacy_engine, legacy_max_turns,
                legacy_timeout_seconds, callbacks, owned,
            )
    if snapshot is not None:
        selected_agent = role_id or "developer"
        agent = next((item for item in snapshot.agents if item.name == selected_agent), None)
        if agent is None:
            raise RuntimeError(f"Agent 不存在: {selected_agent}")
        raw_profile = next((item for item in snapshot.model_profiles
                            if item.id == agent.model_profile_ref), None)
        if agent.model_profile_ref and raw_profile is None:
            raise RuntimeError(f"模型 Profile 不存在: {agent.model_profile_ref}")
        live = await _fetch_model_config(settings, system_id, client, agent.model_profile_ref) \
            if agent.model_profile_ref else {}
        profile = ModelProfile(
            id=raw_profile.id,
            provider=raw_profile.provider,
            model=raw_profile.model,
            base_url=raw_profile.base_url,
            api_key=str(live.get("api_key", "")),
            source="system",
        ) if raw_profile else environment_model_profile(settings)
        return ResolvedAgentConfig(
            engine=_engine(settings, agent.engine or settings.default_engine,
                           agent.max_turns, agent.timeout_seconds),
            model_profile=profile,
            constraints=AgentConstraints(
                role_id=agent.name,
                role_name=agent.name,
                path_scope=tuple(agent.path_scope),
                prompt=agent.prompt,
            ),
            artifacts_root=settings.artifacts_root,
            callbacks=callbacks or {},
        )

    data = await _fetch_model_config(settings, system_id, client)

    agents = [item for item in data.get("agents", []) if isinstance(item, dict)]
    selected_agent = role_id or "developer"
    agent = next((item for item in agents if str(item.get("name", "")) == selected_agent), None)
    if agent is None and not role_id and not agents:
        # 旧 workflow / 控制面暂不可达时保留原有部署环境回落路径。
        return ResolvedAgentConfig(
            engine=_engine(settings, legacy_engine or settings.default_engine,
                           legacy_max_turns, legacy_timeout_seconds),
            model_profile=environment_model_profile(settings),
            artifacts_root=settings.artifacts_root,
            callbacks=callbacks or {},
        )
    if agent is None:
        raise RuntimeError(f"Agent 不存在: {selected_agent}")
    if agent:
        profiles = [item for item in data.get("model_profiles", []) if isinstance(item, dict)]
        profile_id = str(agent.get("model_profile_ref", ""))
        raw_profile = next((item for item in profiles if str(item.get("id", "")) == profile_id), {})
        engine = str(agent.get("engine", "")) or settings.default_engine
        profile = _profile(raw_profile, "system") if raw_profile else environment_model_profile(settings)
        return ResolvedAgentConfig(
            engine=_engine(settings, engine, agent.get("max_turns"), agent.get("timeout_seconds")),
            model_profile=profile,
            constraints=AgentConstraints(
                role_id=str(agent.get("name", "")),
                role_name=str(agent.get("name", "")),
                path_scope=tuple(str(item) for item in agent.get("path_scope", []) if item),
                prompt=str(agent.get("prompt", "")),
            ),
            artifacts_root=settings.artifacts_root,
            callbacks=callbacks or {},
        )


async def _fetch_model_config(settings: Settings, system_id: str, client: httpx.AsyncClient,
                              profile_id: str = "") -> dict[str, Any]:
    try:
        response = await client.get(
            settings.control_plane_url.rstrip("/") + f"/api/v5/internal/systems/{system_id}/model-config",
            headers={"Authorization": f"Bearer {settings.worker_callback_token}"},
            params={"profile_id": profile_id} if profile_id else None,
        )
        if response.status_code == 404:
            return {}
        response.raise_for_status()
        return response.json()
    except httpx.HTTPError:
        return {}


async def available_agent_metadata(settings: Settings, system_id: str, client: httpx.AsyncClient | None = None) -> list[dict[str, Any]]:
    """Planner 只看到自定义 Agent 元数据，Profile 和密钥在这里丢弃。"""

    if client is None:
        async with httpx.AsyncClient(timeout=5) as owned:
            return await available_agent_metadata(settings, system_id, owned)
    try:
        response = await client.get(
            settings.control_plane_url.rstrip("/") + f"/api/v5/internal/systems/{system_id}/model-config",
            headers={"Authorization": f"Bearer {settings.worker_callback_token}"},
        )
        if response.status_code == 404:
            return []
        response.raise_for_status()
        data = response.json()
        agents = data.get("agents", [])
        return [
            {
                "name": str(agent.get("name", "")),
                "engine": str(agent.get("engine", "")),
                "path_scope": [str(path) for path in agent.get("path_scope", [])],
            }
            for agent in agents
            if isinstance(agent, dict) and agent.get("name") and agent.get("kind") == "custom"
        ]
    except httpx.HTTPError:
        return []


def environment_model_profile(settings: Settings) -> ModelProfile:
    return ModelProfile(
        provider=settings.default_model_provider,
        model=settings.default_model,
        base_url=settings.default_model_base_url,
        api_key=settings.default_model_api_key,
        source="worker_env" if settings.default_model_api_key else "unconfigured",
    )


def _profile(data: dict[str, Any], source: str) -> ModelProfile:
    return ModelProfile(
        id=str(data.get("id", data.get("model_id", ""))),
        provider=str(data.get("provider", data.get("preset", "anthropic"))),
        model=str(data.get("model", "")),
        base_url=str(data.get("base_url", "")),
        api_key=str(data.get("api_key", "")),
        source=source,
    )


def _engine(settings: Settings, name: str, max_turns: Any, timeout_seconds: Any) -> EngineConfig:
    return EngineConfig(
        name=name,
        max_turns=int(max_turns or settings.engine_max_turns),
        timeout_seconds=int(timeout_seconds or settings.engine_timeout_seconds),
        endpoint=settings.execution_http_endpoint,
        effort_level=settings.engine_effort_level,
    )
