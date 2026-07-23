import base64
import json
import logging
from collections.abc import Callable
from datetime import datetime, timezone
from hmac import compare_digest
from typing import Literal

from fastapi import Depends, FastAPI, HTTPException, Request
import httpx
from starlette.responses import JSONResponse

from agent_service.contracts import (
    DraftRequest,
    DraftResult,
    StructuredOutputProbe,
    UiObservation,
    normalize_draft_result,
    normalize_ui_observation,
)
from agent_service.llm import LlmClient, ModelConfig, RoutedLlmClient, default_model_config, merge_model_config
from agent_service.model_errors import ModelCallError, ModelErrorCode
from agent_service.settings import AgentSettings
from agent_service.structured_output import StructuredOutputRunner

log = logging.getLogger(__name__)


def create_app(
    llm: LlmClient | None = None,
    settings: AgentSettings | None = None,
    model_config_fetcher: Callable[..., ModelConfig] | None = None,
) -> FastAPI:
    settings = settings or AgentSettings()
    if llm is None:
        llm = RoutedLlmClient(settings)
    fetch_model_config = model_config_fetcher or control_plane_model_config(settings)
    structured = StructuredOutputRunner(llm)
    app = FastAPI(title="Asterism agent-service")

    @app.exception_handler(ModelCallError)
    async def model_error_handler(_request: Request, error: ModelCallError) -> JSONResponse:
        # 日志只记录协议、模型和错误码，禁止写入 Key、图片或完整模型响应。
        log.warning("模型调用失败 code=%s", error.code)
        return JSONResponse(status_code=error.status_code, content={"code": error.code, "message": error.message})

    def require_internal_token(request: Request) -> None:
        # Runner 业务接口只允许携带共享内部 Token 的 Server 或 Worker 调用。
        if not compare_digest(
            request.headers.get("authorization", ""),
            f"Bearer {settings.worker_callback_token}",
        ):
            raise HTTPException(status_code=401, detail="invalid internal token")

    @app.post("/prd-draft", dependencies=[Depends(require_internal_token)])
    def prd_draft(request: DraftRequest) -> DraftResult:
        model_config = resolve_model_config(settings, fetch_model_config, request.system_id, "product")
        return structured.run(
            prd_draft_prompt(request), model_config, DraftResult, normalizer=normalize_draft_result,
        )

    @app.post("/analyze-image", dependencies=[Depends(require_internal_token)])
    async def analyze_image(system_id: str, request: Request) -> UiObservation:
        model_config = resolve_model_config(settings, fetch_model_config, system_id, "vision")
        if not model_config.image_input or not model_config.model or not model_config.api_key:
            raise ModelCallError(
                ModelErrorCode.CAPABILITY_UNSUPPORTED,
                "请先为 Vision Agent 绑定支持图片输入的模型 Profile",
                422,
            )
        content_type = request.headers.get("content-type", "").split(";", 1)[0]
        if content_type not in {"image/png", "image/jpeg", "image/webp"}:
            raise HTTPException(status_code=415, detail="unsupported image type")
        image = await request.body()
        return structured.run(
            image_observation_prompt(), model_config, UiObservation,
            normalizer=normalize_ui_observation, image=image, content_type=content_type,
        )

    @app.get("/healthz")
    def healthz() -> dict:
        # 存活检查不能依赖尚未启动的 Server；模型配置状态由受保护的 readiness 返回。
        return {"ok": True}

    @app.get("/readiness", dependencies=[Depends(require_internal_token)])
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

    @app.post("/model-connection-test", dependencies=[Depends(require_internal_token)])
    def model_connection_test(system_id: str, profile_id: str) -> dict:
        return _test_model_connection(llm, fetch_model_config(system_id, "developer", profile_id))

    @app.post("/model-capability-test", dependencies=[Depends(require_internal_token)])
    def model_capability_test(
        system_id: str,
        profile_id: str,
        capability: Literal["structured_output", "image_input"],
    ) -> dict:
        config = fetch_model_config(system_id, "developer", profile_id)
        return _test_model_capability(llm, structured, config, capability)

    return app


def _test_model_connection(llm: LlmClient, config: ModelConfig) -> dict:
    checked_at = _now()
    if not config.model:
        return _test_result(False, "模型名称未配置", checked_at, ModelErrorCode.CONNECTION_FAILED)
    if not config.api_key:
        return _test_result(False, "API Key 未配置", checked_at, ModelErrorCode.CONNECTION_FAILED)
    try:
        llm.test_connection(config)
        log.info("模型连通性测试 provider=%s model=%s connected=true", config.provider, config.model)
        return _test_result(True, "连接正常", checked_at)
    except ModelCallError as error:
        log.warning("模型连通性测试失败 provider=%s model=%s code=%s",
                    config.provider, config.model, error.code)
        return _test_result(False, error.message, checked_at, ModelErrorCode.CONNECTION_FAILED)


def _test_model_capability(
    llm: LlmClient,
    structured: StructuredOutputRunner,
    config: ModelConfig,
    capability: str,
) -> dict:
    checked_at = _now()
    try:
        if capability == "structured_output":
            structured.run(
                "Return marker as asterism and count as 1.", config, StructuredOutputProbe,
            )
            message = "结构化输出正常"
        else:
            if not config.image_input:
                raise ModelCallError(ModelErrorCode.CAPABILITY_UNSUPPORTED, "Profile 未声明图片输入能力", 422)
            answer = llm.complete_vision(
                "识别这张合成图片的主色，只回答颜色。", _synthetic_red_png(), "image/png", config,
            )
            if "red" not in answer.lower() and "红" not in answer:
                raise ModelCallError(ModelErrorCode.CAPABILITY_UNSUPPORTED, "模型未正确识别合成图片", 422)
            message = "图片输入正常"
        log.info("模型能力测试 provider=%s model=%s capability=%s supported=true",
                 config.provider, config.model, capability)
        return _capability_result(True, message, checked_at)
    except ModelCallError as error:
        log.warning("模型能力测试失败 provider=%s model=%s capability=%s code=%s",
                    config.provider, config.model, capability, error.code)
        return _capability_result(False, error.message, checked_at, error.code)


def _test_result(connected: bool, message: str, checked_at: str, code: ModelErrorCode | None = None) -> dict:
    return {"connected": connected, "message": message, "checkedAt": checked_at, "code": code or ""}


def _capability_result(supported: bool, message: str, checked_at: str,
                       code: ModelErrorCode | None = None) -> dict:
    return {"supported": supported, "message": message, "checkedAt": checked_at, "code": code or ""}


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _synthetic_red_png() -> bytes:
    # 固定合成图不包含业务数据，仅用于独立验证图片输入能力。
    return base64.b64decode(
        "iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAIAAAAlC+aJAAAAb0lEQVR4nO3PAQkAAAyEwO9feosh"
        "gnABdLep8QUNyPEFDcjxBQ3I8QUNyPEFDcjxBQ3I8QUNyPEFDcjxBQ3I8QUNyPEFDcjxBQ3I"
        "8QUNyPEFDcjxBQ3I8QUNyPEFDcjxBQ3I8QUNyPEFDcjxBQ3IPanc8OLDQitxAAAAAElFTkSuQmCC"
    )


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
    # ProductAgent 只产 PRD 草稿；引用必须复用 context_items 中已有的 refId。
    return (
        "Return strict JSON with keys title,draft,missing_fields,assistant_message,used_context_refs,citations,memory_candidates.\n"
        "draft must include title, goal, scope, acceptanceCriteria. acceptanceCriteria must be an array of strings.\n"
        "Use stable citation keys title,goal,scope,AC-1,AC-2... . citations maps each key to source refIds.\n"
        "Only cite refId values present in context_items. Never invent a refId.\n"
        "Content without a source must have no citation and is treated as AI_SUGGESTION.\n"
        "memory_candidates is optional. Each item uses category,audience,title,content,target_refs,evidence_refs.\n"
        "category is constraint|convention|lesson; audience is product|execution|both.\n"
        "evidence_refs may only use refIds from context_items; target_refs may only use targetRefs from context_items.\n"
        "Only propose reusable rules; never include secrets, logs, diffs, temporary errors or one-off goals.\n"
        "missing_fields only suggests what assistant_message should ask; the control plane recomputes lifecycle state from draft.\n"
        "If acceptance criteria are missing, set missing_fields to [\"acceptance_criteria\"] and ask in Chinese.\n"
        "If the previous round missed acceptance criteria, merge this user message into draft.acceptanceCriteria.\n"
        f"User content: {request.content}\n"
        f"Current draft: {json.dumps(request.current_draft, ensure_ascii=False)}\n"
        f"Missing fields: {request.missing_fields}\n"
        f"Conversation history: {json.dumps(request.conversation_history, ensure_ascii=False)}\n"
        f"Structured context items: {json.dumps(request.context_items, ensure_ascii=False)}\n"
    )


def image_observation_prompt() -> str:
    # 视觉模型只做观察，不允许越过知识检索直接猜代码或接口。
    return (
        "Return strict JSON with keys page_title,text_anchors,ui_elements,error_messages,user_visible_summary.\n"
        "Describe only visible UI facts from the screenshot. Keep text anchors exact when readable.\n"
        "Never guess API endpoints, source files, implementation details, routes, or hidden behavior.\n"
        "Write user_visible_summary in concise Chinese."
    )
