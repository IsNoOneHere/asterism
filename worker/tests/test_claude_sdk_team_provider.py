import asyncio
import inspect
import json
import os
import subprocess

import pytest
from claude_agent_sdk import (
    AssistantMessage,
    ResultMessage,
    TaskStartedMessage,
    TaskUpdatedMessage,
    TextBlock,
    ToolResultBlock,
    UserMessage,
)

from asterism_worker.agent_config import AgentConstraints, EngineConfig, ModelProfile
from asterism_worker.contracts import (
    CodingAttemptRequest,
    CodingPlanDraft,
    CodingPlanRequest,
)
from asterism_worker.providers.claude_sdk_planning import (
    PLANNING_TOOL_MATCHER,
    ClaudeSdkPlanningMixin,
    PlanningResultError,
)
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
        "requirement_manifest_id": "manifest-1",
        "requirement_context": [{"refId": "MEM:mem-1", "title": "字段约束", "content": "部门字段来自 deptName"}],
        "execution_context": [{"refId": "MEM:mem-2", "title": "实现惯例", "content": "保持接口兼容"}],
        "repos": [
            {"repo_id": "backend", "name": "server", "kind": "backend", "allowed_paths": ["src"]},
            {"repo_id": "frontend", "name": "web", "kind": "frontend", "allowed_paths": ["src"]},
        ],
        "agent_config_snapshot": {
            "model_profiles": [
                {"id": "deepseek-worker", "provider": "anthropic", "base_url": "https://api.example/anthropic", "model": "deepseek"},
            ],
            "agents": [
                {"name": "developer", "kind": "builtin", "engine": "claude_sdk_team", "model_profile_ref": "deepseek-worker"},
            ],
        },
        "previous_candidate": [{
            "repo": "frontend",
            "diff_patch": "diff --git a/src/app.txt b/src/app.txt\n",
            "changed_paths": ["src/app.txt"],
            "summary": "上一版",
        }],
        "revision_context": {
            "revision": 2,
            "revision_mode": "incremental",
            "feedback": "不要修改构建产物",
            "previous_diff_summary": [{"repo": "frontend", "changedPaths": ["src/app.txt"]}],
        },
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
        assert "修订上下文" in text
        assert "只修订人工意见涉及的部分，不推翻已通过的改动" in text
        assert '"revision": 2' in text
        assert "diff --git" not in text
        assert '"changed_paths": ["src/app.txt"]' in text
        assert options.tools == TEAM_TOOLS
        assert set(SUPERVISOR_TOOLS).issubset(options.tools)
        assert "Edit" in options.tools and "Write" in options.tools
        assert "Bash" not in options.tools
        assert "Bash" in options.disallowed_tools
        assert options.allowed_tools == TEAM_TOOLS
        assert options.permission_mode == "dontAsk"
        assert options.max_buffer_size == 16 * 1024 * 1024
        assert options.output_format is None
        assert options.can_use_tool is None
        assert "CLAUDE_CODE_DISABLE_EXPLORE_PLAN_AGENTS" not in options.env
        assert "CLAUDE_CODE_DISABLE_BACKGROUND_TASKS" not in options.env
        assert set(options.agents) == {"repo-backend", "repo-frontend"}
        assert "repo-backend -> 仓库 backend，目录 backend" in text
        assert "backend/backend" not in text
        assert "定向 Glob/Grep/Read" in text
        assert options.agents["repo-backend"].tools == SUBAGENT_TOOLS
        assert options.agents["repo-backend"].model == "inherit"
        assert options.agents["repo-backend"].permissionMode == "dontAsk"

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
        repeated_agent = await gate({
            "tool_name": "Agent", "tool_input": {
                "subagent_type": "Explore", "description": "分析接口", "prompt": "只读检查接口",
                "run_in_background": False,
            },
        }, None, None)
        assert repeated_agent["hookSpecificOutput"]["permissionDecision"] == "allow"
        await start({"agent_id": "agent-explore", "agent_type": "Explore"}, None, None)
        await start({"agent_id": "agent-back", "agent_type": "repo-backend"}, None, None)
        await start({"agent_id": "agent-front", "agent_type": "repo-frontend"}, None, None)

        supervisor_write = await gate({
            "tool_name": "Write", "tool_input": {"file_path": "backend/src/direct.txt"},
        }, None, None)
        supervisor_outside_allowed = await gate({
            "tool_name": "Write", "tool_input": {"file_path": "backend/README.md"},
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
        assert supervisor_write["hookSpecificOutput"]["permissionDecision"] == "allow"
        assert supervisor_outside_allowed["hookSpecificOutput"]["permissionDecision"] == "deny"
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
        EngineConfig(name="claude_sdk_team", max_turns=20),
        str(tmp_path / "artifacts"),
        AgentConstraints(role_id="developer", prompt="先核对真实语义"),
        {"query": fake_query},
    )
    result = asyncio.run(provider.run(request, workspace))

    assert result.session_id == "session-team"
    assert result.outcome.status == "completed"
    assert result.outcome.changed_paths == ["src/app.txt"]
    assert result.token_usage == {"input_tokens": 100, "output_tokens": 20}
    assert {item.repo for item in result.repo_changes if item.diff_patch} == {"backend", "frontend"}
    assert {item.agent_type for item in result.subagent_runs} == {"Explore", "repo-backend", "repo-frontend"}
    assert {item.status for item in result.subagent_runs} == {"stopped"}
    assert (workspace.repos["backend"] / "src" / "app.txt").read_text() == "old backend\n"
    assert (workspace.repos["frontend"] / "src" / "app.txt").read_text() == "old frontend\n"
    transcript = (tmp_path / "artifacts" / "wi-1" / "coding-attempt-case-1.jsonl").read_text()
    assert '"type": "tool_result"' in transcript
    assert "示例工具错误" in transcript


def test_team_provider_plans_read_only_and_can_resume_the_same_session(tmp_path, monkeypatch):
    monkeypatch.setattr(os, "geteuid", lambda: 501)
    team_root = tmp_path / "team"
    team_root.mkdir()
    coding_request, workspace = team_request(team_root)
    workspace = TeamWorkspace(root=workspace.root, repos=workspace.repos, persistent=True)
    request = CodingPlanRequest.model_validate({
        "case_id": coding_request.case_id,
        "work_item_id": coding_request.work_item_id,
        "system_id": coding_request.system_id,
        "repos": [repo.model_dump() for repo in coding_request.repos],
        "goal": coding_request.goal,
        "acceptance_criteria": coding_request.acceptance_criteria,
        "requirement_context": coding_request.requirement_context,
        "execution_context": coding_request.execution_context,
        "requirement_manifest_id": coding_request.requirement_manifest_id,
        "plan_revision": 2,
        "feedback": "只调整前端提示",
        "resume_session_id": "11111111-1111-4111-8111-111111111111",
    })

    async def fake_query(*, prompt, options):
        messages = [item async for item in prompt]
        assert "只调整前端提示" in messages[0]["message"]["content"]
        assert "计划负责确定方向与边界，不代替后续代码开发" in messages[0]["message"]["content"]
        assert "不要输出完整类、完整函数、可直接复制的实现源码或长代码块" in messages[0]["message"]["content"]
        assert "Requirement manifest: manifest-1" in (workspace.root / "CLAUDE.md").read_text()
        assert "已冻结需求依据" in (workspace.root / "CLAUDE.md").read_text()
        assert "执行阶段补充经验" in (workspace.root / "CLAUDE.md").read_text()
        assert options.tools == ["Read", "Glob", "Grep"]
        assert "Agent" in options.disallowed_tools
        assert "Edit" in options.disallowed_tools
        assert options.resume == request.resume_session_id
        # 本地单 Worker 直接恢复 Artifact volume 中的原生 Claude runtime。
        assert options.session_store is None
        assert options.session_store_flush == "eager"
        assert options.enable_file_checkpointing is False
        assert options.output_format is None
        planning_hook = options.hooks["PreToolUse"][0]
        # 仅读取工具进入路径门禁，SDK 终态控制消息不进入仓库 Hook。
        assert planning_hook.matcher == PLANNING_TOOL_MATCHER == "Read|Glob|Grep"
        gate = planning_hook.hooks[0]
        first = await gate({
            "tool_name": "Read", "tool_input": {"file_path": "frontend/src/app.txt"},
        }, None, None)
        duplicate = await gate({
            "tool_name": "Read", "tool_input": {"file_path": "frontend/src/app.txt"},
        }, None, None)
        assert first["hookSpecificOutput"]["permissionDecision"] == "allow"
        assert duplicate["hookSpecificOutput"]["permissionDecision"] == "deny"
        yield ResultMessage(
            subtype="success", duration_ms=1, duration_api_ms=1, is_error=False, num_turns=2,
            session_id="22222222-2222-4222-8222-222222222222",
            result="# 计划\n\n- 调整前端错误提示位置\n- 保持接口不变",
            usage={"input_tokens": 10, "output_tokens": 5},
        )

    provider = ClaudeSdkTeamProvider(
        ModelProfile(provider="anthropic", model="deepseek", api_key="key"),
        EngineConfig(name="claude_sdk_team"),
        str(tmp_path / "artifacts"),
        AgentConstraints(role_id="developer"),
        {"query": fake_query},
    )
    runtime_dir = provider._runtime_dir(workspace)
    local_project = runtime_dir / "projects" / "local-project"
    local_project.mkdir(parents=True)
    (local_project / f"{request.resume_session_id}.jsonl").write_text("{}\n", encoding="utf-8")
    plan = asyncio.run(provider.plan(request, workspace))

    assert plan.revision == 2
    assert plan.session_id == "22222222-2222-4222-8222-222222222222"
    assert plan.plan_markdown == "# 计划\n\n- 调整前端错误提示位置\n- 保持接口不变"
    assert set(plan.base_revisions) == {"backend", "frontend"}
    transcript = tmp_path / "artifacts" / "wi-1" / "coding-plan-case-1.jsonl"
    records = [json.loads(line) for line in transcript.read_text().splitlines()]
    metadata = next(item for item in records if item["type"] == "sdk_result_metadata")
    assert metadata == {
        "type": "sdk_result_metadata",
        "subtype": "success",
        "is_error": False,
        "stop_reason": "",
        "permission_denial_count": 0,
        "deferred_tool_use": False,
        "error_count": 0,
        "session_id": "22222222-2222-4222-8222-222222222222",
        "result_length": len(plan.plan_markdown),
    }
    assert records[-1]["usage"] == {"input_tokens": 10, "output_tokens": 5}


def test_planning_rejects_empty_result_text(tmp_path, monkeypatch):
    monkeypatch.setattr(os, "geteuid", lambda: 501)
    team_root = tmp_path / "team"
    team_root.mkdir()
    coding_request, workspace = team_request(team_root)
    request = CodingPlanRequest.model_validate({
        **coding_request.model_dump(),
        "plan_revision": 1,
    })

    async def fake_query(**_kwargs):
        yield ResultMessage(
            subtype="success", duration_ms=1, duration_api_ms=1, is_error=False, num_turns=1,
            session_id="session-empty", result="",
        )

    provider = ClaudeSdkTeamProvider(
        ModelProfile(provider="anthropic", model="claude", api_key="key"),
        EngineConfig(name="claude_sdk_team"),
        str(tmp_path / "artifacts"),
        AgentConstraints(role_id="developer"),
        {"query": fake_query},
    )

    with pytest.raises(PlanningResultError, match="^planning_text_missing$"):
        asyncio.run(provider.plan(request, workspace))


def test_planning_surfaces_sdk_terminal_error(tmp_path, monkeypatch):
    monkeypatch.setattr(os, "geteuid", lambda: 501)
    team_root = tmp_path / "team"
    team_root.mkdir()
    coding_request, workspace = team_request(team_root)
    request = CodingPlanRequest.model_validate({
        **coding_request.model_dump(),
        "plan_revision": 1,
    })

    async def fake_query(**_kwargs):
        yield ResultMessage(
            subtype="error_max_turns",
            duration_ms=1,
            duration_api_ms=1,
            is_error=True,
            num_turns=1,
            session_id="session-invalid",
            result="规划回合达到最大轮次",
        )

    provider = ClaudeSdkTeamProvider(
        ModelProfile(provider="anthropic", model="claude", api_key="key"),
        EngineConfig(name="claude_sdk_team"),
        str(tmp_path / "artifacts"),
        AgentConstraints(role_id="developer"),
        {"query": fake_query},
    )

    with pytest.raises(RuntimeError, match="Claude SDK Planning failed: error_max_turns"):
        asyncio.run(provider.plan(request, workspace))

    transcript = tmp_path / "artifacts" / "wi-1" / "coding-plan-case-1.jsonl"
    records = [json.loads(line) for line in transcript.read_text().splitlines()]
    metadata = next(item for item in records if item["type"] == "sdk_result_metadata")
    assert metadata["subtype"] == "error_max_turns"
    assert metadata["is_error"] is True


def test_planning_source_has_no_model_structured_output_contract():
    source = inspect.getsource(ClaudeSdkPlanningMixin)

    assert "output_format" not in source
    assert "structured_output" not in source
    assert "CodingPlanProposal" not in source


def test_session_resolution_prefers_local_and_rebuilds_from_durable_context(tmp_path):
    provider = ClaudeSdkTeamProvider(
        ModelProfile(provider="anthropic", model="deepseek", api_key="key"),
        EngineConfig(name="claude_sdk_team"),
        str(tmp_path / "artifacts"),
        AgentConstraints(role_id="developer"),
    )
    runtime_dir = tmp_path / "runtime"
    runtime_dir.mkdir()
    transcript = tmp_path / "transcript.jsonl"
    stored_id = "11111111-1111-4111-8111-111111111111"
    local_id = "22222222-2222-4222-8222-222222222222"
    missing_id = "33333333-3333-4333-8333-333333333333"

    asyncio.run(provider.session_store.append(
        {"project_key": "old-worker-path", "session_id": stored_id},
        [{"type": "user", "uuid": "stored-1"}],
    ))
    local_project = runtime_dir / "projects" / "local-project"
    local_project.mkdir(parents=True)
    (local_project / f"{local_id}.jsonl").write_text("{}\n", encoding="utf-8")

    stored_resume, stored_store = provider._resolve_session(
        stored_id, runtime_dir, transcript, "coding",
    )
    local_resume, local_store = provider._resolve_session(
        local_id, runtime_dir, transcript, "coding",
    )
    rebuilt_resume, rebuilt_store = provider._resolve_session(
        missing_id, runtime_dir, transcript, "coding",
    )

    assert stored_resume is None
    assert stored_store is provider.session_store
    assert local_resume == local_id
    assert local_store is None
    assert rebuilt_resume is None
    assert rebuilt_store is provider.session_store
    records = transcript.read_text(encoding="utf-8")
    assert '"mode": "rebuilt_from_context"' in records
    assert '"mode": "local_runtime"' in records
    assert '"mode": "rebuilt"' in records


def test_planning_reserves_turns_for_plan_synthesis(tmp_path, monkeypatch):
    monkeypatch.setattr(os, "geteuid", lambda: 501)
    team_root = tmp_path / "team"
    team_root.mkdir()
    coding_request, workspace = team_request(team_root)
    request = CodingPlanRequest.model_validate({
        **coding_request.model_dump(),
        "plan_revision": 1,
    })

    async def fake_query(*, options, **_kwargs):
        for _ in range(4):
            yield AssistantMessage(content=[], model="deepseek")
        gate = options.hooks["PreToolUse"][0].hooks[0]
        decision = await gate({
            "tool_name": "Read", "tool_input": {"file_path": "backend/src/app.txt"},
        }, None, None)
        assert decision["hookSpecificOutput"]["permissionDecision"] == "deny"
        assert "立即提交可审批计划" in decision["hookSpecificOutput"]["permissionDecisionReason"]
        yield ResultMessage(
            subtype="success", duration_ms=1, duration_api_ms=1, is_error=False, num_turns=5,
            session_id="session-plan-budget", result="# 计划\n\n修复后端设置保存",
        )

    provider = ClaudeSdkTeamProvider(
        ModelProfile(provider="anthropic", model="deepseek", api_key="key"),
        EngineConfig(name="claude_sdk_team", max_turns=8),
        str(tmp_path / "artifacts"),
        AgentConstraints(role_id="developer"),
        {"query": fake_query},
    )

    plan = asyncio.run(provider.plan(request, workspace))

    assert plan.session_id == "session-plan-budget"


def test_git_trust_is_scoped_to_the_current_workspace(tmp_path, monkeypatch):
    provider = ClaudeSdkTeamProvider(
        ModelProfile(provider="anthropic", model="deepseek", api_key="key"),
        EngineConfig(name="claude_sdk_team"),
        str(tmp_path / "artifacts"),
        AgentConstraints(role_id="developer"),
    )
    repo = tmp_path / "repo"
    repo.mkdir()
    commands: list[list[str]] = []

    def fake_run(command, **_kwargs):
        commands.append(command)
        return subprocess.CompletedProcess(command, 0, "base-revision\n", "")

    monkeypatch.setattr(subprocess, "run", fake_run)

    result = provider._git(repo, "rev-parse", "HEAD")

    assert result.stdout.strip() == "base-revision"
    assert commands == [[
        "git", "-c", f"safe.directory={repo.resolve()}", "rev-parse", "HEAD",
    ]]


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
        EngineConfig(name="claude_sdk_team"),
        str(tmp_path / "artifacts"),
        AgentConstraints(role_id="developer"),
        {"query": fake_query},
    )
    result = asyncio.run(provider.run(request, workspace))

    changes = {item.repo: item.diff_patch for item in result.repo_changes}
    assert changes["backend"] == ""
    assert "src/app.txt" in changes["frontend"]


def test_planning_captures_git_baseline_before_switching_sdk_user(tmp_path, monkeypatch):
    team_root = tmp_path / "team"
    team_root.mkdir()
    coding_request, workspace = team_request(team_root)
    request = CodingPlanRequest.model_validate({
        **coding_request.model_dump(),
        "plan_revision": 1,
    })
    switched = False

    async def fake_query(**_kwargs):
        yield ResultMessage(
            subtype="success", duration_ms=1, duration_api_ms=1, is_error=False, num_turns=1,
            session_id="session-plan-owner", result="# 计划\n\n修复后端设置保存",
        )

    provider = ClaudeSdkTeamProvider(
        ModelProfile(provider="anthropic", model="deepseek", api_key="key"),
        EngineConfig(name="claude_sdk_team"),
        str(tmp_path / "artifacts"),
        AgentConstraints(role_id="developer"),
        {"query": fake_query},
    )
    read_git = provider._git

    def assert_git_before_switch(*args):
        assert not switched
        return read_git(*args)

    def switch_user(*_args):
        nonlocal switched
        switched = True
        return None

    monkeypatch.setattr(provider, "_git", assert_git_before_switch)
    monkeypatch.setattr(provider, "_prepare_sdk_user", switch_user)

    plan = asyncio.run(provider.plan(request, workspace))

    assert switched
    assert set(plan.base_revisions) == {"backend", "frontend"}


def test_team_provider_appends_attempt_boundaries_across_revisions(tmp_path, monkeypatch):
    monkeypatch.setattr(os, "geteuid", lambda: 501)
    team_root = tmp_path / "team"
    team_root.mkdir()
    request, workspace = team_request(team_root)

    async def fake_query(**kwargs):
        yield ResultMessage(
            subtype="success", duration_ms=1, duration_api_ms=1, is_error=False, num_turns=1,
            session_id="session-audit", result="执行完成",
        )

    provider = ClaudeSdkTeamProvider(
        ModelProfile(provider="anthropic", model="deepseek", api_key="key"),
        EngineConfig(name="claude_sdk_team"),
        str(tmp_path / "artifacts"),
        AgentConstraints(role_id="developer"),
        {"query": fake_query},
    )
    initial_request = request.model_copy(update={"revision_context": None})

    asyncio.run(provider.run(initial_request, workspace))
    asyncio.run(provider.run(request, workspace))

    transcript = tmp_path / "artifacts" / "wi-1" / "coding-attempt-case-1.jsonl"
    records = [json.loads(line) for line in transcript.read_text().splitlines()]
    starts = [record for record in records if record["type"] == "attempt_start"]
    assert [(record["revision"], record["revision_mode"]) for record in starts] == [
        (0, "initial"),
        (2, "incremental"),
    ]
    assert len([record for record in records if record["type"] == "result"]) == 2


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
        EngineConfig(name="claude_sdk_team"),
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


def test_team_provider_leaves_validation_commands_to_workflow(tmp_path, monkeypatch):
    monkeypatch.setattr(os, "geteuid", lambda: 501)
    team_root = tmp_path / "team"
    team_root.mkdir()
    request, workspace = team_request(team_root)
    request.repos[0].test_commands = ["pytest -q"]
    provider = ClaudeSdkTeamProvider(
        ModelProfile(provider="anthropic", model="claude", api_key="key"),
        EngineConfig(name="claude_sdk_team"),
        str(tmp_path / "artifacts"),
        AgentConstraints(role_id="developer"),
    )
    policy = provider._agent_specs(request, workspace)[0].policy

    allowed, reason = provider._authorize(
        policy, "Bash", {"command": "cd backend && pytest -q"}, workspace.root,
    )
    assert allowed is False
    assert "Coding Attempt" in reason


def test_team_provider_keeps_background_task_events_as_telemetry(tmp_path, monkeypatch):
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
        EngineConfig(name="claude_sdk_team"),
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
    result = asyncio.run(provider.run(request, workspace))
    assert result.outcome.status == "blocked"


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
        EngineConfig(name="claude_sdk_team"),
        str(tmp_path / "artifacts"),
        AgentConstraints(role_id="developer"),
        {"query": builtin_query},
    )
    result = asyncio.run(provider.run(request, workspace))

    assert [(item.agent_type, item.status) for item in result.subagent_runs] == [
        ("Explore", "completed"),
    ]


def test_permission_denial_blocks_candidate_even_when_diff_exists(tmp_path, monkeypatch):
    monkeypatch.setattr(os, "geteuid", lambda: 501)
    team_root = tmp_path / "team"
    team_root.mkdir()
    request, workspace = team_request(team_root)
    request.approved_plan = CodingPlanDraft(
        plan_markdown="# 计划\n\n实现后端",
    )

    async def denied_query(**_kwargs):
        (workspace.repos["backend"] / "src" / "app.txt").write_text("partial backend\n")
        yield ResultMessage(
            subtype="success", duration_ms=1, duration_api_ms=1, is_error=False, num_turns=1,
            session_id="session-denied", result="已完成",
            permission_denials=[{"tool_name": "Edit"}],
        )

    provider = ClaudeSdkTeamProvider(
        ModelProfile(provider="anthropic", model="claude", api_key="key"),
        EngineConfig(name="claude_sdk_team"),
        str(tmp_path / "artifacts"),
        AgentConstraints(role_id="developer"),
        {"query": denied_query},
    )

    result = asyncio.run(provider.run(request, workspace))

    assert result.outcome.status == "blocked"
    assert any("permission_denials" in blocker for blocker in result.outcome.blockers)
    assert result.repo_changes[0].diff_patch


def test_sdk_non_success_terminal_blocks_candidate_even_when_error_flag_is_false(tmp_path, monkeypatch):
    monkeypatch.setattr(os, "geteuid", lambda: 501)
    team_root = tmp_path / "team"
    team_root.mkdir()
    request, workspace = team_request(team_root)

    async def terminal_error_query(**_kwargs):
        (workspace.repos["backend"] / "src" / "app.txt").write_text("partial backend\n")
        yield ResultMessage(
            subtype="error_max_turns", duration_ms=1, duration_api_ms=1, is_error=False, num_turns=1,
            session_id="session-terminal-error", result="达到最大轮次",
        )

    provider = ClaudeSdkTeamProvider(
        ModelProfile(provider="anthropic", model="claude", api_key="key"),
        EngineConfig(name="claude_sdk_team"),
        str(tmp_path / "artifacts"),
        AgentConstraints(role_id="developer"),
        {"query": terminal_error_query},
    )

    result = asyncio.run(provider.run(request, workspace))

    # 系统以 SDK 终态而非模型文本判断完成，异常终态即使有 Diff 也必须保留为可恢复阻塞。
    assert result.outcome.status == "blocked"
    assert any("error_max_turns" in blocker for blocker in result.outcome.blockers)
    assert result.repo_changes[0].diff_patch


def test_approved_markdown_plan_does_not_require_model_task_outcomes(tmp_path, monkeypatch):
    monkeypatch.setattr(os, "geteuid", lambda: 501)
    team_root = tmp_path / "team"
    team_root.mkdir()
    request, workspace = team_request(team_root)
    request.approved_plan = CodingPlanDraft(
        plan_markdown="# 计划\n\n实现前后端",
    )

    async def partial_query(**_kwargs):
        (workspace.repos["frontend"] / "src" / "app.txt").write_text("partial frontend\n")
        yield ResultMessage(
            subtype="success", duration_ms=1, duration_api_ms=1, is_error=False, num_turns=1,
            session_id="session-partial", result="前端完成",
        )

    provider = ClaudeSdkTeamProvider(
        ModelProfile(provider="anthropic", model="claude", api_key="key"),
        EngineConfig(name="claude_sdk_team"),
        str(tmp_path / "artifacts"),
        AgentConstraints(role_id="developer"),
        {"query": partial_query},
    )

    result = asyncio.run(provider.run(request, workspace))

    assert result.outcome.status == "completed"
    assert result.outcome.blockers == []
    assert result.outcome.changed_paths == ["src/app.txt"]


def test_system_collects_every_real_diff_without_model_declarations(tmp_path, monkeypatch):
    monkeypatch.setattr(os, "geteuid", lambda: 501)
    team_root = tmp_path / "team"
    team_root.mkdir()
    request, workspace = team_request(team_root)
    request.approved_plan = CodingPlanDraft(
        plan_markdown="# 计划\n\n只实现后端",
    )

    async def query_with_stale_change(**_kwargs):
        (workspace.repos["backend"] / "src" / "app.txt").write_text("new backend\n")
        (workspace.repos["frontend"] / "src" / "app.txt").write_text("stale frontend\n")
        yield ResultMessage(
            subtype="success", duration_ms=1, duration_api_ms=1, is_error=False, num_turns=1,
            session_id="session-stale", result="后端完成",
        )

    provider = ClaudeSdkTeamProvider(
        ModelProfile(provider="anthropic", model="claude", api_key="key"),
        EngineConfig(name="claude_sdk_team"),
        str(tmp_path / "artifacts"),
        AgentConstraints(role_id="developer"),
        {"query": query_with_stale_change},
    )

    result = asyncio.run(provider.run(request, workspace))

    assert result.outcome.status == "completed"
    assert {change.repo for change in result.repo_changes if change.diff_patch} == {
        "backend", "frontend",
    }


def test_model_summary_cannot_invent_changed_paths(tmp_path, monkeypatch):
    monkeypatch.setattr(os, "geteuid", lambda: 501)
    team_root = tmp_path / "team"
    team_root.mkdir()
    request, workspace = team_request(team_root)
    request.approved_plan = CodingPlanDraft(
        plan_markdown="# 计划\n\n实现前后端",
    )

    async def query_with_invented_backend_path(**_kwargs):
        (workspace.repos["frontend"] / "src" / "app.txt").write_text("new frontend\n")
        yield ResultMessage(
            subtype="success", duration_ms=1, duration_api_ms=1, is_error=False, num_turns=1,
            session_id="session-invented", result="前后端均已修改，包括 backend/src/app.txt",
        )

    provider = ClaudeSdkTeamProvider(
        ModelProfile(provider="anthropic", model="claude", api_key="key"),
        EngineConfig(name="claude_sdk_team"),
        str(tmp_path / "artifacts"),
        AgentConstraints(role_id="developer"),
        {"query": query_with_invented_backend_path},
    )

    result = asyncio.run(provider.run(request, workspace))

    assert result.outcome.status == "completed"
    assert result.outcome.changed_paths == ["src/app.txt"]
    assert [change.repo for change in result.repo_changes if change.diff_patch] == ["frontend"]
