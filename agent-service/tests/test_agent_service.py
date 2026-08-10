import json
import logging
from pathlib import Path
from types import SimpleNamespace

from fastapi.testclient import TestClient
import httpx
from openai import APIConnectionError, BadRequestError
import pytest

from agent_service.app import create_app
from agent_service.llm import (
    AnthropicAdapter,
    LlmClient,
    ModelConfig,
    OpenAICompatibleAdapter,
    RoutedLlmClient,
    StructuredOutputFormat,
)
from agent_service.model_errors import ModelCallError, ModelErrorCode
from agent_service.settings import AgentSettings


INTERNAL_HEADERS = {"Authorization": "Bearer dev-worker-token"}


class FakeLlmClient(LlmClient):
    def __init__(self, responses: list[str]) -> None:
        self.responses = responses
        self.calls = 0
        self.configs: list[ModelConfig] = []
        self.prompts: list[str] = []
        self.output_formats: list[StructuredOutputFormat | None] = []
        self.images: list[bytes] = []
        self.connection_error: ModelCallError | None = None

    def complete(self, prompt: str, config: ModelConfig,
                 output_format: StructuredOutputFormat | None = None) -> str:
        self.calls += 1
        self.prompts.append(prompt)
        self.configs.append(config)
        self.output_formats.append(output_format)
        return self.responses.pop(0)

    def complete_vision(self, prompt: str, image: bytes, content_type: str, config: ModelConfig,
                        output_format: StructuredOutputFormat | None = None) -> str:
        self.calls += 1
        self.prompts.append(prompt)
        self.configs.append(config)
        self.output_formats.append(output_format)
        self.images.append(image)
        return self.responses.pop(0)

    def test_connection(self, config: ModelConfig) -> None:
        self.configs.append(config)
        if self.connection_error:
            raise self.connection_error


class FailingVisionLlmClient(FakeLlmClient):
    def __init__(self, error: Exception) -> None:
        super().__init__([])
        self.error = error

    def complete_vision(self, prompt: str, image: bytes, content_type: str, config: ModelConfig,
                        output_format: StructuredOutputFormat | None = None) -> str:
        raise self.error


def test_legacy_execution_endpoints_are_removed():
    client = TestClient(create_app(FakeLlmClient([])))

    assert client.post("/plan", json={}).status_code == 404
    assert client.post("/execute", json={}).status_code == 404


def test_prd_draft_retries_json_and_limits_clarification_to_business_missing_fields():
    llm = FakeLlmClient([
        "not json",
        '{"patch":{"title":"登录页错误提示","goal":"做登录页","scope":"code_change","acceptanceCriteria":[]},"assistant_message":"请补充验收标准。","citations":{}}',
    ])
    client = TestClient(create_app(
        llm,
        model_config_fetcher=lambda *args: ModelConfig(managed=True, model="model", api_key="secret"),
    ))

    response = client.post("/prd-draft", headers=INTERNAL_HEADERS, json={
        "system_id": "sys-1",
        "content": "做登录页",
        "current_draft": {},
        "missing_fields": ["acceptance_criteria"],
        "conversation_history": [{"role": "user", "content": "先做登录页"}],
        "context_items": [{"refId": "MEM:mem-1", "type": "memory", "content": "面向一线业务人员"}],
    })

    assert response.status_code == 200
    assert response.json()["patch"]["acceptanceCriteria"] == []
    assert "验收标准" in response.json()["assistant_message"]
    assert "面向一线业务人员" in llm.prompts[0]
    assert "Never invent a refId" in llm.prompts[0]
    assert "Never return targets" in llm.prompts[0]
    assert "You are a business product manager" in llm.prompts[0]
    assert "Planning owns implementation" in llm.prompts[0]
    assert "Ask at most one question per missing field and at most three questions total" in llm.prompts[0]
    assert "推荐答案：..." in llm.prompts[0]
    assert "endpoint paths, HTTP methods, authentication" in llm.prompts[0]
    assert [item.mode for item in llm.output_formats if item] == ["json_object", "json_object"]
    assert "JSONDecodeError" in llm.prompts[1]
    assert "Previous output:\nnot json" in llm.prompts[1]
    assert '"citations"' in llm.prompts[0]


def test_prd_draft_excludes_system_knowledge_and_stops_questions_when_complete():
    llm = FakeLlmClient([
        '{"patch":{"scope":"code_change"},"assistant_message":"草稿已完整，请确认。","citations":{}}',
    ])
    client = TestClient(create_app(
        llm,
        model_config_fetcher=lambda *args: ModelConfig(managed=True, model="model", api_key="secret"),
    ))

    response = client.post("/prd-draft", headers=INTERNAL_HEADERS, json={
        "system_id": "sys-1",
        "content": "接口必须沿用现有网关限制",
        "current_draft": {
            "title": "心跳能力",
            "goal": "让业务人员确认服务可用",
            "acceptanceCriteria": ["业务人员能看到明确的可用状态"],
        },
        "missing_fields": [],
        "conversation_history": [],
        "context_items": [
            {
                "refId": "KN:health",
                "type": "system_knowledge",
                "content": "路由: /health\n接口: GET /internal/health\n代码位置: src/health.py",
            },
            {
                "refId": "MEM:audience",
                "type": "memory",
                "content": "业务状态需要让值班负责人理解",
            },
        ],
    })

    assert response.status_code == 200
    assert "接口必须沿用现有网关限制" in llm.prompts[0]
    assert "业务状态需要让值班负责人理解" in llm.prompts[0]
    assert "GET /internal/health" not in llm.prompts[0]
    assert "src/health.py" not in llm.prompts[0]
    assert "When Missing fields is empty, ask no questions" in llm.prompts[0]


def test_prd_draft_accepts_shared_fixture():
    fixture = Path(__file__).parents[2] / "docs" / "fixtures" / "prd-draft-request.json"
    llm = FakeLlmClient([
        '{"patch":{"title":"登录页","goal":"做登录页","scope":"code_change","acceptanceCriteria":[]},"assistant_message":"还缺验收标准，请补充。","citations":{}}',
    ])
    client = TestClient(create_app(
        llm,
        model_config_fetcher=lambda *args: ModelConfig(managed=True, model="model", api_key="secret"),
    ))

    response = client.post("/prd-draft", headers=INTERNAL_HEADERS, json=json.loads(fixture.read_text()))

    assert response.status_code == 200
    assert response.json()["patch"]["scope"] == "code_change"


def test_prd_draft_rejects_model_owned_targets_and_accepts_repaired_patch():
    llm = FakeLlmClient([
        json.dumps({
            "patch": {
                "title": "登录页", "goal": "修复登录页", "scope": "code_change",
                "acceptanceCriteria": ["错误可见"],
                "targets": [{"entryId": "model-invented"}],
            },
            "assistant_message": "已完成",
            "citations": {"title": ["MSG:msg-1"]},
        }, ensure_ascii=False),
        json.dumps({
            "patch": {
                "title": "登录页", "goal": "修复登录页", "scope": "code_change",
                "acceptanceCriteria": ["错误可见"],
            },
            "assistant_message": "已完成",
            "citations": {"title": ["MSG:msg-1"]},
        }, ensure_ascii=False),
    ])
    client = TestClient(create_app(
        llm,
        model_config_fetcher=lambda *args: ModelConfig(managed=True, model="model", api_key="secret"),
    ))

    response = client.post("/prd-draft", headers=INTERNAL_HEADERS, json={
        "system_id": "sys-1", "content": "修复登录页",
    })

    assert response.status_code == 200
    assert "targets" not in response.json()["patch"]
    assert "extra_forbidden" in llm.prompts[1]


def test_prd_draft_requires_citation_lists():
    invalid = json.dumps({
        "patch": {
            "title": "登录页", "goal": "修复登录页", "scope": "code_change",
            "acceptanceCriteria": ["错误可见"],
        },
        "assistant_message": "已完成",
        "citations": {"title": "MSG:msg-1", "goal": ["MEM:mem-1"]},
    }, ensure_ascii=False)
    repaired = json.dumps({
        "patch": {
            "title": "登录页", "goal": "修复登录页", "scope": "code_change",
            "acceptanceCriteria": ["错误可见"],
        },
        "assistant_message": "已完成",
        "citations": {"title": ["MSG:msg-1"], "goal": ["MEM:mem-1"]},
    }, ensure_ascii=False)
    llm = FakeLlmClient([invalid, repaired])
    client = TestClient(create_app(
        llm,
        model_config_fetcher=lambda *args: ModelConfig(managed=True, model="model", api_key="secret"),
    ))

    response = client.post("/prd-draft", headers=INTERNAL_HEADERS, json={
        "system_id": "sys-1", "content": "修复登录页",
    })

    assert response.status_code == 200
    assert response.json()["citations"] == {"title": ["MSG:msg-1"], "goal": ["MEM:mem-1"]}
    assert "list_type" in llm.prompts[1]


def test_prd_draft_repairs_multiline_acceptance_criteria_to_strict_list():
    invalid = json.dumps({
        "patch": {
            "title": "登录页", "goal": "修复登录页", "scope": "code_change",
            "acceptanceCriteria": " 显示中文错误 \n\n 保留用户输入 ",
        },
        "assistant_message": "已完成",
        "citations": {},
    }, ensure_ascii=False)
    repaired = json.dumps({
        "patch": {
            "title": "登录页", "goal": "修复登录页", "scope": "code_change",
            "acceptanceCriteria": ["显示中文错误", "保留用户输入"],
        },
        "assistant_message": "已完成",
        "citations": {},
    }, ensure_ascii=False)
    llm = FakeLlmClient([invalid, repaired])
    client = TestClient(create_app(
        llm,
        model_config_fetcher=lambda *args: ModelConfig(managed=True, model="model", api_key="secret"),
    ))

    response = client.post("/prd-draft", headers=INTERNAL_HEADERS, json={
        "system_id": "sys-1", "content": "修复登录页",
    })

    assert response.status_code == 200
    assert response.json()["patch"]["acceptanceCriteria"] == ["显示中文错误", "保留用户输入"]
    assert "list_type" in llm.prompts[1]


def test_prd_draft_rejects_object_acceptance_criteria_after_failed_repair():
    invalid = json.dumps({
        "patch": {
            "title": "登录页", "goal": "修复登录页", "scope": "code_change",
            "acceptanceCriteria": [
                {"text": "显示中文错误", "priority": "high"},
                {"description": "保留用户输入"},
            ],
        },
        "assistant_message": "已完成",
        "citations": {},
    }, ensure_ascii=False)
    llm = FakeLlmClient([invalid, invalid])
    client = TestClient(create_app(
        llm,
        model_config_fetcher=lambda *args: ModelConfig(managed=True, model="model", api_key="secret"),
    ))

    response = client.post("/prd-draft", headers=INTERNAL_HEADERS, json={
        "system_id": "sys-1", "content": "修复登录页",
    })

    assert response.status_code == 422
    assert response.json()["code"] == "MODEL_OUTPUT_INVALID"


def test_prd_draft_returns_typed_error_after_failed_repair():
    invalid = json.dumps({
        "patch": {
            "title": "登录页", "goal": "修复登录页", "scope": "code_change",
            "acceptanceCriteria": [{"priority": "high"}],
        },
        "assistant_message": "已完成",
        "citations": {},
    }, ensure_ascii=False)
    llm = FakeLlmClient([invalid, invalid])
    client = TestClient(create_app(
        llm,
        model_config_fetcher=lambda *args: ModelConfig(managed=True, model="model", api_key="secret"),
    ))

    response = client.post("/prd-draft", headers=INTERNAL_HEADERS, json={
        "system_id": "sys-1", "content": "修复登录页",
    })

    assert response.status_code == 422
    assert response.json() == {"code": "MODEL_OUTPUT_INVALID", "message": "模型输出不符合业务契约"}
    assert "acceptanceCriteria" in llm.prompts[1]


@pytest.mark.parametrize("mode,has_schema_prompt", [
    ("json_schema", False),
    ("json_object", True),
    ("prompt_only", True),
])
def test_structured_runner_supports_all_profile_modes(mode, has_schema_prompt):
    llm = FakeLlmClient([json.dumps({
        "patch": {"title": "t", "goal": "g", "scope": "code_change", "acceptanceCriteria": []},
        "assistant_message": "ok", "citations": {},
    })])
    client = TestClient(create_app(
        llm,
        model_config_fetcher=lambda *args: ModelConfig(
            managed=True, model="model", api_key="secret", structured_output=mode,
        ),
    ))

    response = client.post("/prd-draft", headers=INTERNAL_HEADERS, json={
        "system_id": "sys-1", "content": "g",
    })

    assert response.status_code == 200
    assert llm.output_formats[0].mode == mode
    assert ('"title":"DraftResult"' in llm.prompts[0]) is has_schema_prompt


def test_legacy_supports_vision_populates_image_input():
    config = ModelConfig.model_validate({"supports_vision": True})

    assert config.image_input is True
    assert config.structured_output == "json_object"


def test_system_model_config_overrides_global_defaults(caplog):
    llm = FakeLlmClient([
        '{"patch":{"title":"t","goal":"g","scope":"code_change","acceptanceCriteria":[]},"assistant_message":"ok","citations":{}}',
    ])
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
    llm = FakeLlmClient([
        '{"patch":{"title":"t","goal":"g","scope":"code_change","acceptanceCriteria":[]},"assistant_message":"ok","citations":{}}',
    ])
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


def test_openai_adapter_maps_text_image_and_structured_modes(monkeypatch):
    calls = []
    clients = []

    class Completions:
        def create(self, **kwargs):
            calls.append(kwargs)
            return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content="{}"))])

    fake_client = SimpleNamespace(chat=SimpleNamespace(completions=Completions()))
    monkeypatch.setattr("agent_service.llm.OpenAI", lambda **kwargs: clients.append(kwargs) or fake_client)
    adapter = OpenAICompatibleAdapter()
    config = ModelConfig(model="test-model", api_key="test-key")
    schema = {"type": "object", "properties": {"value": {"type": "string"}}}

    adapter.complete("schema", config, StructuredOutputFormat("json_schema", "Result", schema))
    adapter.complete("object", config, StructuredOutputFormat("json_object", "Result", schema))
    adapter.complete("prompt", config, StructuredOutputFormat("prompt_only", "Result", schema))
    adapter.complete("vision", config, image=b"image-secret", content_type="image/png", max_tokens=1)

    assert calls[0]["response_format"]["type"] == "json_schema"
    assert calls[0]["response_format"]["json_schema"]["schema"] == schema
    assert calls[1]["response_format"] == {"type": "json_object"}
    assert "response_format" not in calls[2]
    assert calls[3]["messages"][0]["content"][1]["type"] == "image_url"
    assert calls[3]["max_tokens"] == 1
    assert clients[0]["api_key"] == "test-key"
    assert clients[0]["timeout"] == 600


def test_anthropic_adapter_maps_image_and_json_schema(monkeypatch):
    captured = {}

    class Response:
        status_code = 200

        def raise_for_status(self):
            return None

        def json(self):
            return {"content": [{"type": "text", "text": "ok"}]}

    def post(url, **kwargs):
        captured.update(url=url, **kwargs)
        return Response()

    monkeypatch.setattr("agent_service.llm.httpx.post", post)
    result = AnthropicAdapter().complete(
        "look", ModelConfig(provider="anthropic", model="claude", api_key="secret",
                            base_url="https://models.example/v1"),
        StructuredOutputFormat("json_schema", "Result", {"type": "object"}),
        image=b"image", content_type="image/png",
    )

    assert result == "ok"
    assert captured["url"] == "https://models.example/v1/messages"
    assert captured["headers"]["x-api-key"] == "secret"
    assert captured["json"]["messages"][0]["content"][1]["type"] == "image"
    assert captured["json"]["output_config"]["format"]["type"] == "json_schema"
    assert captured["timeout"] == 600


def test_routed_client_injects_configured_model_request_timeout():
    client = RoutedLlmClient(AgentSettings(model_request_timeout_seconds=321))

    assert client.registry.get("openai").timeout_seconds == 321
    assert client.registry.get("openai-compat").timeout_seconds == 321
    assert client.registry.get("anthropic").timeout_seconds == 321


def test_openai_adapter_maps_image_rejection_to_typed_capability_error(monkeypatch):
    request = httpx.Request("POST", "https://models.example/chat/completions")

    class Completions:
        def create(self, **_kwargs):
            raise BadRequestError(
                "image rejected", response=httpx.Response(400, request=request), body=None,
            )

    monkeypatch.setattr(
        "agent_service.llm.OpenAI",
        lambda **_kwargs: SimpleNamespace(chat=SimpleNamespace(completions=Completions())),
    )

    with pytest.raises(ModelCallError) as raised:
        OpenAICompatibleAdapter().complete(
            "look", ModelConfig(model="model", api_key="secret"),
            image=b"image", content_type="image/png",
        )

    assert raised.value.code == ModelErrorCode.CAPABILITY_UNSUPPORTED


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


def test_model_connection_is_separate_and_records_check_time():
    llm = FakeLlmClient([])
    client = TestClient(create_app(
        llm, AgentSettings(worker_callback_token="internal-token"),
        model_config_fetcher=lambda *args: ModelConfig(
            managed=True, provider="openai-compat", model="gpt-test",
            base_url="https://models.example/v1", api_key="secret-key",
        ),
    ))

    response = client.post(
        "/model-connection-test", params={"system_id": "sys-1", "profile_id": "mp-1"},
        headers={"Authorization": "Bearer internal-token"},
    )

    assert response.json()["connected"] is True
    assert response.json()["message"] == "连接正常"
    assert response.json()["checkedAt"]
    assert llm.configs[0].api_key == "secret-key"


def test_model_connection_maps_provider_failure_without_response_body():
    llm = FakeLlmClient([])
    llm.connection_error = ModelCallError(ModelErrorCode.PROVIDER_ERROR, "模型服务调用失败", 502)
    client = TestClient(create_app(
        llm, AgentSettings(worker_callback_token="internal-token"),
        model_config_fetcher=lambda *args: ModelConfig(
            managed=True, provider="anthropic", model="claude-test",
            base_url="https://models.example/anthropic", api_key="secret-key",
        ),
    ))

    response = client.post(
        "/model-connection-test", params={"system_id": "sys-1", "profile_id": "mp-1"},
        headers={"Authorization": "Bearer internal-token"},
    )

    assert response.json()["connected"] is False
    assert response.json()["code"] == "MODEL_CONNECTION_FAILED"
    assert "secret-key" not in response.text


def test_structured_and_image_capability_checks_use_synthetic_inputs():
    llm = FakeLlmClient(['{"marker":"asterism","count":1}', "红色"])
    client = TestClient(create_app(
        llm, AgentSettings(worker_callback_token="internal-token"),
        model_config_fetcher=lambda *args: ModelConfig(
            managed=True, provider="openai-compat", model="vision-model", api_key="secret-key",
            image_input=True, structured_output="json_object",
        ),
    ))
    headers = {"Authorization": "Bearer internal-token"}

    structured = client.post("/model-capability-test", headers=headers, params={
        "system_id": "sys-1", "profile_id": "mp-1", "capability": "structured_output",
    })
    image = client.post("/model-capability-test", headers=headers, params={
        "system_id": "sys-1", "profile_id": "mp-1", "capability": "image_input",
    })

    assert structured.json()["supported"] is True
    assert structured.json()["checkedAt"]
    assert image.json()["supported"] is True
    assert llm.images and llm.images[0] != b"image"
    assert "business" not in " ".join(llm.prompts).lower()


def test_logs_exclude_keys_images_and_full_model_output(caplog):
    secret_output = "FULL_MODEL_RESPONSE_SHOULD_NOT_BE_LOGGED"
    llm = FakeLlmClient([secret_output, secret_output])
    client = TestClient(create_app(
        llm,
        model_config_fetcher=lambda *args: ModelConfig(
            managed=True, model="model", api_key="api-key-should-not-be-logged",
        ),
    ))
    caplog.set_level(logging.INFO)

    response = client.post("/prd-draft", headers=INTERNAL_HEADERS, json={
        "system_id": "sys-1", "content": "private-business-content",
    })

    assert response.status_code == 422
    assert "api-key-should-not-be-logged" not in caplog.text
    assert secret_output not in caplog.text
    assert "private-business-content" not in caplog.text


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
    assert response.json()["ui_elements"] == [{"type": "element", "description": "搜索按钮"}]
    assert "Never guess API endpoints" in llm.prompts[0]


def test_analyze_image_accepts_structured_ui_elements():
    llm = FakeLlmClient([json.dumps({
        "page_title": "登录",
        "text_anchors": ["登录失败"],
        "ui_elements": [{"type": "button", "description": "蓝色登录按钮"}],
        "error_messages": ["密码错误"],
        "user_visible_summary": "登录失败",
    }, ensure_ascii=False)])
    client = TestClient(create_app(
        llm,
        model_config_fetcher=lambda *args: ModelConfig(
            managed=True, model="vision-model", api_key="secret", image_input=True,
        ),
    ))

    response = client.post("/analyze-image?system_id=sys-1", content=b"image", headers={
        "Content-Type": "image/png", "Authorization": "Bearer dev-worker-token",
    })

    assert response.status_code == 200
    assert response.json()["ui_elements"] == [{"type": "button", "description": "蓝色登录按钮"}]


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
    llm = FailingVisionLlmClient(ModelCallError(
        ModelErrorCode.CAPABILITY_UNSUPPORTED, "模型不支持声明的输入或输出能力", 422,
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
    assert response.json()["code"] == "MODEL_CAPABILITY_UNSUPPORTED"


def test_analyze_image_reports_provider_outage():
    llm = FailingVisionLlmClient(ModelCallError(
        ModelErrorCode.CONNECTION_FAILED, "模型连接失败", 502,
    ))
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
    assert response.json()["code"] == "MODEL_CONNECTION_FAILED"


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

    response = client.get("/healthz")

    assert response.status_code == 200
    assert response.json() == {"ok": True}
