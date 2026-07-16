import json
from pathlib import Path
from types import SimpleNamespace

from fastapi.testclient import TestClient

from agent_service.app import create_app
from agent_service.llm import LlmClient, ModelConfig, OpenAIChatClient
from agent_service.settings import AgentSettings


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


def test_plan_retries_once_when_llm_returns_invalid_json():
    llm = FakeLlmClient([
        "not json",
        '{"steps":["改 README"],"target_files":["README.md"],"test_plan":["pytest"],"risks":[]}',
    ])
    client = TestClient(create_app(llm))

    response = client.post("/plan", json={
        "prd": {"title": "t", "goal": "g", "acceptance_criteria": [], "draft_json": {}},
        "system_id": "sys-1",
        "repo_summary": "README.md",
        "memories": [],
        "allowed_paths": ["README.md"],
        "context_manifest_id": "manifest-1",
    })

    assert response.status_code == 200
    assert response.json()["steps"] == ["改 README"]
    assert llm.calls == 2
    assert llm.json_modes == [True, True]
    assert "All four values must be JSON arrays of strings" in llm.prompts[0]


def test_execute_rejects_response_without_git_diff():
    client = TestClient(create_app(FakeLlmClient(["plain text"])))

    response = client.post("/execute", json={
        "case_id": "case-1",
        "work_item_id": "wi-1",
        "system_id": "sys-1",
        "repo_path": "/tmp/repo",
        "goal": "g",
        "acceptance_criteria": [],
        "plan": {"steps": ["改 README"], "target_files": [], "test_plan": [], "risks": []},
        "memories": [],
        "context_manifest_id": "manifest-1",
        "allowed_paths": [],
        "forbidden_paths": [],
        "test_commands": [],
    })

    assert response.status_code == 422


def test_plan_returns_400_after_two_invalid_json_responses():
    client = TestClient(create_app(FakeLlmClient(["no", "still no"])))

    response = client.post("/plan", json={
        "prd": {"title": "t", "goal": "g", "acceptance_criteria": [], "draft_json": {}},
        "system_id": "sys-1",
        "repo_summary": "",
        "memories": [],
        "allowed_paths": [],
        "context_manifest_id": "manifest-1",
    })

    assert response.status_code == 400


def test_execute_returns_git_diff():
    diff = "diff --git a/README.md b/README.md\n"
    llm = FakeLlmClient([diff])
    client = TestClient(create_app(llm))

    response = client.post("/execute", json={
        "case_id": "case-1",
        "work_item_id": "wi-1",
        "system_id": "sys-1",
        "repo_path": "/tmp/repo",
        "goal": "g",
        "acceptance_criteria": [],
        "plan": {"steps": ["改 README"], "target_files": [], "test_plan": [], "risks": []},
        "memories": [],
        "context_manifest_id": "manifest-1",
        "allowed_paths": [],
        "forbidden_paths": [],
        "test_commands": [],
    })

    assert response.status_code == 200
    assert response.json()["diff_patch"] == diff
    assert llm.json_modes == [False]


def test_execute_prompt_includes_file_contents_and_previous_attempt():
    diff = "diff --git a/README.md b/README.md\n"
    llm = FakeLlmClient([diff])
    client = TestClient(create_app(llm))

    response = client.post("/execute", json={
        "case_id": "case-1",
        "work_item_id": "wi-1",
        "system_id": "sys-1",
        "repo_path": "/tmp/repo",
        "goal": "g",
        "acceptance_criteria": [],
        "plan": {"steps": ["改 README"], "target_files": ["README.md"], "test_plan": [], "risks": []},
        "memories": [],
        "context_manifest_id": "manifest-1",
        "file_listing": "README.md",
        "file_contents": {"README.md": "asterism\n"},
        "previous_attempt": {"diff": "bad", "apply_error": "patch failed"},
    })

    assert response.status_code == 200
    assert "asterism" in llm.prompts[0]
    assert "patch failed" in llm.prompts[0]


def test_system_model_config_overrides_global_defaults(caplog):
    llm = FakeLlmClient(['{"steps":["s"],"target_files":[],"test_plan":[],"risks":[]}'])
    client = TestClient(create_app(
        llm,
        AgentSettings(api_key="default-key", model="default-model", base_url="https://default.local"),
        model_config_fetcher=lambda system_id, agent, profile_id="": ModelConfig(
            managed=True,
            provider="openai",
            model="system-model",
            base_url="https://system.local",
            api_key="system-secret",
        ),
    ))

    response = client.post("/plan", json={
        "prd": {"title": "t", "goal": "g", "acceptance_criteria": [], "draft_json": {}},
        "system_id": "sys-1",
        "repo_summary": "",
        "memories": [],
        "allowed_paths": [],
        "context_manifest_id": "manifest-1",
    })

    assert response.status_code == 200
    assert llm.configs[0].model == "system-model"
    assert llm.configs[0].api_key == "system-secret"
    assert "system-secret" not in caplog.text


def test_missing_system_model_config_falls_back_to_global_defaults():
    llm = FakeLlmClient(["diff --git a/README.md b/README.md\n"])
    client = TestClient(create_app(
        llm,
        AgentSettings(api_key="default-key", model="default-model", base_url="https://default.local"),
        model_config_fetcher=lambda system_id, agent, profile_id="": ModelConfig(),
    ))

    response = client.post("/execute", json={
        "case_id": "case-1",
        "work_item_id": "wi-1",
        "system_id": "sys-1",
        "repo_path": "/tmp/repo",
        "goal": "g",
        "acceptance_criteria": [],
        "plan": {"steps": ["改 README"], "target_files": [], "test_plan": [], "risks": []},
        "memories": [],
        "context_manifest_id": "manifest-1",
        "allowed_paths": [],
        "forbidden_paths": [],
        "test_commands": [],
    })

    assert response.status_code == 200
    assert llm.configs[0].model == "default-model"
    assert llm.configs[0].api_key == "default-key"


def test_prd_draft_retries_json_and_asks_acceptance_in_chinese():
    llm = FakeLlmClient([
        "not json",
        '{"title":"登录页错误提示","draft":{"goal":"做登录页","scope":"code_change","acceptanceCriteria":[]},"missing_fields":["acceptance_criteria"],"assistant_message":"请补充验收标准。"}',
    ])
    client = TestClient(create_app(llm))

    response = client.post("/prd-draft", json={
        "system_id": "sys-1",
        "content": "做登录页",
        "current_draft": {},
        "missing_fields": [],
        "conversation_history": [{"role": "user", "content": "先做登录页"}],
        "approved_memories": ["只能改 src"],
    })

    assert response.status_code == 200
    assert response.json()["missing_fields"] == ["acceptance_criteria"]
    assert "验收标准" in response.json()["assistant_message"]
    assert "只能改 src" in llm.prompts[0]
    assert llm.json_modes == [True, True]


def test_prd_draft_accepts_shared_fixture():
    fixture = Path(__file__).parents[2] / "docs" / "fixtures" / "prd-draft-request.json"
    llm = FakeLlmClient([
        '{"title":"登录页","draft":{"goal":"做登录页","scope":"code_change","acceptanceCriteria":[]},"missing_fields":["acceptance_criteria"],"assistant_message":"还缺验收标准，请补充。"}',
    ])
    client = TestClient(create_app(llm))

    response = client.post("/prd-draft", json=json.loads(fixture.read_text()))

    assert response.status_code == 200
    assert response.json()["draft"]["scope"] == "code_change"


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


def test_execute_resolves_selected_profile_reference_without_receiving_key():
    seen = []
    llm = FakeLlmClient(["diff --git a/README.md b/README.md\n"])

    def fetch(system_id, agent, profile_id):
        seen.append((system_id, agent, profile_id))
        return ModelConfig(managed=True, configured=True, model_id=profile_id, model="role-model", api_key="internal-key")

    client = TestClient(create_app(llm, model_config_fetcher=fetch))
    response = client.post("/execute", json={
        "case_id": "case", "work_item_id": "wi", "system_id": "sys", "repo_path": "/tmp/repo",
        "goal": "g", "plan": {"steps": ["s"]}, "model_profile_id": "mp-role",
    })

    assert response.status_code == 200
    assert seen == [("sys", "developer", "mp-role")]
    assert llm.configs[0].api_key == "internal-key"


def test_plan_prompt_contains_only_available_agent_metadata():
    llm = FakeLlmClient(['{"steps":["s"],"target_files":[],"test_plan":[],"risks":[],"assignments":[]}'])
    client = TestClient(create_app(llm))
    response = client.post("/plan", json={
        "system_id": "sys", "prd": {"goal": "g"}, "context_manifest_id": "m",
        "available_agents": [{"name": "frontend", "engine": "claude_sdk", "path_scope": ["web"]}],
    })

    assert response.status_code == 200
    assert "frontend" in llm.prompts[0]
    assert "claude_sdk" in llm.prompts[0]
    assert "api_key" not in llm.prompts[0]


def test_readiness_returns_model_state_without_api_key():
    client = TestClient(create_app(
        FakeLlmClient([]),
        AgentSettings(),
        model_config_fetcher=lambda system_id, agent, profile_id="": ModelConfig(managed=True, model="model-1", api_key="secret-key"),
    ))

    response = client.get("/readiness", params={"system_id": "sys-1"})

    assert response.status_code == 200
    assert response.json()["ready"] is True
    assert response.json()["stages"]["prd"]["model"] == "model-1"
    assert "secret-key" not in response.text


def test_each_endpoint_uses_its_stage_model():
    stages = []

    def fetch(system_id, agent, profile_id=""):
        stages.append(agent)
        return ModelConfig(managed=True, model=f"{agent}-model", api_key=f"{agent}-key")

    llm = FakeLlmClient([
        '{"title":"t","draft":{"goal":"g"},"missing_fields":[],"assistant_message":"ok"}',
        '{"steps":[],"target_files":[],"test_plan":[],"risks":[]}',
        "diff --git a/a b/a\n",
    ])
    client = TestClient(create_app(llm, AgentSettings(), model_config_fetcher=fetch))

    assert client.post("/prd-draft", json={"system_id":"sys-1","content":"g"}).status_code == 200
    assert client.post("/plan", json={
        "system_id":"sys-1", "prd":{"goal":"g"}, "context_manifest_id":"m"
    }).status_code == 200
    assert client.post("/execute", json={
        "case_id":"c", "work_item_id":"w", "system_id":"sys-1", "repo_path":"/tmp",
        "goal":"g", "plan":{}
    }).status_code == 200

    assert stages == ["product", "planner", "developer"]
    assert [config.model for config in llm.configs] == ["product-model", "planner-model", "developer-model"]


def test_analyze_image_returns_observation_without_guessing_code():
    llm = FakeLlmClient([json.dumps({
        "page_title": "订单列表",
        "text_anchors": ["待发货订单"],
        "ui_elements": ["搜索按钮"],
        "error_messages": [],
        "user_visible_summary": "订单列表页显示待发货订单",
    }, ensure_ascii=False)])
    client = TestClient(create_app(
        llm,
        model_config_fetcher=lambda system_id, agent, profile_id="": ModelConfig(
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
        model_config_fetcher=lambda system_id, agent, profile_id="": ModelConfig(
            managed=True, model="text-model", api_key="secret", supports_vision=False,
        ),
    ))

    response = client.post("/analyze-image?system_id=sys-1", content=b"image", headers={
        "Content-Type": "image/png", "Authorization": "Bearer dev-worker-token",
    })

    assert response.status_code == 422
    assert "Vision" in response.text
    assert llm.calls == 0


def test_analyze_image_rejects_direct_unauthorized_call():
    llm = FakeLlmClient([])
    client = TestClient(create_app(llm))

    response = client.post("/analyze-image?system_id=sys-1", content=b"image", headers={"Content-Type": "image/png"})

    assert response.status_code == 401
    assert llm.calls == 0


def test_plan_prompt_marks_confirmed_targets_as_untrusted_hint():
    llm = FakeLlmClient(['{"steps":[],"target_files":[],"test_plan":[],"risks":[]}'])
    client = TestClient(create_app(llm))

    response = client.post("/plan", json={
        "system_id": "sys", "prd": {"goal": "g", "draft_json": {"targets": [{"title": "订单列表"}]}},
        "context_manifest_id": "m",
    })

    assert response.status_code == 200
    assert "疑似相关，以实际代码为准" in llm.prompts[0]
    assert "订单列表" in llm.prompts[0]
