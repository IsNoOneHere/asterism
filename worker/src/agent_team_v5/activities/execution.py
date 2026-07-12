import asyncio
import logging
import shlex
import shutil
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath

from temporalio import activity

from agent_team_v5.agent_config import available_role_metadata, resolve_agent_config
from agent_team_v5.config.settings import load_settings
from agent_team_v5.contracts import ExecutionRequest, PatchApplyRequest, PatchApplyResult, PlanRequest, PreviousAttempt, ReleaseResult, ValidationCommandResult, ValidationResult
from agent_team_v5.providers.factory import build_execution_provider, build_planner_provider

log = logging.getLogger(__name__)
IGNORED_SUMMARY_DIRS = {".git", "node_modules", "target", ".venv"}
SUMMARY_FILES = ("README.md", "pyproject.toml", "pom.xml", "package.json")
SUMMARY_MAX_BYTES = 8192
TAIL_CHARS = 4000
FILE_CONTEXT_LIMIT = 8
FILE_CONTEXT_BYTES = 16_000
FILE_CONTEXT_TOTAL_BYTES = 64_000


@dataclass(slots=True)
class FileContext:
    file_listing: str
    file_contents: dict[str, str]


@activity.defn
async def run_execution(request: dict) -> dict:
    """隔离工作区后执行 provider，避免直接污染真实仓库。"""

    settings = load_settings()
    parsed = ExecutionRequest.model_validate(request)
    workspace = prepare_workspace(parsed.repo_path, settings.workspace_root)
    try:
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
            legacy_engine=parsed.execution_provider,
            legacy_max_turns=parsed.claude_max_turns,
            callbacks={"event": heartbeat_provider_event},
        )
        provider = build_execution_provider(resolved)
        provider_name = resolved.engine.name
        effective_scope = parsed.role_scope or list(resolved.constraints.path_scope)
        provider_allowed_paths = effective_scope or parsed.allowed_paths
        log.info("执行 provider", extra={"provider": provider_name, "work_item_id": parsed.work_item_id,
                                       "role_id": resolved.constraints.role_id or parsed.role_id})
        provider_request = parsed.model_copy(update={
            "repo_path": str(workspace),
            "file_listing": context.file_listing,
            "file_contents": context.file_contents,
            "allowed_paths": provider_allowed_paths,
            "role_id": resolved.constraints.role_id or parsed.role_id,
            "role_name": resolved.constraints.role_name,
            "model_profile_id": resolved.model_profile.id,
            "role_scope": effective_scope,
            "role_prompt": resolved.constraints.prompt,
        })
        result = await asyncio.wait_for(provider.run(provider_request), timeout=resolved.engine.timeout_seconds)
        result = result.model_copy(update={"execution_provider": result.execution_provider or provider_name,
                                           "engine": provider_name,
                                           "role_id": provider_request.role_id})
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
                                               "role_id": provider_request.role_id})
            apply_error = git_apply_check(str(workspace), result.diff_patch)
            if apply_error:
                raise RuntimeError(apply_error)
        paths = sorted(changed_paths(result.diff_patch))
        role_scope = list(resolved.constraints.path_scope)
        assignment_scope = parsed.role_scope
        violation = next((path for path in paths if (role_scope and not _matches(path, role_scope))
                          or (assignment_scope and not _matches(path, assignment_scope))), "")
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
        cleanup_workspace(workspace)


@activity.defn
async def plan_execution(request: dict) -> dict:
    """调用 planner provider 生成执行计划，workflow 负责失败降级为 WorkerBlocked。"""

    settings = load_settings()
    parsed = PlanRequest.model_validate(request)
    provider = build_planner_provider(settings)
    roles = await available_role_metadata(settings, parsed.system_id)
    parsed = parsed.model_copy(update={"available_roles": roles})
    log.info("执行 planner", extra={"provider": settings.planner_provider, "manifest_id": parsed.context_manifest_id})
    plan = await provider.plan(parsed)
    return plan.model_dump()


@activity.defn
async def summarize_repo(request: dict) -> str:
    summary = summarize_repo_path(request.get("repo_path", ""))
    log.info("仓库摘要已生成", extra={"repo_path": request.get("repo_path"), "summary_bytes": len(summary.encode())})
    return summary


@activity.defn
async def apply_patch_to_repo(request: dict) -> dict:
    """真实应用 patch，先做路径门禁，再执行 git apply。"""

    parsed = PatchApplyRequest.model_validate(request)
    gate = validate_patch_paths(parsed.diff_patch, parsed.allowed_paths, parsed.forbidden_paths)
    if gate.blocked:
        return gate.model_dump()
    try:
        subprocess.run(["git", "apply", "--check"], cwd=parsed.repo_path, input=parsed.diff_patch,
                       text=True, check=True, capture_output=True)
        subprocess.run(["git", "apply"], cwd=parsed.repo_path, input=parsed.diff_patch,
                       text=True, check=True, capture_output=True)
    except subprocess.CalledProcessError as error:
        return PatchApplyResult(blocked=True, reason=error.stderr.strip() or "git apply failed").model_dump()
    return PatchApplyResult().model_dump()


@activity.defn
async def validate_plan_targets_activity(request: dict) -> None:
    validate_plan_targets(request.get("repo_path", ""), request.get("target_files", []))


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
    if paths:
        try:
            subprocess.run(["git", "checkout", "--", *sorted(paths)], cwd=request.get("repo_path", ""),
                           check=True, capture_output=True, text=True)
        except subprocess.CalledProcessError as error:
            failed = error.stderr.strip() or "git checkout failed"
            log.warning("取消/驳回时回滚 patch 失败", extra={"reason": failed})
    return {"changed_paths": sorted(paths), "failed": failed}


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


def summarize_repo_path(repo_path: str, max_bytes: int = SUMMARY_MAX_BYTES) -> str:
    root = Path(repo_path).expanduser() if repo_path else None
    if not root or not root.exists() or not root.is_dir():
        return ""
    sections = ["# tree", *repo_tree(root), "", "# manifests", *manifest_heads(root)]
    return truncate_summary("\n".join(sections).strip(), max_bytes)


def repo_tree(root: Path, max_depth: int = 3) -> list[str]:
    lines: list[str] = []

    def walk(path: Path, depth: int) -> None:
        if depth > max_depth:
            return
        for child in sorted(path.iterdir(), key=lambda item: (not item.is_dir(), item.name.lower())):
            if child.name in IGNORED_SUMMARY_DIRS:
                continue
            rel = child.relative_to(root).as_posix()
            suffix = "/" if child.is_dir() else ""
            lines.append(f"{rel}{suffix}")
            if child.is_dir():
                walk(child, depth + 1)

    walk(root, 1)
    return lines


def collect_file_context(
    root: Path,
    target_files: list[str],
    file_limit: int = FILE_CONTEXT_LIMIT,
    per_file_bytes: int = FILE_CONTEXT_BYTES,
    total_bytes: int = FILE_CONTEXT_TOTAL_BYTES,
) -> FileContext:
    contents: dict[str, str] = {}
    used = 0
    for name in target_files[:file_limit]:
        if not _is_safe_relative(name):
            continue
        path = root / name
        if not path.is_file():
            continue
        data = path.read_bytes()
        room = max(0, total_bytes - used)
        keep = min(len(data), per_file_bytes, room)
        text = data[:keep].decode(errors="ignore")
        if keep < len(data):
            text += "\n[truncated]\n"
        contents[name] = text
        used += len(text.encode())
        if used >= total_bytes:
            break
    return FileContext(file_listing="\n".join(repo_tree(root)), file_contents=contents)


def validate_plan_targets(repo_path: str, target_files: list[str]) -> None:
    if any(not _is_safe_relative(name) for name in target_files):
        raise RuntimeError("planner target_files contain unsafe path")
    root = Path(repo_path)
    # 至少锚定一个真实文件；其余目标仍可包含本次计划要新增的文件。
    if not any((root / name).is_file() for name in target_files):
        raise RuntimeError("planner target_files do not exist in repository")


def git_apply_check(repo_path: str, diff_patch: str) -> str:
    try:
        subprocess.run(["git", "apply", "--check"], cwd=repo_path, input=diff_patch,
                       text=True, check=True, capture_output=True)
        return ""
    except subprocess.CalledProcessError as error:
        return error.stderr.strip() or "git apply --check failed"


def manifest_heads(root: Path) -> list[str]:
    lines: list[str] = []
    for name in SUMMARY_FILES:
        path = root / name
        if not path.is_file():
            continue
        lines.append(f"## {name}")
        lines.extend(path.read_text(errors="ignore").splitlines()[:30])
    return lines


def truncate_summary(summary: str, max_bytes: int) -> str:
    data = summary.encode()
    if len(data) <= max_bytes:
        return summary
    marker = "\n[truncated]\n"
    keep = max(0, max_bytes - len(marker.encode()))
    return data[:keep].decode(errors="ignore") + marker


def run_validation_commands(repo_path: str, test_commands: list[str], timeout_seconds: int) -> ValidationResult:
    commands: list[ValidationCommandResult] = []
    for command in test_commands:
        try:
            completed = subprocess.run(
                shlex.split(command),
                cwd=repo_path,
                text=True,
                capture_output=True,
                timeout=timeout_seconds,
            )
            exit_code = completed.returncode
            stdout = completed.stdout
            stderr = completed.stderr
        except subprocess.TimeoutExpired as error:
            exit_code = 124
            stdout = error.stdout or ""
            stderr = error.stderr or f"timeout after {timeout_seconds}s"
        except OSError as error:
            exit_code = 127
            stdout = ""
            stderr = str(error)
        item = ValidationCommandResult(
            command=command,
            exit_code=exit_code,
            stdout_tail=tail(stdout),
            stderr_tail=tail(stderr),
        )
        commands.append(item)
        if exit_code != 0:
            return ValidationResult(passed=False, commands=commands, failed_command=command, stderr_tail=item.stderr_tail)
    return ValidationResult(passed=True, commands=commands)


def release_repo(repo_path: str, work_item_id: str, title: str, diff_patch: str, push: bool = False) -> ReleaseResult:
    branch = f"wi/{work_item_id}"
    message = f"{title or 'work item'} ({work_item_id})"
    paths = sorted(changed_paths(diff_patch))
    subprocess.run(["git", "checkout", "-B", branch], cwd=repo_path, check=True, capture_output=True, text=True)
    # 只提交本工作项 diff 涉及的文件，保留仓库中用户已有的修改。
    subprocess.run(["git", "add", "--", *paths], cwd=repo_path, check=True, capture_output=True, text=True)
    subprocess.run([
        "git",
        "-c",
        "user.name=agent-team",
        "-c",
        "user.email=agent-team@example.invalid",
        "commit",
        "-m",
        message,
        "--",
        *paths,
    ], cwd=repo_path, check=True, capture_output=True, text=True)
    commit_hash = subprocess.run(["git", "rev-parse", "HEAD"], cwd=repo_path, check=True, capture_output=True, text=True).stdout.strip()
    push_failed = ""
    if push:
        try:
            subprocess.run(["git", "push", "-u", "origin", branch], cwd=repo_path, check=True, capture_output=True, text=True)
        except subprocess.CalledProcessError as error:
            push_failed = error.stderr.strip() or "git push failed"
    return ReleaseResult(branch=branch, commit_hash=commit_hash, push_failed=push_failed)


def tail(value: str) -> str:
    return value[-TAIL_CHARS:]


def prepare_workspace(repo_path: str, workspace_root: str) -> Path:
    """把仓库复制到临时工作区；repo 不存在时保留空目录给 fake/http 使用。"""

    root = Path(workspace_root)
    root.mkdir(parents=True, exist_ok=True)
    workspace = Path(tempfile.mkdtemp(prefix="case-", dir=root))
    source = Path(repo_path).expanduser() if repo_path else None
    if not source or not source.exists():
        return workspace
    target = workspace / "repo"
    if (source / ".git").exists():
        subprocess.run(["git", "clone", "--quiet", str(source), str(target)], check=True)
    else:
        shutil.copytree(source, target)
    return target


def cleanup_workspace(workspace: Path) -> None:
    """run_execution 完成后清理临时目录，diff 已经保存在 workflow state。"""

    root = workspace.parent if workspace.name == "repo" and workspace.parent.name.startswith("case-") else workspace
    shutil.rmtree(root, ignore_errors=True)


def validate_patch_paths(diff_patch: str, allowed_paths: list[str], forbidden_paths: list[str]) -> PatchApplyResult:
    """只解析 diff --git 头部，门禁关注最终文件路径。"""

    paths = changed_paths(diff_patch)
    if not paths:
        return PatchApplyResult(blocked=True, reason="empty diff")
    for path in paths:
        if not _is_safe_relative(path):
            return PatchApplyResult(blocked=True, reason=f"unsafe path: {path}")
        if _matches(path, forbidden_paths):
            return PatchApplyResult(blocked=True, reason=f"forbidden path: {path}")
        if allowed_paths and not _matches(path, allowed_paths):
            return PatchApplyResult(blocked=True, reason=f"outside allowed paths: {path}")
    return PatchApplyResult()


def changed_paths(diff_patch: str) -> set[str]:
    paths: set[str] = set()
    for line in diff_patch.splitlines():
        if not line.startswith("diff --git "):
            continue
        parts = line.split()
        if len(parts) >= 4:
            paths.add(_strip_diff_prefix(parts[3]))
    return paths


def _strip_diff_prefix(path: str) -> str:
    return path[2:] if path.startswith("b/") else path


def _is_safe_relative(path: str) -> bool:
    pure = PurePosixPath(path)
    return not pure.is_absolute() and ".." not in pure.parts


def _matches(path: str, prefixes: list[str]) -> bool:
    normalized = path.strip("/")
    for prefix in prefixes:
        clean = prefix.strip("/")
        if clean and (normalized == clean or normalized.startswith(clean + "/")):
            return True
    return False
