import stat
import subprocess
from pathlib import Path

from asterism_worker.contracts import RepoSnapshot
from asterism_worker.repo_source import GitlabRepoSource, LocalRepoSource, cleanup_repo_workspace


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
