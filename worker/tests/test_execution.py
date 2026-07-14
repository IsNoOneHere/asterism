import asyncio
import subprocess
import sys
from pathlib import Path

import pytest

from asterism_worker.activities import execution as execution_activities
from asterism_worker.agent_config import AgentConstraints, EngineConfig, ModelProfile, ResolvedAgentConfig
from asterism_worker.activities.execution import (
    collect_file_context,
    git_apply_check,
    plan_execution,
    release_repo,
    run_execution,
    run_validation,
    summarize_repo,
    summarize_repo_path,
    validate_plan_targets,
    validate_patch_paths,
)
from asterism_worker.config.settings import Settings
from asterism_worker.contracts import AgentAssignment, ExecutionPlan, ExecutionResult, PlanRequest, PrdSpec
from asterism_worker.providers.factory import build_execution_provider, build_planner_provider
from asterism_worker.providers.claude_sdk import ClaudeSdkExecutionProvider
from asterism_worker.providers.fake import FakeExecutionProvider
from asterism_worker.providers.http import HttpExecutionProvider, HttpPlannerProvider
from asterism_worker.providers.planner import FakePlannerProvider


def test_execution_provider_comes_from_settings():
    fake = build_execution_provider(ResolvedAgentConfig(EngineConfig("fake"), ModelProfile()))
    http = build_execution_provider(ResolvedAgentConfig(
        EngineConfig("http", endpoint="http://executor/run"), ModelProfile(),
    ))
    claude = build_execution_provider(ResolvedAgentConfig(
        EngineConfig("claude_sdk", max_turns=7, effort_level="max"),
        ModelProfile(api_key="test-key", base_url="https://api.deepseek.com/anthropic", model="deepseek-v4-pro[1m]"),
    ))

    assert isinstance(fake, FakeExecutionProvider)
    assert isinstance(http, HttpExecutionProvider)
    assert isinstance(claude, ClaudeSdkExecutionProvider)
    assert claude.max_turns == 7
    assert claude.model_profile.base_url == "https://api.deepseek.com/anthropic"
    assert claude.model_env["ANTHROPIC_MODEL"] == "deepseek-v4-pro[1m]"
    assert claude.model_env["CLAUDE_CODE_SUBAGENT_MODEL"] == "deepseek-v4-pro[1m]"
    assert claude.model_env["CLAUDE_CODE_EFFORT_LEVEL"] == "max"


def test_claude_provider_prefers_system_model_config():
    claude = build_execution_provider(ResolvedAgentConfig(
        EngineConfig("claude_sdk"),
        ModelProfile(api_key="system-key", base_url="https://api.deepseek.com/anthropic",
                     model="deepseek-v4-pro", source="system"),
    ))

    assert isinstance(claude, ClaudeSdkExecutionProvider)
    assert claude.model_profile.api_key == "system-key"
    assert claude.model_env["ANTHROPIC_MODEL"] == "deepseek-v4-pro"
    assert claude.model_env["ANTHROPIC_DEFAULT_HAIKU_MODEL"] == "deepseek-v4-pro"


def test_planner_provider_comes_from_settings():
    fake = build_planner_provider(Settings(planner_provider="fake"))
    http = build_planner_provider(Settings(planner_provider="http", planner_http_endpoint="http://planner/plan"))

    assert isinstance(fake, FakePlannerProvider)
    assert isinstance(http, HttpPlannerProvider)


def test_fake_planner_uses_acceptance_criteria_for_deterministic_steps():
    provider = FakePlannerProvider()

    plan = asyncio.run(provider.plan(PlanRequest(
        system_id="system-1",
        prd=PrdSpec(
            title="登录页错误提示",
            goal="把登录页加错误提示",
            acceptance_criteria=["错误密码时显示提示"],
            draft_json={},
        ),
        repo_summary="",
        memories=[],
        allowed_paths=["src"],
        context_manifest_id="manifest-1",
    )))

    assert plan.steps == ["按验收标准修改: 错误密码时显示提示"]
    assert plan.target_files == ["src"]


def test_single_agent_mode_discards_planner_assignments(monkeypatch):
    class AssignedPlanner:
        async def plan(self, request):
            return ExecutionPlan(assignments=[AgentAssignment(role="frontend")])

    async def no_roles(settings, system_id):
        return []

    monkeypatch.setattr(execution_activities, "build_planner_provider", lambda settings: AssignedPlanner())
    monkeypatch.setattr(execution_activities, "available_role_metadata", no_roles)

    result = asyncio.run(plan_execution({
        "system_id": "system-1",
        "prd": {"goal": "改登录页"},
        "context_manifest_id": "manifest-1",
    }))

    assert result["assignments"] == []


def test_patch_path_gate_blocks_forbidden_path():
    diff = """diff --git a/src/app.py b/src/app.py
diff --git a/secrets/token.txt b/secrets/token.txt
"""

    result = validate_patch_paths(diff, allowed_paths=["src", "secrets"], forbidden_paths=["secrets"])

    assert result.blocked is True
    assert "forbidden" in result.reason


def test_patch_path_gate_blocks_outside_allowed_paths():
    diff = """diff --git a/src/app.py b/src/app.py
diff --git a/docs/readme.md b/docs/readme.md
"""

    result = validate_patch_paths(diff, allowed_paths=["src"], forbidden_paths=[])

    assert result.blocked is True
    assert "allowed" in result.reason


def test_run_execution_cleans_temporary_workspace(tmp_path, monkeypatch):
    repo = tmp_path / "repo"
    repo.mkdir()
    (repo / "README.md").write_text("asterism\n")
    workspace_root = tmp_path / "workspaces"
    monkeypatch.setenv("V5_WORKSPACE_ROOT", str(workspace_root))
    monkeypatch.setenv("V5_EXECUTION_PROVIDER", "fake")

    result = asyncio.run(run_execution({
        "case_id": "case-1",
        "work_item_id": "wi-1",
        "system_id": "system-1",
        "repo_path": str(repo),
        "goal": "把登录页加错误提示",
        "allowed_paths": [],
        "forbidden_paths": [],
        "test_commands": [],
        "acceptance_criteria": [],
        "plan": {"steps": ["改 README"], "target_files": ["README.md"], "test_plan": [], "risks": []},
        "memories": [],
        "context_manifest_id": "manifest-1",
    }))

    assert result["diff_patch"].startswith("diff --git")
    assert list(workspace_root.iterdir()) == []


def test_run_execution_sends_file_context_and_retries_bad_diff(tmp_path, monkeypatch):
    repo = tmp_path / "repo"
    repo.mkdir()
    (repo / "README.md").write_text("asterism\n")
    subprocess.run(["git", "init"], cwd=repo, check=True, capture_output=True)
    subprocess.run(["git", "add", "README.md"], cwd=repo, check=True, capture_output=True)
    subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@example.invalid", "commit", "-m", "init"], cwd=repo, check=True, capture_output=True)
    workspace_root = tmp_path / "workspaces"
    monkeypatch.setenv("V5_WORKSPACE_ROOT", str(workspace_root))

    class BadThenGoodProvider:
        def __init__(self) -> None:
            self.requests = []

        async def run(self, request):
            self.requests.append(request)
            if len(self.requests) == 1:
                return ExecutionResult(
                    summary="bad",
                    diff_patch=(
                        "diff --git a/missing.md b/missing.md\n"
                        "--- a/missing.md\n"
                        "+++ b/missing.md\n"
                        "@@ -1 +1 @@\n"
                        "-old\n"
                        "+new\n"
                    ),
                )
            return ExecutionResult(
                summary="good",
                diff_patch=(
                    "diff --git a/README.md b/README.md\n"
                    "--- a/README.md\n"
                    "+++ b/README.md\n"
                    "@@ -1 +1 @@\n"
                    "-asterism\n"
                    "+Asterism\n"
                ),
            )

    provider = BadThenGoodProvider()
    monkeypatch.setattr(execution_activities, "build_execution_provider", lambda resolved: provider)

    result = asyncio.run(run_execution({
        "case_id": "case-1",
        "work_item_id": "wi-1",
        "system_id": "system-1",
        "repo_path": str(repo),
        "goal": "改 README",
        "acceptance_criteria": [],
        "plan": {"steps": ["改 README"], "target_files": ["README.md"], "test_plan": [], "risks": []},
        "memories": [],
    }))

    assert result["summary"] == "good"
    assert "README.md" in provider.requests[0].file_listing
    assert provider.requests[0].file_contents["README.md"].startswith("asterism")
    assert provider.requests[1].previous_attempt is not None
    assert "missing.md" in provider.requests[1].previous_attempt.apply_error


def test_file_context_truncates_large_files(tmp_path):
    repo = tmp_path / "repo"
    repo.mkdir()
    (repo / "big.txt").write_text("x" * 20_000)

    context = collect_file_context(repo, ["big.txt"], file_limit=8, per_file_bytes=16_000, total_bytes=64_000)

    assert len(context.file_contents["big.txt"].encode()) <= 16_100
    assert "[truncated]" in context.file_contents["big.txt"]


def test_validate_plan_targets_blocks_when_all_files_are_missing(tmp_path):
    repo = tmp_path / "repo"
    repo.mkdir()

    with pytest.raises(RuntimeError, match="do not exist"):
        validate_plan_targets(str(repo), ["src/missing.py"])


def test_validate_plan_targets_allows_existing_anchor_with_new_file(tmp_path):
    repo = tmp_path / "repo"
    repo.mkdir()
    (repo / "README.md").write_text("existing\n")

    validate_plan_targets(str(repo), ["README.md", "src/new.py"])


def test_validate_plan_targets_blocks_unsafe_paths(tmp_path):
    with pytest.raises(RuntimeError, match="unsafe path"):
        validate_plan_targets(str(tmp_path), ["../secrets.txt"])


def test_release_repo_commits_to_work_item_branch(tmp_path):
    repo = tmp_path / "repo"
    repo.mkdir()
    (repo / "README.md").write_text("asterism\n")
    (repo / "unrelated.txt").write_text("keep\n")
    subprocess.run(["git", "init"], cwd=repo, check=True, capture_output=True)
    subprocess.run(["git", "add", "README.md", "unrelated.txt"], cwd=repo, check=True, capture_output=True)
    subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@example.invalid", "commit", "-m", "init"], cwd=repo, check=True, capture_output=True)
    (repo / "README.md").write_text("Asterism\n")
    (repo / "unrelated.txt").write_text("user change\n")
    (repo / "untracked.txt").write_text("user file\n")

    result = release_repo(str(repo), "wi-1", "登录页错误提示", "diff --git a/README.md b/README.md\n")

    assert result.branch == "wi/wi-1"
    assert result.commit_hash
    committed = subprocess.run(
        ["git", "show", "--format=", "--name-only", "HEAD"],
        cwd=repo,
        text=True,
        capture_output=True,
        check=True,
    ).stdout.splitlines()
    status = subprocess.run(
        ["git", "status", "--porcelain"], cwd=repo, text=True, capture_output=True, check=True
    ).stdout
    assert committed == ["README.md"]
    assert " M unrelated.txt" in status
    assert "?? untracked.txt" in status


def test_summarize_repo_includes_tree_and_manifest_heads(tmp_path):
    repo = tmp_path / "repo"
    (repo / "src").mkdir(parents=True)
    (repo / ".git").mkdir()
    (repo / "node_modules").mkdir()
    (repo / "src" / "app.py").write_text("print('hi')\n")
    (repo / "README.md").write_text("asterism\n" + "line\n" * 40)

    summary = summarize_repo_path(str(repo))

    assert "src/" in summary
    assert "src/app.py" in summary
    assert ".git" not in summary
    assert "node_modules" not in summary
    assert "README.md" in summary
    assert "asterism" in summary


def test_summarize_repo_truncates_large_output(tmp_path):
    repo = tmp_path / "repo"
    repo.mkdir()
    (repo / "README.md").write_text("x" * 10_000)

    summary = summarize_repo_path(str(repo), max_bytes=200)

    assert len(summary.encode()) <= 260
    assert "truncated" in summary


def test_summarize_repo_missing_repo_returns_empty_string(tmp_path):
    assert summarize_repo_path(str(tmp_path / "missing")) == ""


def test_summarize_repo_activity_returns_summary(tmp_path):
    repo = tmp_path / "repo"
    repo.mkdir()
    (repo / "pyproject.toml").write_text("[project]\nname='demo'\n")

    summary = asyncio.run(summarize_repo({"repo_path": str(repo)}))

    assert "pyproject.toml" in summary
    assert "name='demo'" in summary


def test_run_validation_passes_when_all_commands_exit_zero(tmp_path):
    result = asyncio.run(run_validation({
        "repo_path": str(tmp_path),
        "test_commands": [f"{sys.executable} -c \"print('ok')\""],
    }))

    assert result["passed"] is True
    assert result["commands"][0]["exit_code"] == 0
    assert "ok" in result["commands"][0]["stdout_tail"]


def test_run_validation_fails_on_first_non_zero_command(tmp_path):
    result = asyncio.run(run_validation({
        "repo_path": str(tmp_path),
        "test_commands": [f"{sys.executable} -c \"import sys; print('bad', file=sys.stderr); sys.exit(7)\""],
    }))

    assert result["passed"] is False
    assert result["failed_command"].startswith(sys.executable)
    assert result["commands"][0]["exit_code"] == 7
    assert "bad" in result["stderr_tail"]


def test_default_worker_token_is_rejected_outside_local_profile():
    with pytest.raises(ValueError, match="默认 worker token"):
        Settings(worker_callback_token="dev-worker-token", profile="prod")


def test_claude_provider_without_key_fails_fast():
    with pytest.raises(RuntimeError, match="模型 Profile API key"):
        build_execution_provider(ResolvedAgentConfig(EngineConfig("claude_sdk"), ModelProfile()))


def test_run_execution_returns_empty_diff_to_state_machine(tmp_path, monkeypatch):
    repo = tmp_path / "repo"
    repo.mkdir()
    (repo / "README.md").write_text("asterism\n")
    subprocess.run(["git", "init"], cwd=repo, check=True, capture_output=True)
    subprocess.run(["git", "add", "README.md"], cwd=repo, check=True, capture_output=True)
    subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@example.invalid", "commit", "-m", "init"],
                   cwd=repo, check=True, capture_output=True)

    class EmptyProvider:
        async def run(self, request):
            return ExecutionResult(summary="no changes", diff_patch="")

    monkeypatch.setenv("V5_WORKSPACE_ROOT", str(tmp_path / "workspaces"))
    monkeypatch.setattr(execution_activities, "build_execution_provider", lambda resolved: EmptyProvider())

    result = asyncio.run(run_execution({
        "case_id": "case-1",
        "work_item_id": "wi-1",
        "system_id": "system-1",
        "repo_path": str(repo),
        "goal": "无需修改",
        "plan": {"steps": ["检查"], "target_files": ["README.md"]},
    }))

    assert result["diff_patch"] == ""


def test_run_execution_blocks_role_scope_violation(tmp_path, monkeypatch):
    repo = tmp_path / "repo"
    (repo / "web").mkdir(parents=True)
    (repo / "api").mkdir()
    (repo / "web" / "app.ts").write_text("old\n")
    (repo / "api" / "app.py").write_text("old\n")
    subprocess.run(["git", "init"], cwd=repo, check=True, capture_output=True)
    subprocess.run(["git", "add", "."], cwd=repo, check=True, capture_output=True)
    subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@example.invalid", "commit", "-m", "init"], cwd=repo, check=True, capture_output=True)

    class OutsideProvider:
        async def run(self, request):
            return ExecutionResult(summary="wrong scope", diff_patch=(
                "diff --git a/api/app.py b/api/app.py\n--- a/api/app.py\n+++ b/api/app.py\n"
                "@@ -1 +1 @@\n-old\n+new\n"
            ))

    async def resolved(*args, **kwargs):
        return ResolvedAgentConfig(
            EngineConfig("fake"), ModelProfile(), AgentConstraints(role_id="frontend", path_scope=("web",)),
        )

    monkeypatch.setenv("V5_WORKSPACE_ROOT", str(tmp_path / "workspaces"))
    monkeypatch.setattr(execution_activities, "resolve_agent_config", resolved)
    monkeypatch.setattr(execution_activities, "build_execution_provider", lambda value: OutsideProvider())
    result = asyncio.run(run_execution({
        "case_id": "case", "work_item_id": "wi", "system_id": "sys", "repo_path": str(repo),
        "goal": "改前端", "role_id": "frontend",
        "plan": {"steps": ["改前端"], "target_files": ["web/app.ts", "api/app.py"]},
    }))

    assert result["blocked_reason"] == "role_scope_violation"
    assert result["blocked_detail"] == "api/app.py"


def test_run_execution_resolves_distinct_profile_and_engine_per_role(tmp_path, monkeypatch):
    repo = tmp_path / "repo"
    (repo / "web").mkdir(parents=True)
    (repo / "api").mkdir()
    (repo / "web" / "app.ts").write_text("old-web\n")
    (repo / "api" / "app.py").write_text("old-api\n")
    subprocess.run(["git", "init"], cwd=repo, check=True, capture_output=True)
    subprocess.run(["git", "add", "."], cwd=repo, check=True, capture_output=True)
    subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@example.invalid", "commit", "-m", "init"], cwd=repo, check=True, capture_output=True)
    built: list[tuple[str, str, str]] = []

    async def resolve(settings, system_id, role_id="", **kwargs):
        engine = "claude_sdk" if role_id == "frontend" else "deepagents"
        profile = ModelProfile(id=f"mp-{role_id}", model=f"model-{role_id}", api_key=f"key-{role_id}")
        return ResolvedAgentConfig(EngineConfig(engine), profile, AgentConstraints(role_id=role_id, path_scope=("web" if role_id == "frontend" else "api",)))

    def build(resolved):
        built.append((resolved.constraints.role_id, resolved.engine.name, resolved.model_profile.id))
        role = resolved.constraints.role_id

        class Provider:
            async def run(self, request):
                path, old, new = ("web/app.ts", "old-web", "new-web") if role == "frontend" else ("api/app.py", "old-api", "new-api")
                return ExecutionResult(summary=role, diff_patch=(
                    f"diff --git a/{path} b/{path}\n--- a/{path}\n+++ b/{path}\n@@ -1 +1 @@\n-{old}\n+{new}\n"
                ))
        return Provider()

    monkeypatch.setenv("V5_WORKSPACE_ROOT", str(tmp_path / "workspaces"))
    monkeypatch.setattr(execution_activities, "resolve_agent_config", resolve)
    monkeypatch.setattr(execution_activities, "build_execution_provider", build)
    base = {
        "case_id": "case", "work_item_id": "wi", "system_id": "sys", "repo_path": str(repo),
        "goal": "前后端修改", "plan": {"steps": ["web", "api"], "target_files": ["web/app.ts", "api/app.py"]},
    }
    frontend = asyncio.run(run_execution({**base, "role_id": "frontend", "role_scope": ["web"]}))
    backend = asyncio.run(run_execution({**base, "role_id": "backend", "role_scope": ["api"]}))

    assert built == [("frontend", "claude_sdk", "mp-frontend"), ("backend", "deepagents", "mp-backend")]
    assert frontend["engine"] == "claude_sdk"
    assert backend["engine"] == "deepagents"
    assert "key-frontend" not in str(frontend)
    assert "key-backend" not in str(backend)
