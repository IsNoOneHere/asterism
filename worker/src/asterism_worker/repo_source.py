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


@dataclass(slots=True)
class TeamWorkspace:
    root: Path
    repos: dict[str, Path]
    persistent: bool = False


def reset_team_workspace(workspace: TeamWorkspace) -> None:
    """候选已持久化后，把稳定工作区恢复到批准计划的 Git 基线。"""

    for repo_id, repo_path in workspace.repos.items():
        safe_directory = f"safe.directory={repo_path.resolve()}"
        subprocess.run(
            ["git", "-c", safe_directory, "reset", "--hard", "HEAD"],
            cwd=repo_path, check=True, capture_output=True, text=True,
        )
        subprocess.run(
            ["git", "-c", safe_directory, "clean", "-fd"],
            cwd=repo_path, check=True, capture_output=True, text=True,
        )
        log.info("Coding 工作区已恢复 Git 基线 repo=%s", repo_id)


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


async def prepare_team_workspace(
    repos: list[RepoSnapshot], system_id: str, settings: Settings,
) -> TeamWorkspace:
    """把多个隔离克隆收拢到同一个 Claude SDK 团队工作区。"""

    root = Path(settings.workspace_root)
    root.mkdir(parents=True, exist_ok=True)
    team_root = Path(tempfile.mkdtemp(prefix="case-team-", dir=root))
    prepared: dict[str, Path] = {}
    try:
        for index, repo in enumerate(repos):
            workspace = await prepare_repo_workspace(repo, system_id, settings)
            directory = re.sub(r"[^a-zA-Z0-9_.-]", "-", repo.repo_id) or f"repo-{index + 1}"
            target = team_root / directory
            shutil.move(str(workspace), target)
            if workspace.name == "repo":
                shutil.rmtree(workspace.parent, ignore_errors=True)
            prepared[repo.repo_id] = target
        log.info("Claude SDK 团队工作区已准备 repo_count=%s", len(prepared))
        return TeamWorkspace(team_root, prepared)
    except Exception:
        shutil.rmtree(team_root, ignore_errors=True)
        raise


async def prepare_case_workspace(
    repos: list[RepoSnapshot], system_id: str, case_id: str, settings: Settings,
) -> TeamWorkspace:
    """在持久卷准备稳定工作区，使 Claude Session 可跨 Activity 恢复。"""

    case_root = _case_root(settings.artifacts_root, case_id)
    workspace_root = case_root / "workspace"
    if workspace_root.exists():
        return open_case_workspace(repos, case_id, settings)
    case_root.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix="workspace-staging-", dir=case_root))
    try:
        await _populate_case_staging(repos, system_id, settings, staging)
        staging.rename(workspace_root)
        log.info("Claude SDK 持久工作区已准备 case=%s repo_count=%s", case_id, len(repos))
        return TeamWorkspace(workspace_root, {
            repo.repo_id: workspace_root / _repo_directory(repo.repo_id, index)
            for index, repo in enumerate(repos)
        }, persistent=True)
    except Exception:
        shutil.rmtree(staging, ignore_errors=True)
        raise


async def refresh_case_workspace(
    repos: list[RepoSnapshot], system_id: str, case_id: str, settings: Settings,
) -> TeamWorkspace:
    """原子刷新 Case 代码基线，Claude runtime 与 Session 文件保持不变。"""

    case_root = _case_root(settings.artifacts_root, case_id)
    workspace_root = case_root / "workspace"
    case_root.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix="workspace-staging-", dir=case_root))
    backup = Path(tempfile.mkdtemp(prefix="workspace-backup-", dir=case_root))
    backup.rmdir()
    try:
        await _populate_case_staging(repos, system_id, settings, staging)
        if workspace_root.exists():
            workspace_root.rename(backup)
        try:
            staging.rename(workspace_root)
        except Exception:
            if backup.exists() and not workspace_root.exists():
                backup.rename(workspace_root)
            raise
        shutil.rmtree(backup, ignore_errors=True)
        log.info("Claude SDK 持久工作区已刷新 case=%s repo_count=%s", case_id, len(repos))
        return open_case_workspace(repos, case_id, settings)
    except Exception:
        shutil.rmtree(staging, ignore_errors=True)
        shutil.rmtree(backup, ignore_errors=True)
        raise


async def _populate_case_staging(
    repos: list[RepoSnapshot], system_id: str, settings: Settings, staging: Path,
) -> None:
    """把所有仓库放入同一个未发布的 Case staging 目录。"""

    for index, repo in enumerate(repos):
        workspace = await prepare_repo_workspace(repo, system_id, settings)
        target = staging / _repo_directory(repo.repo_id, index)
        shutil.move(str(workspace), target)
        if workspace.name == "repo":
            shutil.rmtree(workspace.parent, ignore_errors=True)


def open_case_workspace(repos: list[RepoSnapshot], case_id: str, settings: Settings) -> TeamWorkspace:
    root = _case_root(settings.artifacts_root, case_id) / "workspace"
    paths = {
        repo.repo_id: root / _repo_directory(repo.repo_id, index)
        for index, repo in enumerate(repos)
    }
    missing = [repo_id for repo_id, path in paths.items() if not path.exists()]
    if missing:
        raise RuntimeError(f"持久工作区缺少仓库: {', '.join(missing)}")
    return TeamWorkspace(root, paths, persistent=True)


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
    # 从创建瞬间即限制为当前用户读写，避免 chmod 前的短暂宽权限窗口。
    descriptor = os.open(credentials, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
        stream.write(f"{parsed.scheme}://oauth2:{quote(token, safe='')}@{parsed.netloc}\n")
    try:
        yield ["-c", f"credential.helper=store --file={credentials}"]
    finally:
        credentials.unlink(missing_ok=True)


def _workspace(workspace_root: str, repo_id: str) -> Path:
    root = Path(workspace_root)
    root.mkdir(parents=True, exist_ok=True)
    prefix = re.sub(r"[^a-zA-Z0-9_.-]", "-", repo_id) or "repo"
    return Path(tempfile.mkdtemp(prefix=f"case-{prefix}-", dir=root))


def _case_root(artifacts_root: str, case_id: str) -> Path:
    safe_case = re.sub(r"[^a-zA-Z0-9_.-]", "-", case_id) or "case"
    return Path(artifacts_root) / "cases" / safe_case


def _repo_directory(repo_id: str, index: int) -> str:
    return re.sub(r"[^a-zA-Z0-9_.-]", "-", repo_id) or f"repo-{index + 1}"
