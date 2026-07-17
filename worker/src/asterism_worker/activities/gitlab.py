import logging
import os
import subprocess
from pathlib import Path
from urllib.parse import quote

import httpx
from temporalio import activity

from asterism_worker.activities.execution_support import release_repo, run_validation_commands, validate_patch_paths
from asterism_worker.config.settings import load_settings
from asterism_worker.contracts import GitlabPublishResult, MergeRequestRef, RepoSnapshot, ValidationResult
from asterism_worker.repo_source import (
    GitlabRepoSource,
    cleanup_repo_workspace,
    fetch_git_connection,
    git_credentials,
)

log = logging.getLogger(__name__)


@activity.defn
async def publish_merge_request(request: dict) -> dict:
    """在临时 clone 内校验、提交、推送并幂等创建 MR。"""

    settings = load_settings()
    repo = RepoSnapshot.model_validate(request["repo"])
    base_url, token = await fetch_git_connection(request["system_id"], settings)
    workspace = GitlabRepoSource(base_url, token).prepare(repo, settings.workspace_root)
    try:
        diff_patch = str(request.get("diff_patch", ""))
        gate = validate_patch_paths(diff_patch, repo.allowed_paths, repo.forbidden_paths)
        if gate.blocked:
            raise RuntimeError(gate.reason)
        subprocess.run(["git", "apply", "--check"], cwd=workspace, input=diff_patch,
                       text=True, check=True, capture_output=True)
        subprocess.run(["git", "apply"], cwd=workspace, input=diff_patch,
                       text=True, check=True, capture_output=True)
        validation = ValidationResult(passed=True)
        if request.get("validation_mode") == "auto":
            validation = run_validation_commands(
                str(workspace), repo.test_commands, settings.validation_timeout_seconds,
            )
        if not validation.passed:
            return GitlabPublishResult(repo=repo.repo_id, validation=validation).model_dump()

        release = release_repo(
            str(workspace), request["work_item_id"], request.get("title", ""), diff_patch,
        )
        clone_url = f"{base_url.rstrip('/')}/{repo.gitlab_project.strip('/')}.git"
        commit_hash = _push_branch(
            workspace, clone_url, token, release.branch, request.get("expected_remote_commit", ""),
        )
        merge_request = await _ensure_merge_request(
            base_url=base_url,
            token=token,
            project=repo.gitlab_project,
            source_branch=release.branch,
            target_branch=request.get("mr_target_branch") or repo.default_branch,
            title=f"{request.get('title') or request['work_item_id']} ({request['work_item_id']})",
            description=_description(request, settings.public_url or settings.control_plane_url),
            labels=request.get("mr_labels", []),
            repo_id=repo.repo_id,
            draft=request.get("validation_mode") == "manual",
        )
        log.info("GitLab MR 已准备 repo=%s iid=%s", repo.repo_id, merge_request.mr_iid)
        return GitlabPublishResult(
            repo=repo.repo_id,
            branch=release.branch,
            commit_hash=commit_hash,
            merge_request=merge_request,
            validation=validation,
        ).model_dump()
    finally:
        cleanup_repo_workspace(workspace)


@activity.defn
async def check_merge_requests(request: dict) -> list[dict]:
    settings = load_settings()
    base_url, token = await fetch_git_connection(request["system_id"], settings)
    repos = {repo.repo_id: repo for repo in map(RepoSnapshot.model_validate, request.get("repos", []))}
    result = []
    async with httpx.AsyncClient(timeout=20, headers={"PRIVATE-TOKEN": token}) as client:
        for item in request.get("merge_requests", []):
            current = MergeRequestRef.model_validate(item)
            repo = repos[current.repo]
            response = await client.get(_mr_url(base_url, repo.gitlab_project, current.mr_iid))
            response.raise_for_status()
            data = response.json()
            result.append(MergeRequestRef(
                repo=current.repo,
                mr_iid=current.mr_iid,
                mr_url=str(data.get("web_url") or current.mr_url),
                state=str(data.get("state", "opened")),
                project=repo.gitlab_project,
            ).model_dump())
    log.info("GitLab MR 状态已轮询 count=%s", len(result))
    return result


@activity.defn
async def ready_merge_requests(request: dict) -> list[dict]:
    settings = load_settings()
    base_url, token = await fetch_git_connection(request["system_id"], settings)
    repos = {repo.repo_id: repo for repo in map(RepoSnapshot.model_validate, request.get("repos", []))}
    result = []
    async with httpx.AsyncClient(timeout=20, headers={"PRIVATE-TOKEN": token}) as client:
        for value in request.get("merge_requests", []):
            current = MergeRequestRef.model_validate(value)
            repo = repos[current.repo]
            url = _mr_url(base_url, repo.gitlab_project, current.mr_iid)
            response = await client.get(url)
            response.raise_for_status()
            data = response.json()
            title = str(data.get("title", ""))
            if title.lower().startswith("draft:"):
                updated = await client.put(url, data={"title": title.split(":", 1)[1].strip()})
                updated.raise_for_status()
                data = updated.json()
            result.append(_mr_ref(repo.repo_id, repo.gitlab_project, data).model_dump())
    log.info("GitLab 候选 MR 已转为可合并 count=%s", len(result))
    return result


def _push_branch(workspace: Path, clone_url: str, token: str, branch: str,
                 expected_remote_commit: str = "") -> str:
    env = {**os.environ, "GIT_TERMINAL_PROMPT": "0"}
    with git_credentials(clone_url, token, workspace.parent) as auth:
        remote = subprocess.run(
            ["git", *auth, "ls-remote", "--heads", "origin", f"refs/heads/{branch}"],
            cwd=workspace, check=True, capture_output=True, text=True, env=env,
        ).stdout.strip()
        local_ref = f"refs/heads/{branch}"
        remote_ref = f"refs/heads/{branch}"
        local_hash = subprocess.run(["git", "rev-parse", local_ref], cwd=workspace,
                                    check=True, capture_output=True, text=True).stdout.strip()
        remote_hash = remote.split()[0] if remote else ""
        if remote_hash:
            subprocess.run(["git", *auth, "fetch", "--no-tags", "origin", remote_ref], cwd=workspace,
                           check=True, capture_output=True, text=True, env=env)
            local_tree = subprocess.run(["git", "rev-parse", f"{local_ref}^{{tree}}"], cwd=workspace,
                                        check=True, capture_output=True, text=True).stdout.strip()
            remote_tree = subprocess.run(["git", "rev-parse", "FETCH_HEAD^{tree}"], cwd=workspace,
                                         check=True, capture_output=True, text=True).stdout.strip()
            if local_tree == remote_tree:
                return remote_hash
            if not expected_remote_commit or remote_hash != expected_remote_commit:
                raise RuntimeError("远端工作项分支已被其他人修改")
        elif expected_remote_commit:
            raise RuntimeError("远端工作项分支已被删除")
        command = ["git", *auth, "push"]
        if remote_hash:
            command.append(f"--force-with-lease={remote_ref}:{remote_hash}")
        command.extend(["origin", f"{local_ref}:{remote_ref}"])
        subprocess.run(command, cwd=workspace, check=True, capture_output=True, text=True, env=env)
        return local_hash


async def _ensure_merge_request(base_url: str, token: str, project: str, source_branch: str,
                                target_branch: str, title: str, description: str,
                                labels: list[str], repo_id: str, draft: bool = False) -> MergeRequestRef:
    headers = {"PRIVATE-TOKEN": token}
    url = _mrs_url(base_url, project)
    params = {"state": "opened", "source_branch": source_branch, "target_branch": target_branch}
    async with httpx.AsyncClient(timeout=20, headers=headers) as client:
        existing = await client.get(url, params=params)
        existing.raise_for_status()
        values = existing.json()
        if values:
            return _mr_ref(repo_id, project, values[0])
        response = await client.post(url, data={
            "source_branch": source_branch,
            "target_branch": target_branch,
            "title": f"Draft: {title}" if draft else title,
            "description": description,
            "labels": ",".join(labels),
        })
        if response.status_code in {400, 409}:
            existing = await client.get(url, params=params)
            existing.raise_for_status()
            values = existing.json()
            if values:
                return _mr_ref(repo_id, project, values[0])
        response.raise_for_status()
        return _mr_ref(repo_id, project, response.json())


def _description(request: dict, public_url: str) -> str:
    criteria = "\n".join(f"- {item}" for item in request.get("acceptance_criteria", [])) or "- 未提供"
    link = public_url.rstrip("/") + f"/work-items/{request['work_item_id']}"
    return f"""## PRD Goal
{request.get('goal', '')}

## 验收标准
{criteria}

## Asterism 工作项
{link}
"""


def _mr_ref(repo_id: str, project: str, data: dict) -> MergeRequestRef:
    return MergeRequestRef(
        repo=repo_id,
        mr_iid=int(data["iid"]),
        mr_url=str(data.get("web_url", "")),
        state=str(data.get("state", "opened")),
        project=project,
    )


def _mrs_url(base_url: str, project: str) -> str:
    return f"{base_url.rstrip('/')}/api/v4/projects/{quote(project, safe='')}/merge_requests"


def _mr_url(base_url: str, project: str, iid: int) -> str:
    return f"{_mrs_url(base_url, project)}/{iid}"
