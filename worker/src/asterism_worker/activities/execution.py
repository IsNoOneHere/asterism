import asyncio
import logging
import subprocess
from pathlib import Path

from temporalio import activity

from asterism_worker.activities.execution_support import (
    _matches,
    changed_paths,
    collect_file_context,
    git_apply_check,
    patch_changes_already_present,
    release_repo,
    run_validation_commands,
    summarize_repo_path,
    validate_patch_paths,
    validate_plan_targets,
    validate_plan_targets_in_repositories,
)
from asterism_worker.agent_config import available_agent_metadata, resolve_agent_config
from asterism_worker.config.settings import load_settings
from asterism_worker.contracts import (
    CodingAttemptRequest,
    ExecutionRequest,
    PatchApplyRequest,
    PatchApplyResult,
    PlanRequest,
    PreviousAttempt,
    RepoSnapshot,
)
from asterism_worker.providers.factory import build_coding_team_provider, build_execution_provider, build_planner_provider
from asterism_worker.repo_source import (
    TeamWorkspace,
    cleanup_repo_workspace,
    prepare_repo_workspace,
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
        _apply_previous_candidate(parsed, workspace)

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
            role_id="developer",
            snapshot=parsed.agent_config_snapshot,
            callbacks={"event": heartbeat_provider_event},
        )
        provider = build_coding_team_provider(resolved)
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
        return result.model_dump()
    finally:
        cleanup_repo_workspace(workspace.root)


def _apply_previous_candidate(request: CodingAttemptRequest, workspace: TeamWorkspace) -> None:
    """把上一版完整候选恢复到新工作区，Agent 直接在其上做增删修订。"""

    repos = {repo.repo_id: repo for repo in request.repos}
    applied_repos: list[str] = []
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
        applied_repos.append(candidate.repo)
    if applied_repos:
        log.info(
            "上一版候选已恢复到 Coding 工作区",
            extra={"work_item_id": request.work_item_id, "repositories": applied_repos},
        )


@activity.defn
async def run_execution(request: dict) -> dict:
    """隔离工作区后执行 provider，避免直接污染真实仓库。"""

    settings = load_settings()
    parsed = ExecutionRequest.model_validate(request)
    legacy = parsed.model_extra or {}
    repo = parsed.repo or RepoSnapshot(
        repo_id="main",
        name="main",
        local_path=parsed.repo_path,
        allowed_paths=parsed.allowed_paths,
        forbidden_paths=parsed.forbidden_paths,
        test_commands=parsed.test_commands,
    )
    workspace = await prepare_repo_workspace(repo, parsed.system_id, settings)
    try:
        if parsed.repo is None:
            validate_plan_targets(str(workspace), parsed.plan.target_files)
        context = collect_file_context(workspace, parsed.plan.target_files)
        def heartbeat_provider_event(event: dict) -> None:
            # SDK 每返回一个消息就续租 activity；单测直接调用 activity 时没有上下文。
            try:
                activity.heartbeat({"work_item_id": parsed.work_item_id, "event_type": event.get("type", "")})
            except RuntimeError:
                pass

        resolved = await resolve_agent_config(
            settings,
            parsed.system_id,
            role_id=parsed.role_id,
            snapshot=parsed.agent_config_snapshot,
            legacy_engine=str(legacy.get("execution_provider", "")),
            legacy_max_turns=legacy.get("claude_max_turns"),
            legacy_timeout_seconds=legacy.get("execution_timeout_seconds"),
            callbacks={"event": heartbeat_provider_event},
        )
        provider = build_execution_provider(resolved)
        provider_name = resolved.engine.name
        agent_scope = list(resolved.constraints.path_scope)
        provider_allowed_paths = hard_allowed_paths(parsed.allowed_paths, agent_scope)
        log.info("执行 provider", extra={"provider": provider_name, "work_item_id": parsed.work_item_id,
                                       "role_id": resolved.constraints.role_id or parsed.role_id,
                                       "hard_path_scope": provider_allowed_paths,
                                       "planner_focus_paths": parsed.role_scope})
        provider_request = parsed.model_copy(update={
            "repo_path": str(workspace),
            "file_listing": context.file_listing,
            "file_contents": context.file_contents,
            "allowed_paths": provider_allowed_paths,
            "role_id": resolved.constraints.role_id or parsed.role_id,
            "role_name": resolved.constraints.role_name,
            "model_profile_id": resolved.model_profile.id,
            # Planner scope 只帮助 Agent 定位，不参与权限计算。
            "role_scope": parsed.role_scope,
            "role_prompt": resolved.constraints.prompt,
        })
        result = await asyncio.wait_for(provider.run(provider_request), timeout=resolved.engine.timeout_seconds)
        result = result.model_copy(update={"execution_provider": result.execution_provider or provider_name,
                                           "engine": provider_name,
                                           "role_id": provider_request.role_id,
                                           "repo": repo.repo_id})
        if not result.diff_patch.strip():
            return result.model_dump()
        apply_error = git_apply_check(str(workspace), result.diff_patch)
        if apply_error:
            log.info("diff apply-check 失败，回传 provider 重试", extra={"work_item_id": parsed.work_item_id})
            result = await asyncio.wait_for(provider.run(provider_request.model_copy(update={
                "previous_attempt": PreviousAttempt(diff=result.diff_patch, apply_error=apply_error),
            })), timeout=resolved.engine.timeout_seconds)
            result = result.model_copy(update={"execution_provider": result.execution_provider or provider_name,
                                               "engine": provider_name,
                                               "role_id": provider_request.role_id,
                                               "repo": repo.repo_id})
            apply_error = git_apply_check(str(workspace), result.diff_patch)
            if apply_error:
                raise RuntimeError(apply_error)
        paths = sorted(changed_paths(result.diff_patch))
        violation = next((path for path in paths if (provider_allowed_paths and not _matches(path, provider_allowed_paths))
                          or _matches(path, parsed.forbidden_paths)), "")
        if violation:
            log.warning("Agent 角色越出路径范围", extra={"work_item_id": parsed.work_item_id,
                                                        "role_id": provider_request.role_id,
                                                        "path": violation})
            result = result.model_copy(update={
                "blocked_reason": "role_scope_violation",
                "blocked_detail": violation,
            })
        return result.model_copy(update={"changed_paths": paths}).model_dump()
    finally:
        cleanup_repo_workspace(workspace)


@activity.defn
async def plan_execution(request: dict) -> dict:
    """调用 planner provider 生成执行计划，workflow 负责失败降级为 WorkerBlocked。"""

    settings = load_settings()
    parsed = PlanRequest.model_validate(request)
    provider = build_planner_provider(settings)
    snapshot_mode = parsed.agent_config_snapshot is not None or parsed.available_agents is not None
    if parsed.agent_config_snapshot is not None:
        agents = [
            {"name": agent.name, "engine": agent.engine, "path_scope": agent.path_scope}
            for agent in parsed.agent_config_snapshot.agents if agent.kind == "custom"
        ]
    elif parsed.available_agents is not None:
        agents = [agent.model_dump() for agent in parsed.available_agents]
    else:
        agents = await available_agent_metadata(settings, parsed.system_id)
    parsed = parsed.model_copy(update={"available_agents": agents})
    log.info("执行 planner", extra={"provider": settings.planner_provider, "manifest_id": parsed.context_manifest_id})
    plan = await provider.plan(parsed)
    # 无可选角色时清空模型自带的分配；有角色时只接受系统已发布的引用。
    if not agents:
        plan = plan.model_copy(update={"assignments": []})
    elif not snapshot_mode:
        agent_names = {agent["name"] for agent in agents}
        unknown_role = next((item.role for item in plan.assignments if item.role not in agent_names), "")
        if unknown_role:
            raise RuntimeError(f"Planner 返回了不可用的 Agent: {unknown_role}")
    return plan.model_dump()


@activity.defn
async def summarize_repo(request: dict) -> str:
    settings = load_settings()
    repos = [RepoSnapshot.model_validate(item) for item in request.get("repos", [])]
    if not repos:
        repos = [RepoSnapshot(repo_id="main", name="main", local_path=request.get("repo_path", ""))]
    sections: list[str] = []
    for repo in repos:
        temporary = repo.clone_mode == "gitlab"
        workspace = (await prepare_repo_workspace(repo, request.get("system_id", ""), settings)
                     if temporary else Path(repo.local_path))
        try:
            summary = summarize_repo_path(str(workspace))
            sections.append(summary if len(repos) == 1 else f"# repo {repo.repo_id} ({repo.kind})\n{summary}")
        finally:
            if temporary:
                cleanup_repo_workspace(workspace)
    result = "\n\n".join(sections)
    log.info("仓库摘要已生成", extra={"repo_count": len(repos), "summary_bytes": len(result.encode())})
    return result


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
async def validate_plan_targets_activity(request: dict) -> None:
    settings = load_settings()
    repos = [RepoSnapshot.model_validate(item) for item in request.get("repos", [])]
    if not repos:
        repos = [RepoSnapshot(repo_id="main", name="main", local_path=request.get("repo_path", ""))]
    repo_ids = [item.get("repo", "") for item in request.get("assignments", [])]
    assignment_scope_paths = [
        path
        for assignment in request.get("assignments", [])
        for path in assignment.get("scope_paths", [])
    ]
    selected = repos if not repo_ids else [repo for repo in repos if repo.repo_id in repo_ids or (not repo_ids[0] and len(repos) == 1)]
    prepared: list[tuple[Path, bool]] = []
    try:
        for repo in selected:
            temporary = repo.clone_mode == "gitlab"
            workspace = (await prepare_repo_workspace(repo, request.get("system_id", ""), settings)
                         if temporary else Path(repo.local_path))
            prepared.append((workspace, temporary))
        used_scope_fallback = validate_plan_targets_in_repositories(
            [str(workspace) for workspace, _ in prepared],
            request.get("target_files", []),
            assignment_scope_paths,
        )
        if used_scope_fallback:
            log.warning(
                "Planner target_files 无真实仓库锚点，使用 assignment scope_paths 继续",
                extra={"target_files": request.get("target_files", []),
                       "assignment_scope_paths": assignment_scope_paths},
            )
    finally:
        for workspace, temporary in prepared:
            if temporary:
                cleanup_repo_workspace(workspace)


def hard_allowed_paths(system_scope: list[str], agent_scope: list[str]) -> list[str]:
    """系统和 Agent 都配置范围时取交集，Planner assignment 不得参与硬权限。"""

    if not system_scope:
        return list(agent_scope)
    if not agent_scope:
        return list(system_scope)
    candidates = {
        item.strip("/") for item in [*system_scope, *agent_scope]
        if item.strip("/") and _matches(item, system_scope) and _matches(item, agent_scope)
    }
    if not candidates:
        raise RuntimeError("system allowed_paths and Agent pathScope do not overlap")
    return sorted(candidates)


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
