import shlex
import shutil
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath

from asterism_worker.contracts import PatchApplyResult, ReleaseResult, ValidationCommandResult, ValidationResult

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
        "user.name=asterism",
        "-c",
        "user.email=asterism@example.invalid",
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
