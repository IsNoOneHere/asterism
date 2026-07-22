import json
from pathlib import Path
from types import SimpleNamespace

from fastapi.testclient import TestClient
import httpx
from openai import APIConnectionError, BadRequestError

from agent_service.app import create_app
from agent_service.llm import LlmClient, ModelConfig, OpenAIChatClient
from agent_service.settings import AgentSettings


INTERNAL_HEADERS = {"Authorization": "Bearer dev-worker-token"}


class FakeLlmClient(LlmClient):
    def __init__(self, responses: list[str]) -> None:
        self.responses = responses
        self.calls = 0
        self.configs: list[ModelConfig] = []
        self.prompts: list[str] = []
        self.json_modes: list[bool] = []

    def complete(self, prompt: str, config: ModelConfig, json_mode: bool = False) -> str:
        self.calls += 1
        self.prompts.append(prompt)
        self.configs.append(config)
        self.json_modes.append(json_mode)
        return self.responses.pop(0)

    def complete_vision(self, prompt: str, image: bytes, content_type: str, config: ModelConfig) -> str:
        self.calls += 1
        self.prompts.append(prompt)
        self.configs.append(config)
        return self.responses.pop(0)


class FailingVisionLlmClient(FakeLlmClient):
    def __init__(self, error: Exception) -> None:
        super().__init__([])
        self.error = error

    def complete_vision(self, prompt: str, image: bytes, content_type: str, config: ModelConfig) -> str:
        raise self.error


def test_legacy_execution_endpoints_are_removed():
    client = TestClient(create_app(FakeLlmClient([])))

    assert client.post("/plan", json={}).status_code == 404
    assert client.post("/execute", json={}).status_code == 404


def test_prd_draft_retries_json_and_asks_acceptance_in_chinese():
    llm = FakeLlmClient([
        "not json",
        '{"title":"登录页错误提示","draft":{"goal":"做登录页","scope":"code_change","acceptanceCriteria":[]},"missing_fields":["acceptance_criteria"],"assistant_message":"请补充验收标准。"}',
    ])
    client = TestClient(create_app(llm))

    response = client.post("/prd-draft", headers=INTERNAL_HEADERS, json={
        "system_id": "sys-1",
        "content": "做登录页",
        "current_draft": {},
        "missing_fields": [],
        "conversation_history": [{"role": "user", "content": "先做登录页"}],
        "context_items": [{"refId": "MEM:mem-1", "type": "memory", "content": "只能改 src"}],
    })

    assert response.status_code == 200
    assert response.json()["missing_fields"] == ["acceptance_criteria"]
    assert "验收标准" in response.json()["assistant_message"]
    assert "只能改 src" in llm.prompts[0]
    assert "Never invent a refId" in llm.prompts[0]
    assert "evidence_refs may only use refIds" in llm.prompts[0]
    assert llm.json_modes == [True, True]


def test_prd_draft_accepts_shared_fixture():
    fixture = Path(__file__).parents[2] / "docs" / "fixtures" / "prd-draft-request.json"
    llm = FakeLlmClient([
        '{"title":"登录页","draft":{"goal":"做登录页","scope":"code_change","acceptanceCriteria":[]},"missing_fields":["acceptance_criteria"],"assistant_message":"还缺验收标准，请补充。"}',
    ])
    client = TestClient(create_app(llm))

    response = client.post("/prd-draft", headers=INTERNAL_HEADERS, json=json.loads(fixture.read_text()))

    assert response.status_code == 200
    assert response.json()["draft"]["scope"] == "code_change"


def test_system_model_config_overrides_global_defaults(caplog):
    llm = FakeLlmClient(['{"title":"t","draft":{"goal":"g"},"missing_fields":[],"assistant_message":"ok"}'])
    client = TestClient(create_app(
        llm,
        AgentSettings(api_key="default-key", model="default-model", base_url="https://default.local"),
        model_config_fetcher=lambda *args: ModelConfig(
            managed=True, provider="openai", model="system-model",
            base_url="https://system.local", api_key="system-secret",
        ),
    ))

    response = client.post(
        "/prd-draft", headers=INTERNAL_HEADERS, json={"system_id": "sys-1", "content": "g"},
    )

    assert response.status_code == 200
    assert llm.configs[0].model == "system-model"
    assert llm.configs[0].api_key == "system-secret"
    assert "system-secret" not in caplog.text


def test_missing_system_model_config_falls_back_to_global_defaults():
    llm = FakeLlmClient(['{"title":"t","draft":{"goal":"g"},"missing_fields":[],"assistant_message":"ok"}'])
    client = TestClient(create_app(
        llm,
        AgentSettings(api_key="default-key", model="default-model", base_url="https://default.local"),
        model_config_fetcher=lambda *args: ModelConfig(),
    ))

    response = client.post(
        "/prd-draft", headers=INTERNAL_HEADERS, json={"system_id": "sys-1", "content": "g"},
    )

    assert response.status_code == 200
    assert llm.configs[0].model == "default-model"
    assert llm.configs[0].api_key == "default-key"


def test_openai_client_only_adds_response_format_in_json_mode(monkeypatch):
    calls = []

    class Completions:
        def create(self, **kwargs):
            calls.append(kwargs)
            return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content="{}"))])

    fake_client = SimpleNamespace(chat=SimpleNamespace(completions=Completions()))
    monkeypatch.setattr("agent_service.llm.OpenAI", lambda **kwargs: fake_client)
    client = OpenAIChatClient(AgentSettings(model="test-model", api_key="test-key"))
    config = ModelConfig(model="test-model", api_key="test-key")

    client.complete("json", config, json_mode=True)
    client.complete("text", config)

    assert calls[0]["response_format"] == {"type": "json_object"}
    assert "response_format" not in calls[1]


def test_readiness_returns_only_product_model_state_without_api_key():
    client = TestClient(create_app(
        FakeLlmClient([]), AgentSettings(),
        model_config_fetcher=lambda *args: ModelConfig(managed=True, model="model-1", api_key="secret-key"),
    ))

    response = client.get("/readiness", headers=INTERNAL_HEADERS, params={"system_id": "sys-1"})

    assert response.status_code == 200
    assert response.json()["ready"] is True
    assert list(response.json()["stages"]) == ["prd"]
    assert "secret-key" not in response.text


def test_model_connection_uses_openai_compatible_endpoint(monkeypatch):
    captured = {}

    def post(url, **kwargs):
        captured.update(url=url, **kwargs)
        return SimpleNamespace(is_success=True, status_code=200)

    monkeypatch.setattr("agent_service.app.httpx.post", post)
    client = TestClient(create_app(
        FakeLlmClient([]), AgentSettings(worker_callback_token="internal-token"),
        model_config_fetcher=lambda *args: ModelConfig(
            managed=True, provider="openai-compat", model="gpt-test",
            base_url="https://models.example/v1", api_key="secret-key",
        ),
    ))

    response = client.post(
        "/model-connection-test", params={"system_id": "sys-1", "profile_id": "mp-1"},
        headers={"Authorization": "Bearer internal-token"},
    )

    assert response.json() == {"connected": True, "message": "连接正常"}
    assert captured["url"] == "https://models.example/v1/chat/completions"
    assert captured["headers"]["Authorization"] == "Bearer secret-key"
    assert captured["json"]["max_tokens"] == 1


def test_model_connection_returns_anthropic_failure_without_response_body(monkeypatch):
    captured = {}

    def post(url, **kwargs):
        captured.update(url=url, **kwargs)
        return SimpleNamespace(is_success=False, status_code=401)

    monkeypatch.setattr("agent_service.app.httpx.post", post)
    client = TestClient(create_app(
        FakeLlmClient([]), AgentSettings(worker_callback_token="internal-token"),
        model_config_fetcher=lambda *args: ModelConfig(
            managed=True, provider="anthropic", model="claude-test",
            base_url="https://models.example/anthropic", api_key="secret-key",
        ),
    ))

    response = client.post(
        "/model-connection-test", params={"system_id": "sys-1", "profile_id": "mp-1"},
        headers={"Authorization": "Bearer internal-token"},
    )

    assert response.json() == {"connected": False, "message": "连接失败（HTTP 401）"}
    assert captured["url"] == "https://models.example/anthropic/v1/messages"
    assert captured["headers"]["x-api-key"] == "secret-key"


def test_analyze_image_returns_observation_without_guessing_code():
    llm = FakeLlmClient([json.dumps({
        "page_title": "订单列表", "text_anchors": ["待发货订单"], "ui_elements": ["搜索按钮"],
        "error_messages": [], "user_visible_summary": "订单列表页显示待发货订单",
    }, ensure_ascii=False)])
    client = TestClient(create_app(
        llm,
        model_config_fetcher=lambda *args: ModelConfig(
            managed=True, model="vision-model", api_key="secret", supports_vision=True,
        ),
    ))

    response = client.post("/analyze-image?system_id=sys-1", content=b"image", headers={
        "Content-Type": "image/png", "Authorization": "Bearer dev-worker-token",
    })

    assert response.status_code == 200
    assert response.json()["page_title"] == "订单列表"
    assert "Never guess API endpoints" in llm.prompts[0]


def test_analyze_image_requires_vision_profile():
    llm = FakeLlmClient([])
    client = TestClient(create_app(
        llm,
        model_config_fetcher=lambda *args: ModelConfig(
            managed=True, model="text-model", api_key="secret", supports_vision=False,
        ),
    ))

    response = client.post("/analyze-image?system_id=sys-1", content=b"image", headers={
        "Content-Type": "image/png", "Authorization": "Bearer dev-worker-token",
    })

    assert response.status_code == 422
    assert llm.calls == 0


def test_analyze_image_reports_provider_without_vision_capability():
    request = httpx.Request("POST", "https://api.deepseek.com/chat/completions")
    llm = FailingVisionLlmClient(BadRequestError(
        "unknown variant image_url, expected text",
        response=httpx.Response(400, request=request),
        body=None,
    ))
    client = TestClient(create_app(
        llm,
        model_config_fetcher=lambda *args: ModelConfig(
            managed=True, provider="openai-compat", model="deepseek-v4-pro",
            api_key="secret", supports_vision=True,
        ),
    ))

    response = client.post("/analyze-image?system_id=sys-1", content=b"image", headers={
        "Content-Type": "image/png", "Authorization": "Bearer dev-worker-token",
    })

    assert response.status_code == 422
    assert response.json()["detail"] == "当前模型不支持图片理解，请更换支持 Vision 的模型 Profile"


def test_analyze_image_reports_provider_outage():
    request = httpx.Request("POST", "https://models.example/chat/completions")
    llm = FailingVisionLlmClient(APIConnectionError(request=request))
    client = TestClient(create_app(
        llm,
        model_config_fetcher=lambda *args: ModelConfig(
            managed=True, model="vision-model", api_key="secret", supports_vision=True,
        ),
    ))

    response = client.post("/analyze-image?system_id=sys-1", content=b"image", headers={
        "Content-Type": "image/png", "Authorization": "Bearer dev-worker-token",
    })

    assert response.status_code == 502
    assert response.json()["detail"] == "图片分析服务暂时不可用"


def test_analyze_image_rejects_direct_unauthorized_call():
    llm = FakeLlmClient([])
    client = TestClient(create_app(llm))

    response = client.post("/analyze-image?system_id=sys-1", content=b"image", headers={"Content-Type": "image/png"})

    assert response.status_code == 401
    assert llm.calls == 0


def test_runner_business_endpoints_require_internal_token():
    client = TestClient(create_app(
        FakeLlmClient([]),
        model_config_fetcher=lambda *args: ModelConfig(),
    ))

    assert client.post("/prd-draft", json={"system_id": "sys-1", "content": "g"}).status_code == 401
    assert client.get("/readiness", params={"system_id": "sys-1"}).status_code == 401
    assert client.post(
        "/model-connection-test", params={"system_id": "sys-1", "profile_id": "mp-1"},
    ).status_code == 401


def test_healthz_stays_public():
    client = TestClient(create_app(
        FakeLlmClient([]),
        model_config_fetcher=lambda *args: ModelConfig(),
    ))

    assert client.get("/healthz").status_code == 200
