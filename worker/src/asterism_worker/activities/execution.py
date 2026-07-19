import asyncio
import logging
import subprocess
from pathlib import Path

from temporalio import activity

from asterism_worker.activities.execution_support import (
    changed_paths,
    git_apply_check,
    patch_changes_already_present,
    release_repo,
    run_validation_commands,
    validate_patch_paths,
)
from asterism_worker.agent_config import resolve_agent_config
from asterism_worker.config.settings import load_settings
from asterism_worker.contracts import (
    CodingAttemptRequest,
    PatchApplyRequest,
    PatchApplyResult,
)
from asterism_worker.providers.factory import build_execution_provider
from asterism_worker.repo_source import (
    TeamWorkspace,
    cleanup_repo_workspace,
    prepare_team_workspace,
)

log = logging.getLogger(__name__)


@activity.defn
async def run_coding_attempt(request: dict) -> dict:
    """在单个团队工作区内运行 Claude SDK Supervisor，并按仓库收集候选 Diff。"""

    settings = load_settings()
    parsed = CodingAttemptRequest.model_validate(request)
    workspace = await prepare_team_workspace(parsed.repos, parsed.system_id, settings)
    try:
        parsed = _restore_revision_candidate(parsed, workspace)

        def heartbeat_provider_event(event: dict) -> None:
            try:
                activity.heartbeat({
                    "work_item_id": parsed.work_item_id,
                    "event_type": event.get("type", ""),
                    "agent": event.get("agent", ""),
                })
            except RuntimeError:
                pass

        resolved = await resolve_agent_config(
            settings,
            parsed.system_id,
            snapshot=parsed.agent_config_snapshot,
            callbacks={"event": heartbeat_provider_event},
        )
        provider = build_execution_provider(resolved)
        log.info(
            "启动 Claude SDK Coding Supervisor",
            extra={"work_item_id": parsed.work_item_id, "repo_count": len(parsed.repos)},
        )
        result = await asyncio.wait_for(
            provider.run(parsed, workspace),
            timeout=resolved.engine.timeout_seconds,
        )
        by_id = {repo.repo_id: repo for repo in parsed.repos}
        for change in result.repo_changes:
            if not change.diff_patch.strip():
                continue
            repo = by_id[change.repo]
            gate = validate_patch_paths(change.diff_patch, repo.allowed_paths, repo.forbidden_paths)
            if gate.blocked:
                raise RuntimeError(f"{change.repo}: {gate.reason}")
            apply_error = git_apply_check(str(workspace.repos[change.repo]), change.diff_patch)
            if apply_error:
                raise RuntimeError(f"{change.repo}: {apply_error}")
        if parsed.revision_context is not None:
            result = result.model_copy(update={"revision_mode": parsed.revision_context.revision_mode})
        return result.model_dump()
    finally:
        cleanup_repo_workspace(workspace.root)


def _apply_previous_candidate(request: CodingAttemptRequest, workspace: TeamWorkspace) -> None:
    """把上一版完整候选恢复到新工作区，Agent 直接在其上做增删修订。"""

    repos = {repo.repo_id: repo for repo in request.repos}
    applied_repos: list[str] = []
    applied: list[Path] = []
    try:
        for candidate in request.previous_candidate:
            if not candidate.diff_patch.strip():
                continue
            repo = repos.get(candidate.repo)
            repo_path = workspace.repos.get(candidate.repo)
            if repo is None or repo_path is None:
                raise RuntimeError(f"上一版候选引用未知仓库: {candidate.repo}")
            gate = validate_patch_paths(candidate.diff_patch, repo.allowed_paths, repo.forbidden_paths)
            if gate.blocked:
                raise RuntimeError(f"{candidate.repo}: 上一版候选不符合路径约束: {gate.reason}")
            apply_error = git_apply_check(str(repo_path), candidate.diff_patch)
            if apply_error:
                raise RuntimeError(f"{candidate.repo}: 上一版候选无法应用: {apply_error}")
            subprocess.run(
                ["git", "apply"], cwd=repo_path, input=candidate.diff_patch,
                text=True, check=True, capture_output=True,
            )
            applied.append(repo_path)
            applied_repos.append(candidate.repo)
    except Exception:
        # 工作区是 Activity 临时 clone；直接回到 HEAD 比反向套用多份 Patch 更稳定。
        for repo_path in set(applied):
            subprocess.run(["git", "reset", "--hard", "HEAD"], cwd=repo_path, check=True, capture_output=True)
            subprocess.run(["git", "clean", "-fd"], cwd=repo_path, check=True, capture_output=True)
        raise
    if applied_repos:
        log.info(
            "上一版候选已恢复到 Coding 工作区",
            extra={"work_item_id": request.work_item_id, "repositories": applied_repos},
        )


def _restore_revision_candidate(
    request: CodingAttemptRequest, workspace: TeamWorkspace,
) -> CodingAttemptRequest:
    """候选无法恢复时，仅人工修订轮降级为带意见的全量执行。"""

    revision = request.revision_context
    if revision is None:
        _apply_previous_candidate(request, workspace)
        return request
    if not request.previous_candidate:
        return request.model_copy(update={
            "revision_context": revision.model_copy(update={"revision_mode": "full"}),
        })
    try:
        _apply_previous_candidate(request, workspace)
        return request
    except (RuntimeError, subprocess.SubprocessError) as error:
        log.warning(
            "修订候选恢复失败，降级为全量执行",
            extra={"work_item_id": request.work_item_id, "revision": revision.revision, "reason": str(error)},
        )
        return request.model_copy(update={
            "previous_candidate": [],
            "revision_context": revision.model_copy(update={"revision_mode": "full"}),
        })


@activity.defn
async def apply_patch_to_repo(request: dict) -> dict:
    """真实应用 patch，先做路径门禁，再执行 git apply。"""

    parsed = PatchApplyRequest.model_validate(request)
    gate = validate_patch_paths(parsed.diff_patch, parsed.allowed_paths, parsed.forbidden_paths)
    if gate.blocked:
        return gate.model_dump()
    check = subprocess.run(["git", "apply", "--check"], cwd=parsed.repo_path, input=parsed.diff_patch,
                           text=True, capture_output=True)
    if check.returncode == 0:
        subprocess.run(["git", "apply"], cwd=parsed.repo_path, input=parsed.diff_patch,
                       text=True, check=True, capture_output=True)
    else:
        # Activity 重试时 Patch 可能已成功应用；反向检查通过即复用真实结果。
        reverse = subprocess.run(["git", "apply", "-R", "--check"], cwd=parsed.repo_path,
                                 input=parsed.diff_patch, text=True, capture_output=True)
        if reverse.returncode != 0:
            # 真实工作区可能已包含候选改动并叠加了人工修改，只在同区域增删内容完整覆盖时复用。
            if patch_changes_already_present(parsed.repo_path, parsed.diff_patch):
                log.info("Patch 变更已被真实工作区覆盖", extra={"path_count": len(changed_paths(parsed.diff_patch))})
                return PatchApplyResult(already_applied=True).model_dump()
            return PatchApplyResult(blocked=True, reason=check.stderr.strip() or "git apply failed").model_dump()
        return PatchApplyResult(already_applied=True).model_dump()
    return PatchApplyResult().model_dump()


@activity.defn
async def run_release(request: dict) -> dict:
    result = release_repo(
        request.get("repo_path", ""),
        request.get("work_item_id", ""),
        request.get("title", ""),
        request.get("diff_patch", ""),
        push=load_settings().release_push,
    )
    log.info("release commit 已完成", extra={"branch": result.branch, "commit_hash": result.commit_hash})
    return result.model_dump()


@activity.defn
async def revert_patch(request: dict) -> dict:
    paths = changed_paths(request.get("diff_patch", ""))
    failed = ""
    already_reverted = False
    if paths:
        repo_path = request.get("repo_path", "")
        diff_patch = request.get("diff_patch", "")
        reverse = subprocess.run(["git", "apply", "-R", "--check"], cwd=repo_path, input=diff_patch,
                                 text=True, capture_output=True)
        if reverse.returncode == 0:
            subprocess.run(["git", "apply", "-R"], cwd=repo_path, input=diff_patch,
                           text=True, check=True, capture_output=True)
        else:
            forward = subprocess.run(["git", "apply", "--check"], cwd=repo_path, input=diff_patch,
                                     text=True, capture_output=True)
            already_reverted = forward.returncode == 0
            if not already_reverted:
                failed = reverse.stderr.strip() or "reverse patch conflict"
                log.warning("取消/驳回时回滚 patch 失败", extra={"reason": failed})
    return {"changed_paths": sorted(paths), "failed": failed, "already_reverted": already_reverted}


@activity.defn
async def run_validation(request: dict) -> dict:
    settings = load_settings()
    result = run_validation_commands(
        repo_path=request.get("repo_path", ""),
        test_commands=request.get("test_commands", []),
        timeout_seconds=settings.validation_timeout_seconds,
    )
    log.info("验证命令已完成", extra={"passed": result.passed, "command_count": len(result.commands)})
    return result.model_dump()
