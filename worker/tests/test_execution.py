import asyncio
import subprocess

import pytest
from temporalio.exceptions import ApplicationError

from asterism_worker.activities import execution as execution_activity
from asterism_worker.activities.execution import (
    _apply_previous_candidate,
    _restore_revision_candidate,
    apply_patch_to_repo,
    generate_coding_plan,
    run_coding_attempt,
    run_validation,
)
from asterism_worker.agent_config import AgentConstraints, EngineConfig, ModelProfile, ResolvedAgentConfig
from asterism_worker.contracts import CodingAttemptRequest, RepoSnapshot
from asterism_worker.providers.claude_sdk_team import ClaudeSdkTeamProvider
from asterism_worker.providers.claude_sdk_planning import PlanningOutputError
from asterism_worker.providers.factory import build_execution_provider
from asterism_worker.providers.fake import FakeExecutionProvider
from asterism_worker.repo_source import TeamWorkspace


def _git_repo(path):
    path.mkdir()
    (path / "README.md").write_text("asterism\n")
    subprocess.run(["git", "init"], cwd=path, check=True, capture_output=True)
    subprocess.run(["git", "add", "README.md"], cwd=path, check=True, capture_output=True)
    subprocess.run([
        "git", "-c", "user.name=test", "-c", "user.email=test@example.invalid",
        "commit", "-m", "init",
    ], cwd=path, check=True, capture_output=True)


def test_factory_only_accepts_fake_and_claude_sdk_team():
    fake = build_execution_provider(ResolvedAgentConfig(EngineConfig("fake"), ModelProfile()))
    assert isinstance(fake, FakeExecutionProvider)

    team = build_execution_provider(ResolvedAgentConfig(
        EngineConfig("claude_sdk_team"), ModelProfile(api_key="secret"),
        constraints=AgentConstraints(role_id="developer"),
    ))
    assert isinstance(team, ClaudeSdkTeamProvider)

    with pytest.raises(ValueError, match="unsupported"):
        build_execution_provider(ResolvedAgentConfig(EngineConfig("http"), ModelProfile()))


def _stub_planning_activity(monkeypatch, tmp_path, error):
    class FailingProvider:
        async def plan(self, _request, _workspace):
            raise error

    async def prepare_workspace(*_args):
        return TeamWorkspace(tmp_path, {"main": tmp_path})

    async def resolve_config(*_args, **_kwargs):
        return object()

    monkeypatch.setattr(execution_activity, "load_settings", object)
    monkeypatch.setattr(execution_activity, "prepare_case_workspace", prepare_workspace)
    monkeypatch.setattr(execution_activity, "resolve_agent_config", resolve_config)
    monkeypatch.setattr(execution_activity, "build_execution_provider", lambda _resolved: FailingProvider())


def _planning_request():
    return {
        "case_id": "case-1",
        "work_item_id": "wi-1",
        "system_id": "sys-1",
        "requirement_manifest_id": "manifest-1",
        "repos": [{"repo_id": "main"}],
        "goal": "修复保存",
    }


def test_generate_coding_plan_marks_output_error_non_retryable(tmp_path, monkeypatch):
    _stub_planning_activity(monkeypatch, tmp_path, PlanningOutputError("structured_output_invalid"))

    with pytest.raises(ApplicationError) as caught:
        asyncio.run(generate_coding_plan(_planning_request()))

    assert caught.value.type == "PLAN_OUTPUT_INVALID"
    assert caught.value.non_retryable is True
    assert caught.value.message == "structured_output_invalid"


def test_generate_coding_plan_keeps_runtime_error_retryable(tmp_path, monkeypatch):
    _stub_planning_activity(monkeypatch, tmp_path, RuntimeError("temporary network error"))

    with pytest.raises(RuntimeError, match="temporary network error"):
        asyncio.run(generate_coding_plan(_planning_request()))


def test_previous_candidate_is_restored_before_revision(tmp_path):
    repo = tmp_path / "repo"
    _git_repo(repo)
    request = CodingAttemptRequest.model_validate({
        "case_id": "case-1", "work_item_id": "wi-1", "system_id": "sys-1",
        "requirement_manifest_id": "manifest-1",
        "repos": [{"repo_id": "main", "local_path": str(repo)}],
        "goal": "修订 README",
        "previous_candidate": [{
            "repo": "main",
            "diff_patch": """diff --git a/README.md b/README.md
--- a/README.md
+++ b/README.md
@@ -1 +1 @@
-asterism
+Asterism
""",
        }],
    })

    _apply_previous_candidate(request, TeamWorkspace(tmp_path, {"main": repo}))

    assert (repo / "README.md").read_text() == "Asterism\n"


def test_unrestorable_revision_candidate_falls_back_to_clean_full_mode(tmp_path):
    repo = tmp_path / "repo"
    _git_repo(repo)
    request = CodingAttemptRequest.model_validate({
        "case_id": "case-1", "work_item_id": "wi-1", "system_id": "sys-1",
        "requirement_manifest_id": "manifest-1",
        "repos": [{"repo_id": "main", "local_path": str(repo)}],
        "goal": "修订 README",
        "previous_candidate": [{
            "repo": "main",
            "diff_patch": "diff --git a/README.md b/README.md\n--- a/README.md\n+++ b/README.md\n@@ -1 +1 @@\n-missing\n+new\n",
        }],
        "revision_context": {
            "revision": 1, "revision_mode": "incremental", "feedback": "只修改标题",
        },
    })

    restored = _restore_revision_candidate(request, TeamWorkspace(tmp_path, {"main": repo}))

    assert restored.previous_candidate == []
    assert restored.revision_context.revision_mode == "full"
    assert (repo / "README.md").read_text() == "asterism\n"


def test_partial_candidate_restore_resets_every_touched_repo_before_full_fallback(tmp_path):
    first = tmp_path / "first"
    second = tmp_path / "second"
    _git_repo(first)
    _git_repo(second)
    request = CodingAttemptRequest.model_validate({
        "case_id": "case-1", "work_item_id": "wi-1", "system_id": "sys-1",
        "requirement_manifest_id": "manifest-1",
        "repos": [{"repo_id": "first"}, {"repo_id": "second"}],
        "goal": "修订多仓 README",
        "previous_candidate": [
            {
                "repo": "first",
                "diff_patch": "diff --git a/README.md b/README.md\n--- a/README.md\n+++ b/README.md\n@@ -1 +1 @@\n-asterism\n+first\n",
            },
            {
                "repo": "second",
                "diff_patch": "diff --git a/README.md b/README.md\n--- a/README.md\n+++ b/README.md\n@@ -1 +1 @@\n-missing\n+second\n",
            },
        ],
        "revision_context": {
            "revision": 1, "revision_mode": "incremental", "feedback": "只修改标题",
        },
    })

    restored = _restore_revision_candidate(request, TeamWorkspace(tmp_path, {"first": first, "second": second}))

    assert restored.previous_candidate == []
    assert restored.revision_context.revision_mode == "full"
    assert (first / "README.md").read_text() == "asterism\n"
    assert (second / "README.md").read_text() == "asterism\n"


def test_run_coding_attempt_uses_terminal_fake_baseline_and_cleans_workspace(tmp_path, monkeypatch):
    repo = tmp_path / "repo"
    _git_repo(repo)
    workspace_root = tmp_path / "workspaces"
    monkeypatch.setenv("V5_EXECUTION_ENGINE", "fake")
    monkeypatch.setenv("V5_WORKSPACE_ROOT", str(workspace_root))
    monkeypatch.setenv("V5_CONTROL_PLANE_URL", "http://127.0.0.1:1")

    result = asyncio.run(run_coding_attempt({
        "case_id": "case-1", "work_item_id": "wi-1", "system_id": "sys-1",
        "requirement_manifest_id": "manifest-1",
        "repos": [{"repo_id": "main", "local_path": str(repo)}],
        "goal": "规范项目名",
    }))

    assert result["execution_provider"] == "fake"
    assert result["repo_changes"][0]["repo"] == "main"
    assert list(workspace_root.iterdir()) == []


def test_apply_patch_is_idempotent_and_enforces_paths(tmp_path):
    repo = tmp_path / "repo"
    _git_repo(repo)
    patch = """diff --git a/README.md b/README.md
--- a/README.md
+++ b/README.md
@@ -1 +1 @@
-asterism
+Asterism
"""

    first = asyncio.run(apply_patch_to_repo({
        "repo_path": str(repo), "diff_patch": patch, "allowed_paths": ["README.md"],
    }))
    second = asyncio.run(apply_patch_to_repo({
        "repo_path": str(repo), "diff_patch": patch, "allowed_paths": ["README.md"],
    }))
    blocked = asyncio.run(apply_patch_to_repo({
        "repo_path": str(repo), "diff_patch": patch, "allowed_paths": ["src"],
    }))

    assert first["blocked"] is False
    assert second["already_applied"] is True
    assert blocked["blocked"] is True


def test_validation_returns_command_evidence(tmp_path):
    result = asyncio.run(run_validation({
        "repo_path": str(tmp_path),
        "test_commands": ["printf ok", "false"],
    }))

    assert result["passed"] is False
    assert result["failed_command"] == "false"
    assert result["commands"][-1]["exit_code"] == 1
