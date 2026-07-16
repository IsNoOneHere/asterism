import asyncio
import json
import os
import stat
import subprocess

from claude_agent_sdk import AssistantMessage, ResultMessage, TextBlock, ToolUseBlock

from asterism_worker.agent_config import EngineConfig, ModelProfile
from asterism_worker.activities.execution import apply_patch_to_repo
from asterism_worker.contracts import ExecutionPlan, ExecutionRequest, HandoffContext
from asterism_worker.providers.claude_sdk import CLAUDE_DISALLOWED_TOOLS, CLAUDE_TOOLS, ClaudeSdkExecutionProvider


def init_repo(path):
    path.mkdir()
    (path / "README.md").write_text("asterism\n")
    subprocess.run(["git", "init"], cwd=path, check=True, capture_output=True)
    subprocess.run(["git", "add", "README.md"], cwd=path, check=True, capture_output=True)
    subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@example.invalid", "commit", "-m", "init"],
                   cwd=path, check=True, capture_output=True)


def execution_request(repo):
    return ExecutionRequest(
        case_id="case-1",
        work_item_id="wi-1",
        system_id="system-1",
        repo_path=str(repo),
        goal="更新 README 并新增模块",
        acceptance_criteria=["README 包含 v5"],
        plan=ExecutionPlan(steps=["修改 README", "新增 src/app.py"]),
        memories=[{"content": "保留现有入口"}],
        context_manifest_id="manifest-1",
        allowed_paths=["README.md", "src"],
        forbidden_paths=["secrets"],
        handoff=[HandoffContext(
            role="frontend",
            summary="前端完成",
            diff_patch="diff --git a/web/app.ts b/web/app.ts\n",
            interface_notes="新增登录参数。",
        )],
    )


def provider(artifacts, query_fn, *, key="test-key", base_url="", model="", effort="", event=None):
    return ClaudeSdkExecutionProvider(
        ModelProfile(provider="anthropic", api_key=key, base_url=base_url, model=model),
        EngineConfig(name="claude_sdk", max_turns=12, effort_level=effort),
        str(artifacts),
        {"event": event, "query": query_fn},
    )


def test_claude_sdk_collects_diff_and_writes_transcript(tmp_path, monkeypatch):
    monkeypatch.setattr(os, "geteuid", lambda: 501)
    repo = tmp_path / "repo"
    init_repo(repo)
    (repo / ".claude").mkdir()
    (repo / ".claude" / "settings.json").write_text('{"hooks":{"PreToolUse":[]}}\n')
    subprocess.run(["git", "add", ".claude/settings.json"], cwd=repo, check=True, capture_output=True)
    subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@example.invalid", "commit", "-m", "settings"],
                   cwd=repo, check=True, capture_output=True)
    artifacts = tmp_path / "artifacts"
    heartbeats = []

    async def fake_query(*, prompt, options):
        assert options.cwd == repo
        assert options.tools == CLAUDE_TOOLS
        assert options.allowed_tools == CLAUDE_TOOLS
        assert options.disallowed_tools == CLAUDE_DISALLOWED_TOOLS
        assert options.permission_mode == "bypassPermissions"
        assert options.user is None
        assert options.setting_sources == ["project"]
        assert options.env["ANTHROPIC_API_KEY"] == "test-key"
        assert "ANTHROPIC_AUTH_TOKEN" not in options.env
        assert "ANTHROPIC_BASE_URL" not in options.env
        assert "更新 README" in prompt
        assert '"interface_notes": "新增登录参数。"' in prompt
        assert "保留现有入口" in (repo / "CLAUDE.md").read_text()
        assert not (repo / ".claude").exists()
        (repo / "README.md").write_text("Asterism\n")
        (repo / "src").mkdir()
        (repo / "src" / "app.py").write_text("print('v5')\n")
        yield AssistantMessage(
            content=[
                ToolUseBlock(id="tool-1", name="Edit", input={"file_path": "README.md"}),
                ToolUseBlock(id="tool-2", name="Write", input={"file_path": "src/app.py"}),
                TextBlock(text="已完成两处修改"),
            ],
            model="claude-test",
        )
        yield ResultMessage(
            subtype="success",
            duration_ms=10,
            duration_api_ms=8,
            is_error=False,
            num_turns=2,
            session_id="session-1",
            result="修改完成",
            usage={"input_tokens": 120, "output_tokens": 30},
        )

    result = asyncio.run(provider(artifacts, fake_query, event=heartbeats.append).run(execution_request(repo)))

    assert "README.md" in result.diff_patch
    assert "src/app.py" in result.diff_patch
    assert "CLAUDE.md" not in result.diff_patch
    assert not (repo / "CLAUDE.md").exists()
    assert (repo / ".claude" / "settings.json").exists()
    assert result.execution_provider == "claude_sdk"
    assert result.turns == 2
    assert result.token_usage == {"input_tokens": 120, "output_tokens": 30}
    subprocess.run(["git", "apply", "--check"], cwd=repo, input=result.diff_patch, text=True,
                   check=True, capture_output=True)
    assert (repo / "README.md").read_text() == "asterism\n"
    assert not (repo / "src").exists()
    assert len(heartbeats) == 2
    records = [json.loads(line) for line in (artifacts / "wi-1" / "agent-transcript.jsonl").read_text().splitlines()]
    assert records[0] == {"type": "tool_use", "tool": "Edit", "target": "README.md"}
    assert records[-1]["tokenUsage"]["output_tokens"] == 30


def test_claude_sdk_root_runs_cli_as_nobody(tmp_path, monkeypatch):
    repo = tmp_path / "repo"
    init_repo(repo)
    chown_calls = []
    monkeypatch.setattr(os, "geteuid", lambda: 0)
    monkeypatch.setattr(os, "chown", lambda path, uid, gid, **kwargs: chown_calls.append((path, uid, gid, kwargs)))

    async def fake_query(*, options, **kwargs):
        runtime = tmp_path / ".claude-runtime"
        assert options.user == 65534
        assert options.env["HOME"] == str(runtime / "home")
        assert options.env["XDG_CONFIG_HOME"] == str(runtime / "config")
        assert options.env["XDG_CACHE_HOME"] == str(runtime / "cache")
        assert options.env["XDG_DATA_HOME"] == str(runtime / "data")
        assert options.env["XDG_STATE_HOME"] == str(runtime / "state")
        assert stat.S_IMODE(repo.stat().st_mode) & stat.S_IRWXU == stat.S_IRWXU
        assert stat.S_IMODE(runtime.stat().st_mode) & stat.S_IRWXU == stat.S_IRWXU
        assert stat.S_IMODE((runtime / "settings.json").stat().st_mode) & stat.S_IRUSR
        assert stat.S_IMODE((runtime / "settings.json").stat().st_mode) & stat.S_IWUSR
        yield ResultMessage(
            subtype="success",
            duration_ms=1,
            duration_api_ms=1,
            is_error=False,
            num_turns=1,
            session_id="session-root",
            result="无需修改",
        )

    result = asyncio.run(provider(tmp_path / "artifacts", fake_query).run(execution_request(repo)))

    assert result.diff_patch == ""
    assert (tmp_path, 65534, 65534, {"follow_symlinks": False}) in chown_calls
    assert (repo, 65534, 65534, {"follow_symlinks": False}) in chown_calls
    assert (tmp_path / ".claude-runtime", 65534, 65534, {"follow_symlinks": False}) in chown_calls
    assert (tmp_path, 0, 0, {"follow_symlinks": False}) in chown_calls
    assert (repo, 0, 0, {"follow_symlinks": False}) in chown_calls


def test_claude_sdk_uses_auth_token_for_custom_base_url(tmp_path):
    repo = tmp_path / "repo"
    init_repo(repo)

    async def fake_query(*, options, **kwargs):
        assert "ANTHROPIC_API_KEY" not in options.env
        assert options.env["ANTHROPIC_AUTH_TOKEN"] == "deepseek-key"
        assert options.env["ANTHROPIC_BASE_URL"] == "https://api.deepseek.com/anthropic"
        assert options.env["ANTHROPIC_MODEL"] == "deepseek-v4-pro[1m]"
        assert options.env["ANTHROPIC_DEFAULT_OPUS_MODEL"] == "deepseek-v4-pro[1m]"
        assert options.env["ANTHROPIC_DEFAULT_SONNET_MODEL"] == "deepseek-v4-pro[1m]"
        assert options.env["ANTHROPIC_DEFAULT_HAIKU_MODEL"] == "deepseek-v4-pro[1m]"
        assert options.env["CLAUDE_CODE_SUBAGENT_MODEL"] == "deepseek-v4-pro[1m]"
        assert options.env["CLAUDE_CODE_EFFORT_LEVEL"] == "max"
        yield ResultMessage(
            subtype="success",
            duration_ms=1,
            duration_api_ms=1,
            is_error=False,
            num_turns=1,
            session_id="session-1",
            result="无需修改",
        )

    result = asyncio.run(provider(
        tmp_path / "artifacts", fake_query, key="deepseek-key",
        base_url="https://api.deepseek.com/anthropic", model="deepseek-v4-pro[1m]", effort="max",
    ).run(execution_request(repo)))

    assert result.diff_patch == ""


def test_claude_sdk_returns_empty_diff(tmp_path):
    repo = tmp_path / "repo"
    init_repo(repo)

    async def fake_query(**kwargs):
        yield ResultMessage(
            subtype="success",
            duration_ms=1,
            duration_api_ms=1,
            is_error=False,
            num_turns=1,
            session_id="session-1",
            result="无需修改",
        )

    result = asyncio.run(provider(tmp_path / "artifacts", fake_query).run(execution_request(repo)))

    assert result.diff_patch == ""


def test_claude_sdk_forbidden_diff_is_blocked_by_existing_gate(tmp_path):
    repo = tmp_path / "repo"
    init_repo(repo)

    async def fake_query(**kwargs):
        (repo / "secrets").mkdir()
        (repo / "secrets" / "token.txt").write_text("not-a-real-secret\n")
        yield ResultMessage(
            subtype="success",
            duration_ms=1,
            duration_api_ms=1,
            is_error=False,
            num_turns=1,
            session_id="session-1",
            result="完成",
        )

    result = asyncio.run(provider(tmp_path / "artifacts", fake_query).run(execution_request(repo)))
    gate = asyncio.run(apply_patch_to_repo({
        "repo_path": str(repo),
        "diff_patch": result.diff_patch,
        "allowed_paths": ["README.md", "src", "secrets"],
        "forbidden_paths": ["secrets"],
    }))

    assert gate["blocked"] is True
    assert gate["reason"] == "forbidden path: secrets/token.txt"


def test_claude_sdk_keeps_separate_transcript_for_each_role(tmp_path):
    repo = tmp_path / "repo"
    init_repo(repo)

    async def fake_query(**kwargs):
        yield ResultMessage(subtype="success", duration_ms=1, duration_api_ms=1, is_error=False,
                            num_turns=1, session_id="session", result="完成")

    artifacts = tmp_path / "artifacts"
    sdk = provider(artifacts, fake_query)
    asyncio.run(sdk.run(execution_request(repo).model_copy(update={"role_id": "frontend", "assignment_index": 0})))
    asyncio.run(sdk.run(execution_request(repo).model_copy(update={"role_id": "backend", "assignment_index": 1})))

    files = sorted(path.name for path in (artifacts / "wi-1").glob("agent-transcript-*.jsonl"))
    assert files == ["agent-transcript-0-frontend-claude_sdk.jsonl", "agent-transcript-1-backend-claude_sdk.jsonl"]
