from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any

import httpx

from agent_team_v5.config.settings import Settings


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
                settings, system_id, role_id, legacy_engine, legacy_max_turns,
                legacy_timeout_seconds, callbacks, owned,
            )
    data: dict[str, Any] = {}
    try:
        response = await client.get(
            settings.control_plane_url.rstrip("/") + f"/api/v5/internal/systems/{system_id}/model-config",
            headers={"Authorization": f"Bearer {settings.worker_callback_token}"},
        )
        if response.status_code != 404:
            response.raise_for_status()
            data = response.json()
    except httpx.HTTPError:
        data = {}

    roles = [item for item in data.get("agent_roles", []) if isinstance(item, dict)]
    selected_role_id = role_id or str(data.get("default_role_id", ""))
    role = next((item for item in roles if str(item.get("id", "")) == selected_role_id), None)
    if role_id and role is None:
        raise RuntimeError(f"Agent role 不存在: {role_id}")
    if role:
        profiles = [item for item in data.get("model_profiles", []) if isinstance(item, dict)]
        profile_id = str(role.get("model_profile_ref", ""))
        raw_profile = next((item for item in profiles if str(item.get("id", "")) == profile_id), {})
        engine = str(role.get("engine", "")) or settings.default_engine
        if engine != "fake" and not raw_profile:
            raise RuntimeError(f"Agent role 缺少有效模型 Profile: {selected_role_id}")
        profile = _profile(raw_profile, "system") if raw_profile else ModelProfile(source="fake")
        return ResolvedAgentConfig(
            engine=_engine(settings, engine, role.get("max_turns"), role.get("timeout_seconds")),
            model_profile=profile,
            constraints=AgentConstraints(
                role_id=str(role.get("id", "")),
                role_name=str(role.get("name", "")),
                path_scope=tuple(str(item) for item in role.get("path_scope", []) if item),
                prompt=str(role.get("prompt", "")),
            ),
            artifacts_root=settings.artifacts_root,
            callbacks=callbacks or {},
        )

    engine = legacy_engine or settings.default_engine
    profile = environment_model_profile(settings)
    if engine == "claude_sdk":
        profile = await _legacy_claude_profile(settings, system_id, client, profile)
    return ResolvedAgentConfig(
        engine=_engine(settings, engine, legacy_max_turns, legacy_timeout_seconds),
        model_profile=profile,
        artifacts_root=settings.artifacts_root,
        callbacks=callbacks or {},
    )


async def available_role_metadata(settings: Settings, system_id: str, client: httpx.AsyncClient | None = None) -> list[dict[str, Any]]:
    """Planner 只看到角色元数据，Profile 和密钥在这里丢弃。"""

    if client is None:
        async with httpx.AsyncClient(timeout=5) as owned:
            return await available_role_metadata(settings, system_id, owned)
    try:
        response = await client.get(
            settings.control_plane_url.rstrip("/") + f"/api/v5/internal/systems/{system_id}/model-config",
            headers={"Authorization": f"Bearer {settings.worker_callback_token}"},
        )
        if response.status_code == 404:
            return []
        response.raise_for_status()
        data = response.json()
        # 单 Agent 模式不向 Planner 暴露角色，执行阶段会使用默认 Agent。
        if data.get("execution_mode", "planner_select") == "single":
            return []
        roles = data.get("agent_roles", [])
        return [
            {
                "id": str(role.get("id", "")),
                "name": str(role.get("name", "")),
                "engine": str(role.get("engine", "")),
                "path_scope": [str(path) for path in role.get("path_scope", [])],
            }
            for role in roles if isinstance(role, dict) and role.get("id")
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


async def _legacy_claude_profile(
    settings: Settings, system_id: str, client: httpx.AsyncClient, fallback: ModelProfile,
) -> ModelProfile:
    try:
        response = await client.get(
            settings.control_plane_url.rstrip("/") + f"/api/v5/internal/systems/{system_id}/claude-model-config",
            headers={"Authorization": f"Bearer {settings.worker_callback_token}"},
        )
        if response.status_code == 404:
            return fallback
        response.raise_for_status()
        data = response.json()
        return _profile(data, str(data.get("source", "legacy_system"))) if data.get("configured") else fallback
    except httpx.HTTPError:
        return fallback


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
