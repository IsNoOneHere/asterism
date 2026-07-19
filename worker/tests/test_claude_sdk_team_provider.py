import asyncio
import os
import subprocess

import pytest
from claude_agent_sdk import (
    AssistantMessage,
    ResultMessage,
    TaskStartedMessage,
    TaskUpdatedMessage,
    TextBlock,
    ToolPermissionContext,
    ToolResultBlock,
    UserMessage,
)

from asterism_worker.agent_config import AgentConstraints, EngineConfig, ModelProfile
from asterism_worker.contracts import CodingAttemptRequest
from asterism_worker.providers.claude_sdk_team import (
    SUBAGENT_TOOLS,
    SUPERVISOR_TOOLS,
    TEAM_TOOLS,
    ClaudeSdkTeamProvider,
)
from asterism_worker.repo_source import TeamWorkspace


def init_repo(path, content: str) -> None:
    (path / "src").mkdir(parents=True)
    (path / "src" / "app.txt").write_text(content)
    subprocess.run(["git", "init"], cwd=path, check=True, capture_output=True)
    subprocess.run(["git", "add", "."], cwd=path, check=True, capture_output=True)
    subprocess.run(
        ["git", "-c", "user.name=t", "-c", "user.email=t@example.invalid", "commit", "-m", "init"],
        cwd=path,
        check=True,
        capture_output=True,
    )


def team_request(root) -> tuple[CodingAttemptRequest, TeamWorkspace]:
    backend = root / "backend"
    frontend = root / "frontend"
    init_repo(backend, "old backend\n")
    init_repo(frontend, "old frontend\n")
    request = CodingAttemptRequest.model_validate({
        "case_id": "case-1",
        "work_item_id": "wi-1",
        "system_id": "system-1",
        "goal": "同时更新前后端",
        "acceptance_criteria": ["接口和页面一致"],
        "feedback": "不要修改构建产物",
        "context_manifest_id": "manifest-1",
        "memories": [{"content": "部门字段来自 deptName"}],
        "repos": [
            {"repo_id": "backend", "name": "server", "kind": "backend", "allowed_paths": ["src"]},
            {"repo_id": "frontend", "name": "web", "kind": "frontend", "allowed_paths": ["src"]},
        ],
        "agent_config_snapshot": {
            "model_profiles": [
                {"id": "deepseek-worker", "provider": "anthropic", "base_url": "https://api.example/anthropic", "model": "deepseek"},
            ],
            "agents": [
                {"name": "developer", "kind": "builtin", "engine": "claude_sdk", "model_profile_ref": "deepseek-worker"},
                {"name": "backend-dev", "kind": "custom", "engine": "claude_sdk", "model_profile_ref": "deepseek-worker", "path_scope": ["src"]},
                {"name": "frontend-dev", "kind": "custom", "engine": "claude_sdk", "model_profile_ref": "deepseek-worker", "path_scope": ["src"]},
            ],
        },
        "previous_candidate": [{
            "repo": "frontend",
            "diff_patch": "diff --git a/src/app.txt b/src/app.txt\n",
            "changed_paths": ["src/app.txt"],
            "summary": "上一版",
        }],
    })
    return request, TeamWorkspace(root=root, repos={"backend": backend, "frontend": frontend})


def test_team_provider_uses_native_subagents_and_enforces_repo_write_policy(tmp_path, monkeypatch):
    monkeypatch.setattr(os, "geteuid", lambda: 501)
    team_root = tmp_path / "team"
    team_root.mkdir()
    request, workspace = team_request(team_root)

    async def fake_query(*, prompt, options):
        messages = [item async for item in prompt]
        text = messages[0]["message"]["content"]
        assert "同时更新前后端" in text
        assert "上一版候选" in text
        assert "已恢复到当前工作区" in text
        assert "人工反馈为准" in text
        assert options.tools == TEAM_TOOLS
        assert set(SUPERVISOR_TOOLS).issubset(options.tools)
        assert "Edit" in options.tools and "Write" in options.tools
        assert options.allowed_tools == []
        assert options.permission_mode == "default"
        assert options.max_buffer_size == 16 * 1024 * 1024
        assert options.can_use_tool is not None
        assert "CLAUDE_CODE_DISABLE_EXPLORE_PLAN_AGENTS" not in options.env
        assert "CLAUDE_CODE_DISABLE_BACKGROUND_TASKS" not in options.env
        assert set(options.agents) == {"repo-backend", "repo-frontend"}
        assert "repo-backend -> 仓库 backend，目录 backend" in text
        assert "backend/backend" not in text
        assert "定向 Glob/Grep/Read" in text
        assert options.agents["repo-backend"].tools == [item for item in SUBAGENT_TOOLS if item != "Bash"]
        assert options.agents["repo-backend"].model == "inherit"
        assert options.agents["repo-backend"].permissionMode == "default"

        start = options.hooks["SubagentStart"][0].hooks[0]
        stop = options.hooks["SubagentStop"][0].hooks[0]
        gate = options.hooks["PreToolUse"][0].hooks[0]
        foreground_agent = await gate({
            "tool_name": "Agent", "tool_input": {
                "subagent_type": "Explore", "description": "分析接口", "prompt": "只读检查接口",
                "run_in_background": True,
            },
        }, None, None)
        assert foreground_agent["hookSpecificOutput"]["permissionDecision"] == "allow"
        assert "updatedInput" not in foreground_agent["hookSpecificOutput"]
        duplicate_agent = await gate({
            "tool_name": "Agent", "tool_input": {
                "subagent_type": "Explore", "description": "分析接口", "prompt": "只读检查接口",
                "run_in_background": False,
            },
        }, None, None)
        assert duplicate_agent["hookSpecificOutput"]["permissionDecision"] == "deny"
        await start({"agent_id": "agent-explore", "agent_type": "Explore"}, None, None)
        await start({"agent_id": "agent-back", "agent_type": "repo-backend"}, None, None)
        await start({"agent_id": "agent-front", "agent_type": "repo-frontend"}, None, None)

        callback_read = await options.can_use_tool(
            "Read", {"file_path": "backend/src/app.txt"},
            ToolPermissionContext(agent_id="agent-explore"),
        )
        callback_write = await options.can_use_tool(
            "Write", {"file_path": "backend/src/explore.txt"},
            ToolPermissionContext(agent_id="agent-explore"),
        )
        assert callback_read.behavior == "allow"
        assert callback_write.behavior == "deny"

        supervisor_write = await gate({
            "tool_name": "Write", "tool_input": {"file_path": "backend/src/direct.txt"},
        }, None, None)
        explore_read = await gate({
            "agent_id": "agent-explore", "agent_type": "Explore", "tool_name": "Read",
            "tool_input": {"file_path": "backend/src/app.txt"},
        }, None, None)
        explore_write = await gate({
            "agent_id": "agent-explore", "agent_type": "Explore", "tool_name": "Write",
            "tool_input": {"file_path": "backend/src/explore.txt"},
        }, None, None)
        nested_agent = await gate({
            "agent_id": "agent-back", "agent_type": "repo-backend", "tool_name": "Agent",
            "tool_input": {"subagent_type": "Explore", "prompt": "继续分析"},
        }, None, None)
        cross_repo = await gate({
            "agent_id": "agent-back", "agent_type": "repo-backend", "tool_name": "Write",
            "tool_input": {"file_path": "frontend/src/wrong.txt"},
        }, None, None)
        own_repo = await gate({
            "agent_id": "agent-back", "agent_type": "repo-backend", "tool_name": "Edit",
            "tool_input": {"file_path": "backend/src/app.txt"},
        }, None, None)
        assert supervisor_write["hookSpecificOutput"]["permissionDecision"] == "deny"
        assert explore_read["hookSpecificOutput"]["permissionDecision"] == "allow"
        assert explore_write["hookSpecificOutput"]["permissionDecision"] == "deny"
        assert nested_agent["hookSpecificOutput"]["permissionDecision"] == "deny"
        assert cross_repo["hookSpecificOutput"]["permissionDecision"] == "deny"
        assert own_repo["hookSpecificOutput"]["permissionDecision"] == "allow"

        (workspace.repos["backend"] / "src" / "app.txt").write_text("new backend\n")
        (workspace.repos["frontend"] / "src" / "app.txt").write_text("new frontend\n")
        await stop({"agent_id": "agent-explore", "agent_type": "Explore"}, None, None)
        await stop({"agent_id": "agent-back", "agent_type": "repo-backend"}, None, None)
        await stop({"agent_id": "agent-front", "agent_type": "repo-frontend"}, None, None)
        yield UserMessage(content=[ToolResultBlock(
            tool_use_id="edit-1", content="示例工具错误", is_error=True,
        )])
        yield AssistantMessage(content=[TextBlock(text="前后端已完成")], model="deepseek")
        yield ResultMessage(
            subtype="success", duration_ms=10, duration_api_ms=8, is_error=False, num_turns=4,
            session_id="session-team", result="实现完成", usage={"input_tokens": 100, "output_tokens": 20},
        )

    provider = ClaudeSdkTeamProvider(
        ModelProfile(provider="anthropic", base_url="https://api.example/anthropic", model="deepseek", api_key="key"),
        EngineConfig(name="claude_sdk", max_turns=20),
        str(tmp_path / "artifacts"),
        AgentConstraints(role_id="developer", prompt="先核对真实语义"),
        {"query": fake_query},
    )
    result = asyncio.run(provider.run(request, workspace))

    assert result.session_id == "session-team"
    assert result.token_usage == {"input_tokens": 100, "output_tokens": 20}
    assert {item.repo for item in result.repo_changes if item.diff_patch} == {"backend", "frontend"}
    assert {item.agent_type for item in result.subagent_runs} == {"Explore", "repo-backend", "repo-frontend"}
    assert (workspace.repos["backend"] / "src" / "app.txt").read_text() == "old backend\n"
    assert (workspace.repos["frontend"] / "src" / "app.txt").read_text() == "old frontend\n"
    transcript = (tmp_path / "artifacts" / "wi-1" / "coding-attempt-case-1.jsonl").read_text()
    assert '"type": "tool_result"' in transcript
    assert "示例工具错误" in transcript


def test_team_provider_allows_unchanged_repository(tmp_path, monkeypatch):
    monkeypatch.setattr(os, "geteuid", lambda: 501)
    team_root = tmp_path / "team"
    team_root.mkdir()
    request, workspace = team_request(team_root)

    async def fake_query(**kwargs):
        (workspace.repos["frontend"] / "src" / "app.txt").write_text("new frontend\n")
        yield ResultMessage(
            subtype="success", duration_ms=1, duration_api_ms=1, is_error=False, num_turns=1,
            session_id="session-one", result="只需前端修改",
        )

    provider = ClaudeSdkTeamProvider(
        ModelProfile(provider="anthropic", base_url="https://api.example/anthropic", model="deepseek", api_key="key"),
        EngineConfig(name="claude_sdk"),
        str(tmp_path / "artifacts"),
        AgentConstraints(role_id="developer"),
        {"query": fake_query},
    )
    result = asyncio.run(provider.run(request, workspace))

    changes = {item.repo: item.diff_patch for item in result.repo_changes}
    assert changes["backend"] == ""
    assert "src/app.txt" in changes["frontend"]


def test_team_provider_restores_worker_owner_before_collecting_diff(tmp_path, monkeypatch):
    team_root = tmp_path / "team"
    team_root.mkdir()
    request, workspace = team_request(team_root)
    owner_changes: list[tuple[object, int, int]] = []

    async def fake_query(**kwargs):
        yield ResultMessage(
            subtype="success", duration_ms=1, duration_api_ms=1, is_error=False, num_turns=1,
            session_id="session-owner", result="执行完成",
        )

    provider = ClaudeSdkTeamProvider(
        ModelProfile(provider="anthropic", base_url="https://api.example/anthropic", model="deepseek", api_key="key"),
        EngineConfig(name="claude_sdk"),
        str(tmp_path / "artifacts"),
        AgentConstraints(role_id="developer"),
        {"query": fake_query},
    )
    monkeypatch.setattr(os, "geteuid", lambda: 0)
    monkeypatch.setattr(os, "getegid", lambda: 0)
    monkeypatch.setattr(provider, "_prepare_sdk_user", lambda *_args: 65534)
    monkeypatch.setattr(
        provider,
        "_set_tree_owner",
        lambda root, uid, gid, **_kwargs: owner_changes.append((root, uid, gid)),
    )
    collect_changes = provider._repo_changes

    def assert_owner_then_collect(current_workspace, summary):
        assert owner_changes[-2:] == [(workspace.root, 0, 0), (workspace.root.parent / ".team-claude-runtime", 0, 0)]
        return collect_changes(current_workspace, summary)

    monkeypatch.setattr(provider, "_repo_changes", assert_owner_then_collect)
    result = asyncio.run(provider.run(request, workspace))

    assert result.session_id == "session-owner"


def test_team_provider_only_allows_configured_validation_command(tmp_path, monkeypatch):
    monkeypatch.setattr(os, "geteuid", lambda: 501)
    team_root = tmp_path / "team"
    team_root.mkdir()
    request, workspace = team_request(team_root)
    request.repos[0].test_commands = ["pytest -q"]
    provider = ClaudeSdkTeamProvider(
        ModelProfile(provider="anthropic", model="claude", api_key="key"),
        EngineConfig(name="claude_sdk"),
        str(tmp_path / "artifacts"),
        AgentConstraints(role_id="developer"),
    )
    policy = provider._agent_specs(request, workspace)[0].policy

    assert provider._authorize(
        policy, "Bash", {"command": "cd backend && pytest -q"}, workspace.root,
    ) == (True, "")
    allowed, reason = provider._authorize(
        policy, "Bash", {"command": "curl https://example.com"}, workspace.root,
    )
    assert allowed is False
    assert "验证命令" in reason


def test_team_provider_waits_for_background_task_terminal_event(tmp_path, monkeypatch):
    monkeypatch.setattr(os, "geteuid", lambda: 501)
    team_root = tmp_path / "team"
    team_root.mkdir()
    request, workspace = team_request(team_root)

    async def completed_query(**kwargs):
        yield TaskStartedMessage(
            subtype="task_started", data={}, task_id="task-1", description="并行检查",
            uuid="uuid-1", session_id="session-task",
        )
        yield TaskUpdatedMessage(
            subtype="task_updated", data={}, task_id="task-1", patch={"status": "completed"},
            status="completed", session_id="session-task", uuid="uuid-2",
        )
        yield ResultMessage(
            subtype="success", duration_ms=1, duration_api_ms=1, is_error=False, num_turns=1,
            session_id="session-task", result="执行完成",
        )

    provider = ClaudeSdkTeamProvider(
        ModelProfile(provider="anthropic", model="claude", api_key="key"),
        EngineConfig(name="claude_sdk"),
        str(tmp_path / "artifacts"),
        AgentConstraints(role_id="developer"),
        {"query": completed_query},
    )
    assert asyncio.run(provider.run(request, workspace)).session_id == "session-task"

    async def unfinished_query(**kwargs):
        yield TaskStartedMessage(
            subtype="task_started", data={}, task_id="task-2", description="未完成检查",
            uuid="uuid-3", session_id="session-task",
        )
        yield ResultMessage(
            subtype="success", duration_ms=1, duration_api_ms=1, is_error=False, num_turns=1,
            session_id="session-task", result="提前结束",
        )

    provider.query = unfinished_query
    with pytest.raises(RuntimeError, match="后台任务尚未完成"):
        asyncio.run(provider.run(request, workspace))


def test_team_provider_closes_builtin_agent_from_task_terminal_event(tmp_path, monkeypatch):
    monkeypatch.setattr(os, "geteuid", lambda: 501)
    team_root = tmp_path / "team"
    team_root.mkdir()
    request, workspace = team_request(team_root)

    async def builtin_query(*, options, **kwargs):
        start = options.hooks["SubagentStart"][0].hooks[0]
        await start({"agent_id": "task-explore", "agent_type": "Explore"}, None, None)
        yield TaskStartedMessage(
            subtype="task_started", data={}, task_id="task-explore", description="只读探索",
            uuid="uuid-explore", session_id="session-explore",
        )
        yield TaskUpdatedMessage(
            subtype="task_updated", data={}, task_id="task-explore", patch={"status": "completed"},
            status="completed", session_id="session-explore", uuid="uuid-explore-done",
        )
        yield ResultMessage(
            subtype="success", duration_ms=1, duration_api_ms=1, is_error=False, num_turns=1,
            session_id="session-explore", result="探索完成",
        )

    provider = ClaudeSdkTeamProvider(
        ModelProfile(provider="anthropic", model="claude", api_key="key"),
        EngineConfig(name="claude_sdk"),
        str(tmp_path / "artifacts"),
        AgentConstraints(role_id="developer"),
        {"query": builtin_query},
    )
    result = asyncio.run(provider.run(request, workspace))

    assert [(item.agent_type, item.status) for item in result.subagent_runs] == [
        ("Explore", "completed"),
    ]
