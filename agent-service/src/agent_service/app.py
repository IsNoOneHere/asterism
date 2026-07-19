import json
import logging
from collections.abc import Callable
from hmac import compare_digest

from fastapi import FastAPI, HTTPException, Request
import httpx
from pydantic import ValidationError

from agent_service.contracts import DraftRequest, DraftResult, UiObservation
from agent_service.llm import LlmClient, ModelConfig, OpenAIChatClient, default_model_config, merge_model_config
from agent_service.settings import AgentSettings

log = logging.getLogger(__name__)


def create_app(
    llm: LlmClient | None = None,
    settings: AgentSettings | None = None,
    model_config_fetcher: Callable[..., ModelConfig] | None = None,
) -> FastAPI:
    settings = settings or AgentSettings()
    if llm is None:
        llm = OpenAIChatClient(settings)
    fetch_model_config = model_config_fetcher or control_plane_model_config(settings)
    app = FastAPI(title="Asterism agent-service")

    @app.post("/prd-draft")
    def prd_draft(request: DraftRequest) -> DraftResult:
        model_config = resolve_model_config(settings, fetch_model_config, request.system_id, "product")
        return _strict_json(llm, prd_draft_prompt(request), model_config, DraftResult, "prd draft did not return valid DraftResult JSON")

    @app.post("/analyze-image")
    async def analyze_image(system_id: str, request: Request) -> UiObservation:
        # 视觉调用只能由控制面转发，避免绕过成员鉴权直接消耗模型额度。
        if not compare_digest(
            request.headers.get("authorization", ""),
            f"Bearer {settings.worker_callback_token}",
        ):
            raise HTTPException(status_code=401, detail="invalid internal token")
        model_config = resolve_model_config(settings, fetch_model_config, system_id, "vision")
        if not model_config.supports_vision or not model_config.model or not model_config.api_key:
            raise HTTPException(status_code=422, detail="请先为该系统配置支持 Vision 的模型 Profile")
        content_type = request.headers.get("content-type", "").split(";", 1)[0]
        if content_type not in {"image/png", "image/jpeg", "image/webp"}:
            raise HTTPException(status_code=415, detail="unsupported image type")
        image = await request.body()
        try:
            raw = llm.complete_vision(image_observation_prompt(), image, content_type, model_config)
            return UiObservation.model_validate(json.loads(raw))
        except (json.JSONDecodeError, ValidationError):
            raise HTTPException(status_code=400, detail="vision model did not return valid UiObservation JSON")

    @app.get("/healthz")
    def healthz() -> dict:
        config = resolve_model_config(settings, fetch_model_config, "healthz", "product")
        return {"ok": True, "model_config_available": bool(config.model and config.api_key)}

    @app.get("/readiness")
    def readiness(system_id: str) -> dict:
        # 仅返回配置状态，禁止把 API key 暴露给 Worker 或页面。
        stages = {"prd": resolve_model_config(settings, fetch_model_config, system_id, "product")}
        return {
            "ready": all(config.model and config.api_key for config in stages.values()),
            "protocol": "openai_compatible",
            "stages": {
                stage: {
                    "ready": bool(config.model and config.api_key),
                    "model": config.model,
                    "model_id": config.model_id,
                    "name": config.name,
                    "base_url_configured": bool(config.base_url),
                    "api_key_configured": bool(config.api_key),
                }
                for stage, config in stages.items()
            },
        }

    @app.post("/model-connection-test")
    def model_connection_test(system_id: str, profile_id: str, request: Request) -> dict:
        if not compare_digest(
            request.headers.get("authorization", ""),
            f"Bearer {settings.worker_callback_token}",
        ):
            raise HTTPException(status_code=401, detail="invalid internal token")
        return _test_model_connection(fetch_model_config(system_id, "developer", profile_id))

    return app


def _test_model_connection(config: ModelConfig) -> dict:
    if not config.model:
        return {"connected": False, "message": "模型名称未配置"}
    if not config.api_key:
        return {"connected": False, "message": "API Key 未配置"}
    anthropic = config.provider == "anthropic"
    base_url = config.base_url.rstrip("/") or ("https://api.anthropic.com" if anthropic else "https://api.openai.com/v1")
    headers = {"Authorization": f"Bearer {config.api_key}"}
    if anthropic:
        headers.update({"x-api-key": config.api_key, "anthropic-version": "2023-06-01"})
    try:
        # 最多生成 1 token，同时验证地址、密钥和模型真实可用。
        response = httpx.post(
            base_url + ("/v1/messages" if anthropic else "/chat/completions"),
            headers=headers,
            json={"model": config.model, "max_tokens": 1, "messages": [{"role": "user", "content": "ping"}]},
            timeout=10,
        )
        connected = response.is_success
        log.info("模型连通性测试 provider=%s model=%s connected=%s status=%s",
                 config.provider, config.model, connected, response.status_code)
        return {"connected": connected,
                "message": "连接正常" if connected else f"连接失败（HTTP {response.status_code}）"}
    except httpx.HTTPError as error:
        log.warning("模型连通性测试失败 provider=%s model=%s type=%s",
                    config.provider, config.model, type(error).__name__)
        return {"connected": False, "message": f"连接失败（{type(error).__name__}）"}


def _strict_json(llm: LlmClient, prompt: str, model_config: ModelConfig, schema, error_detail: str):
    for attempt in range(2):
        try:
            return schema.model_validate(json.loads(llm.complete(prompt, model_config, json_mode=True)))
        except (json.JSONDecodeError, ValidationError):
            if attempt == 0:
                continue
    raise HTTPException(status_code=400, detail=error_detail)


def control_plane_model_config(settings: AgentSettings) -> Callable[[str, str, str], ModelConfig]:
    def fetch(system_id: str, agent: str, profile_id: str = "") -> ModelConfig:
        url = settings.control_plane_url.rstrip("/") + f"/api/v5/internal/systems/{system_id}/model-config"
        headers = {"Authorization": f"Bearer {settings.worker_callback_token}"}
        try:
            params = {"agent": agent}
            if profile_id:
                params["profile_id"] = profile_id
            response = httpx.get(url, headers=headers, params=params, timeout=5)
            if response.status_code == 404:
                return ModelConfig()
            response.raise_for_status()
            return ModelConfig.model_validate(response.json())
        except httpx.HTTPError:
            return ModelConfig()

    return fetch


def resolve_model_config(settings: AgentSettings, fetch_model_config: Callable[..., ModelConfig],
                         system_id: str, agent: str, profile_id: str = "") -> ModelConfig:
    resolved = fetch_model_config(system_id, agent, profile_id)
    return merge_model_config(default_model_config(settings), resolved)


def prd_draft_prompt(request: DraftRequest) -> str:
    # ProductAgent 只产 PRD 草稿，缺验收标准时必须中文追问。
    return (
        "Return strict JSON with keys title,draft,missing_fields,assistant_message.\n"
        "draft must include goal, scope, acceptanceCriteria.\n"
        "If acceptance criteria are missing, set missing_fields to [\"acceptance_criteria\"] and ask in Chinese.\n"
        "If the previous round missed acceptance criteria, merge this user message into draft.acceptanceCriteria.\n"
        f"User content: {request.content}\n"
        f"Current draft: {json.dumps(request.current_draft, ensure_ascii=False)}\n"
        f"Missing fields: {request.missing_fields}\n"
        f"Conversation history: {json.dumps(request.conversation_history, ensure_ascii=False)}\n"
        f"Approved memories as constraints: {json.dumps(request.approved_memories, ensure_ascii=False)}\n"
    )


def image_observation_prompt() -> str:
    # 视觉模型只做观察，不允许越过知识检索直接猜代码或接口。
    return (
        "Return strict JSON with keys page_title,text_anchors,ui_elements,error_messages,user_visible_summary.\n"
        "Describe only visible UI facts from the screenshot. Keep text anchors exact when readable.\n"
        "Never guess API endpoints, source files, implementation details, routes, or hidden behavior.\n"
        "Write user_visible_summary in concise Chinese."
    )
