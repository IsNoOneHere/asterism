import json
from collections.abc import Callable
from hmac import compare_digest

from fastapi import FastAPI, HTTPException, Request
import httpx
from pydantic import ValidationError

from agent_service.contracts import DraftRequest, DraftResult, ExecutionPlan, ExecutionRequest, ExecutionResult, PlanRequest, UiObservation
from agent_service.llm import LlmClient, ModelConfig, OpenAIChatClient, default_model_config, merge_model_config
from agent_service.settings import AgentSettings


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

    @app.post("/plan")
    def plan(request: PlanRequest) -> ExecutionPlan:
        prompt = plan_prompt(request)
        model_config = resolve_model_config(settings, fetch_model_config, request.system_id, "planning")
        return _strict_json(llm, prompt, model_config, ExecutionPlan, "planner did not return valid ExecutionPlan JSON")

    @app.post("/execute")
    def execute(request: ExecutionRequest) -> ExecutionResult:
        model_config = resolve_model_config(settings, fetch_model_config, request.system_id, "diff", request.model_profile_id)
        diff_patch = llm.complete(execute_prompt(request), model_config)
        if "diff --git" not in diff_patch:
            raise HTTPException(status_code=422, detail="execution did not return a unified git diff")
        return ExecutionResult(summary="llm diff generated", diff_patch=diff_patch)

    @app.post("/prd-draft")
    def prd_draft(request: DraftRequest) -> DraftResult:
        model_config = resolve_model_config(settings, fetch_model_config, request.system_id, "prd")
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
        config = resolve_model_config(settings, fetch_model_config, "healthz", "default")
        return {"ok": True, "model_config_available": bool(config.model and config.api_key)}

    @app.get("/readiness")
    def readiness(system_id: str) -> dict:
        # 仅返回配置状态，禁止把 API key 暴露给 Worker 或页面。
        stages = {
            stage: resolve_model_config(settings, fetch_model_config, system_id, stage)
            for stage in ("prd", "planning", "diff")
        }
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

    return app


def _strict_json(llm: LlmClient, prompt: str, model_config: ModelConfig, schema, error_detail: str):
    for attempt in range(2):
        try:
            return schema.model_validate(json.loads(llm.complete(prompt, model_config, json_mode=True)))
        except (json.JSONDecodeError, ValidationError):
            if attempt == 0:
                continue
    raise HTTPException(status_code=400, detail=error_detail)


def control_plane_model_config(settings: AgentSettings) -> Callable[[str, str, str], ModelConfig]:
    def fetch(system_id: str, stage: str, profile_id: str = "") -> ModelConfig:
        url = settings.control_plane_url.rstrip("/") + f"/api/v5/internal/systems/{system_id}/model-config"
        headers = {"Authorization": f"Bearer {settings.worker_callback_token}"}
        try:
            params = {"stage": stage}
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
                         system_id: str, stage: str, profile_id: str = "") -> ModelConfig:
    # 兼容旧测试/部署注入的双参数 fetcher。
    try:
        resolved = fetch_model_config(system_id, stage, profile_id)
    except TypeError:
        resolved = fetch_model_config(system_id, stage)
    return merge_model_config(default_model_config(settings), resolved)


def plan_prompt(request: PlanRequest) -> str:
    # LLM 只负责计划文本，权威记忆和生命周期仍由控制面/worker 管。
    return (
        "Return strict JSON for ExecutionPlan with keys steps,target_files,test_plan,risks,assignments.\n"
        "All four values must be JSON arrays of strings: steps,target_files,test_plan,risks.\n"
        "assignments is optional; when multiple available roles are useful, return ordered objects "
        "{role,scope_paths,step_refs} using only listed role ids and path scopes.\n"
        "target_files must be real existing paths selected from the repo summary.\n"
        f"PRD: {request.prd.model_dump_json()}\n"
        f"Confirmed target hints (疑似相关，以实际代码为准): "
        f"{json.dumps(request.prd.draft_json.get('targets', []), ensure_ascii=False)}\n"
        f"Repo summary:\n{request.repo_summary}\n"
        f"Memories: {json.dumps(request.memories, ensure_ascii=False)}\n"
        f"Allowed paths: {request.allowed_paths}\n"
        f"Available roles (no secrets): {json.dumps([role.model_dump() for role in request.available_roles], ensure_ascii=False)}\n"
    )


def execute_prompt(request: ExecutionRequest) -> str:
    # 只要求 unified diff；worker 仍会做 diff 门禁和真实 patch apply。
    previous = ""
    if request.previous_attempt:
        previous = (
            "\n上次 diff 因以下错误无法应用，请只修正 diff：\n"
            f"{request.previous_attempt.get('apply_error', '')}\n"
            f"Previous diff:\n{request.previous_attempt.get('diff', '')}\n"
        )
    return (
        "Return only a unified git diff that contains diff --git headers.\n"
        "Diff must be based on the provided file contents; context lines must match exactly.\n"
        f"Goal: {request.goal}\n"
        f"Acceptance criteria: {request.acceptance_criteria}\n"
        f"Plan: {request.plan.model_dump_json()}\n"
        f"Memories: {json.dumps(request.memories, ensure_ascii=False)}\n"
        f"File listing:\n{request.file_listing}\n"
        f"File contents:\n{json.dumps(request.file_contents, ensure_ascii=False)}\n"
        f"Role: {request.role_id or 'default'} ({request.role_name})\n"
        f"Role scope: {request.role_scope}\n"
        f"Role instructions: {request.role_prompt or 'none'}\n"
        f"Stage step refs: {request.step_refs or ['all']}\n"
        f"Previous role handoff: {request.handoff_summary or 'none'}\n"
        f"{previous}"
    )


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
