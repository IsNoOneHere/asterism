import logging
import os
import re
import shutil
import subprocess
import tempfile
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol
from urllib.parse import quote, urlsplit

import httpx

from asterism_worker.config.settings import Settings
from asterism_worker.contracts import RepoSnapshot

log = logging.getLogger(__name__)


class RepoSourcePort(Protocol):
    def prepare(self, repo: RepoSnapshot, workspace_root: str) -> Path:
        """准备隔离工作区并返回仓库目录。"""


class LocalRepoSource:
    def prepare(self, repo: RepoSnapshot, workspace_root: str) -> Path:
        workspace = _workspace(workspace_root, repo.repo_id)
        source = Path(repo.local_path).expanduser() if repo.local_path else None
        if not source or not source.exists():
            # fake/http provider 的旧行为允许空仓库，local 模式保持不变。
            return workspace
        target = workspace / "repo"
        if (source / ".git").exists():
            subprocess.run(["git", "clone", "--quiet", str(source), str(target)], check=True)
        else:
            shutil.copytree(source, target)
        return target


@dataclass(slots=True)
class GitlabRepoSource:
    base_url: str
    token: str

    def prepare(self, repo: RepoSnapshot, workspace_root: str) -> Path:
        workspace = _workspace(workspace_root, repo.repo_id)
        target = workspace / "repo"
        clone_url = f"{self.base_url.rstrip('/')}/{repo.gitlab_project.strip('/')}.git"
        parsed = urlsplit(clone_url)
        if not parsed.scheme or not parsed.netloc or not self.token:
            raise RuntimeError("GitLab clone 配置不完整")
        env = {**os.environ, "GIT_TERMINAL_PROMPT": "0"}
        try:
            with git_credentials(clone_url, self.token, workspace) as auth:
                subprocess.run([
                    "git", *auth, "clone", "--quiet", "--depth", "50", "--single-branch", "--branch",
                    repo.default_branch, clone_url, str(target),
                ], check=True, capture_output=True, text=True, env=env)
            log.info("GitLab 仓库工作区已准备 repo=%s project=%s", repo.repo_id, repo.gitlab_project)
            return target
        except Exception:
            shutil.rmtree(workspace, ignore_errors=True)
            raise


async def prepare_repo_workspace(repo: RepoSnapshot, system_id: str, settings: Settings) -> Path:
    if repo.clone_mode != "gitlab":
        return LocalRepoSource().prepare(repo, settings.workspace_root)
    base_url, token = await fetch_git_connection(system_id, settings)
    return GitlabRepoSource(base_url, token).prepare(repo, settings.workspace_root)


async def fetch_git_connection(system_id: str, settings: Settings) -> tuple[str, str]:
    url = settings.control_plane_url.rstrip("/") + f"/api/v5/internal/systems/{system_id}/git-config"
    headers = {"Authorization": f"Bearer {settings.worker_callback_token}"}
    async with httpx.AsyncClient(timeout=10) as client:
        response = await client.get(url, headers=headers)
        response.raise_for_status()
    data = response.json()
    return str(data.get("base_url", "")), str(data.get("token", ""))


def cleanup_repo_workspace(repo_path: Path) -> None:
    root = repo_path.parent if repo_path.name == "repo" and repo_path.parent.name.startswith("case-") else repo_path
    shutil.rmtree(root, ignore_errors=True)


@contextmanager
def git_credentials(url: str, token: str, directory: Path):
    """短时创建 0600 credential store，退出上下文即删除。"""

    parsed = urlsplit(url)
    credentials = directory / ".git-credentials"
    credentials.write_text(
        f"{parsed.scheme}://oauth2:{quote(token, safe='')}@{parsed.netloc}\n",
        encoding="utf-8",
    )
    credentials.chmod(0o600)
    try:
        yield ["-c", f"credential.helper=store --file={credentials}"]
    finally:
        credentials.unlink(missing_ok=True)


def _workspace(workspace_root: str, repo_id: str) -> Path:
    root = Path(workspace_root)
    root.mkdir(parents=True, exist_ok=True)
    prefix = re.sub(r"[^a-zA-Z0-9_.-]", "-", repo_id) or "repo"
    return Path(tempfile.mkdtemp(prefix=f"case-{prefix}-", dir=root))
