import asyncio
import stat
import subprocess
from pathlib import Path
from urllib.parse import quote

import pytest

from asterism_worker import repo_source
from asterism_worker.config.settings import Settings
from asterism_worker.contracts import RepoSnapshot
from asterism_worker.networking import subprocess_environment
from asterism_worker.repo_source import (
    GitlabRepoSource,
    LocalRepoSource,
    cleanup_repo_workspace,
    prepare_case_workspace,
)


def test_private_git_endpoint_extends_both_no_proxy_variants():
    original = {"NO_PROXY": "localhost,postgres", "no_proxy": "temporal"}

    env = subprocess_environment("http://10.96.230.251:8080/group/api.git", original)

    assert env["NO_PROXY"] == "localhost,postgres,10.96.230.251"
    assert env["no_proxy"] == "temporal,10.96.230.251"
    assert env["GIT_TERMINAL_PROMPT"] == "0"
    assert original == {"NO_PROXY": "localhost,postgres", "no_proxy": "temporal"}


def test_external_git_endpoint_keeps_proxy_bypass_unchanged():
    env = subprocess_environment(
        "https://gitlab.com/group/api.git",
        {"NO_PROXY": "localhost", "no_proxy": "temporal"},
    )

    assert env["NO_PROXY"] == "localhost"
    assert env["no_proxy"] == "temporal"


def test_local_repo_source_clones_into_isolated_workspace(tmp_path):
    source = tmp_path / "source"
    source.mkdir()
    (source / "README.md").write_text("asterism\n")
    subprocess.run(["git", "init"], cwd=source, check=True, capture_output=True)
    subprocess.run(["git", "add", "README.md"], cwd=source, check=True, capture_output=True)
    subprocess.run(["git", "-c", "user.name=test", "-c", "user.email=test@example.invalid",
                    "commit", "-m", "init"], cwd=source, check=True, capture_output=True)

    workspace = LocalRepoSource().prepare(
        RepoSnapshot(repo_id="main", local_path=str(source)), str(tmp_path / "workspaces"),
    )

    assert workspace != source
    assert (workspace / "README.md").read_text() == "asterism\n"
    cleanup_repo_workspace(workspace)
    assert not workspace.parent.exists()


def test_local_repo_source_disables_local_clone_optimization(monkeypatch, tmp_path):
    source = tmp_path / "source"
    (source / ".git").mkdir(parents=True)
    commands: list[list[str]] = []

    def fake_run(command, **_kwargs):
        commands.append(command)
        Path(command[-1]).mkdir(parents=True)
        return subprocess.CompletedProcess(command, 0, "", "")

    monkeypatch.setattr(subprocess, "run", fake_run)

    LocalRepoSource().prepare(
        RepoSnapshot(repo_id="main", local_path=str(source)), str(tmp_path / "workspaces"),
    )

    assert len(commands) == 1
    assert commands[0][:4] == ["git", "clone", "--quiet", "--no-local"]
    assert commands[0][-2] == str(source)


def test_local_repo_source_removes_failed_clone(monkeypatch, tmp_path):
    source = tmp_path / "source"
    (source / ".git").mkdir(parents=True)
    workspace_root = tmp_path / "workspaces"

    def fail_clone(command, **_kwargs):
        Path(command[-1]).mkdir(parents=True)
        raise subprocess.CalledProcessError(128, command)

    monkeypatch.setattr(subprocess, "run", fail_clone)

    with pytest.raises(subprocess.CalledProcessError):
        LocalRepoSource().prepare(
            RepoSnapshot(repo_id="main", local_path=str(source)), str(workspace_root),
        )

    assert list(workspace_root.iterdir()) == []


def test_case_workspace_prepares_clones_inside_artifact_staging(monkeypatch, tmp_path):
    settings = Settings(
        workspace_root=str(tmp_path / "transient"),
        artifacts_root=str(tmp_path / "artifacts"),
    )
    roots: list[Path] = []

    async def prepare(repo, _system_id, _settings, workspace_root=None):
        root = Path(workspace_root)
        roots.append(root)
        workspace = root / f"case-{repo.repo_id}" / "repo"
        workspace.mkdir(parents=True)
        (workspace / "README.md").write_text(repo.repo_id)
        return workspace

    monkeypatch.setattr(repo_source, "prepare_repo_workspace", prepare)

    workspace = asyncio.run(prepare_case_workspace([
        RepoSnapshot(repo_id="backend"),
        RepoSnapshot(repo_id="frontend"),
    ], "system-1", "case-1", settings))

    case_root = Path(settings.artifacts_root) / "cases" / "case-1"
    assert roots and all(root.parent == case_root for root in roots)
    assert workspace.root == case_root / "workspace"
    assert (workspace.repos["backend"] / "README.md").read_text() == "backend"
    assert (workspace.repos["frontend"] / "README.md").read_text() == "frontend"
    assert not Path(settings.workspace_root).exists()


def test_gitlab_repo_source_uses_temporary_credential_store(monkeypatch, tmp_path, caplog):
    token = "secret-token-value"
    observed: dict[str, object] = {}

    def fake_run(command, **kwargs):
        observed["command"] = command
        helper = next(value for value in command if value.startswith("credential.helper="))
        credentials = Path(helper.split("--file=", 1)[1])
        observed["credential_mode"] = stat.S_IMODE(credentials.stat().st_mode)
        observed["credential_text"] = credentials.read_text()
        target = Path(command[-1])
        (target / ".git").mkdir(parents=True)
        (target / ".git" / "config").write_text(f"url = {command[-2]}\n")
        return subprocess.CompletedProcess(command, 0, "", "")

    monkeypatch.setattr(subprocess, "run", fake_run)
    repo = RepoSnapshot(repo_id="backend", clone_mode="gitlab", gitlab_project="group/api")

    workspace = GitlabRepoSource("https://gitlab.example", token).prepare(repo, str(tmp_path / "workspaces"))

    assert observed["credential_mode"] == 0o600
    assert token in str(observed["credential_text"])
    assert token not in str(observed["command"])
    assert token not in caplog.text
    assert not (workspace.parent / ".git-credentials").exists()
    assert token not in (workspace / ".git" / "config").read_text()
    cleanup_repo_workspace(workspace)


def test_gitlab_clone_failure_redacts_credentials_but_keeps_diagnostics(monkeypatch, tmp_path, caplog):
    token = "secret/token value"
    encoded_token = quote(token, safe="")
    workspace_root = tmp_path / "workspaces"

    def fail_clone(command, **_kwargs):
        raise subprocess.CalledProcessError(
            128,
            command,
            stderr=(
                f"fatal: unable to access 'http://oauth2:{encoded_token}@10.96.230.251/group/api.git': "
                f"private-token={token} upstream returned 502"
            ),
        )

    monkeypatch.setattr(subprocess, "run", fail_clone)
    caplog.set_level("WARNING")
    repo = RepoSnapshot(repo_id="backend", clone_mode="gitlab", gitlab_project="group/api")

    with pytest.raises(RuntimeError) as raised:
        GitlabRepoSource("http://10.96.230.251", token).prepare(repo, str(workspace_root))

    diagnostic = f"{raised.value}\n{caplog.text}"
    assert "exit 128" in diagnostic
    assert "upstream returned 502" in diagnostic
    assert token not in diagnostic
    assert encoded_token not in diagnostic
    assert list(workspace_root.iterdir()) == []
