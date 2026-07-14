import asyncio
import subprocess

from asterism_worker.agent_config import EngineConfig, ModelProfile
from asterism_worker.contracts import ExecutionPlan, ExecutionRequest
from asterism_worker.providers.deepagents import DeepAgentsExecutionProvider


def test_deepagents_mock_runner_collects_workspace_diff(tmp_path):
    repo = tmp_path / "repo"
    repo.mkdir()
    (repo / "README.md").write_text("before\n")
    subprocess.run(["git", "init"], cwd=repo, check=True, capture_output=True)
    subprocess.run(["git", "add", "."], cwd=repo, check=True, capture_output=True)
    subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@example.invalid", "commit", "-m", "init"], cwd=repo, check=True, capture_output=True)

    async def runner(request, workspace):
        assert request.step_refs == ["backend"]
        (workspace / "README.md").write_text("after\n")
        return {"messages": [], "usage": {"input_tokens": 10}}

    provider = DeepAgentsExecutionProvider(
        ModelProfile(provider="openai-compat", model="test", api_key="test-key"),
        EngineConfig("deepagents"), str(tmp_path / "artifacts"), {"runner": runner},
    )
    request = ExecutionRequest(
        case_id="case", work_item_id="wi", system_id="sys", repo_path=str(repo), goal="改 README",
        plan=ExecutionPlan(steps=["backend"]), role_id="backend", assignment_index=1, step_refs=["backend"],
    )
    result = asyncio.run(provider.run(request))

    assert "README.md" in result.diff_patch
    assert result.execution_provider == "deepagents"
    assert (tmp_path / "artifacts" / "wi" / "deepagents-transcript-1-backend.jsonl").exists()
    assert (repo / "README.md").read_text() == "before\n"
