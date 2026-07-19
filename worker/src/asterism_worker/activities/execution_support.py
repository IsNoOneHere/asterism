import os
import re
import shlex
import subprocess
import tempfile
from collections import Counter
from dataclasses import dataclass
from pathlib import Path, PurePosixPath

from asterism_worker.contracts import PatchApplyResult, ReleaseResult, ValidationCommandResult, ValidationResult

IGNORED_SUMMARY_DIRS = {".git", ".idea", "node_modules", "target", ".venv", "dist", "build", "coverage"}
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


@dataclass(slots=True)
class DiffHunk:
    path: str
    old_start: int
    old_count: int
    additions: Counter[str]
    deletions: Counter[str]


def summarize_repo_path(repo_path: str, max_bytes: int = SUMMARY_MAX_BYTES) -> str:
    root = Path(repo_path).expanduser() if repo_path else None
    if not root or not root.exists() or not root.is_dir():
        return ""
    sections = ["# tree", *repo_tree(root), "", "# manifests", *manifest_heads(root)]
    return truncate_summary("\n".join(sections).strip(), max_bytes)


def repo_tree(root: Path, max_depth: int = 5) -> list[str]:
    tracked_files = git_tracked_files(root)
    if tracked_files is not None:
        # Git 仓库只向模型暴露版本库内容，目录按层级优先，避免深层构建产物挤掉源码入口。
        directories = {
            "/".join(Path(name).parts[:depth])
            for name in tracked_files
            for depth in range(1, min(len(Path(name).parts), max_depth + 1))
        }
        lines = [f"{name}/" for name in sorted(directories, key=lambda item: (item.count("/"), item.lower()))]
        lines.extend(sorted(
            (name for name in tracked_files if len(Path(name).parts) <= max_depth),
            key=lambda item: (item.count("/"), item.lower()),
        ))
        return lines

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
    tracked_files = git_tracked_files(root)
    candidates: list[str] = []
    for name in target_files:
        if not _is_safe_relative(name):
            continue
        path = root / name
        normalized = name.strip("/")
        if path.is_file() and (tracked_files is None or normalized in tracked_files):
            candidates.append(normalized)
        elif path.is_dir():
            prefix = normalized + "/"
            candidates.extend(
                item for item in (tracked_files or [])
                if item.startswith(prefix)
            )
    for name in dict.fromkeys(candidates):
        if len(contents) >= file_limit:
            break
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


def validate_plan_targets(repo_path: str, target_files: list[str]) -> bool:
    return validate_plan_targets_in_repositories([repo_path], target_files)


def validate_plan_targets_in_repositories(
    repo_paths: list[str], target_files: list[str], assignment_scope_paths: list[str] | None = None,
) -> bool:
    if any(not name.strip("/") or not _is_safe_relative(name) for name in target_files):
        raise RuntimeError("planner target_files contain unsafe path")
    roots = [Path(path) for path in repo_paths]
    tracked_by_root = [(root, git_tracked_files(root)) for root in roots]
    trusted_anchor = False
    untracked: list[str] = []
    for name in target_files:
        normalized = name.strip("/")
        existing = [(root, tracked) for root, tracked in tracked_by_root if (root / normalized).exists()]
        trusted = any(
            tracked is not None
            and (normalized in tracked or any(item.startswith(normalized + "/") for item in tracked))
            for _, tracked in tracked_by_root
        )
        if trusted:
            trusted_anchor = True
            continue
        ignored = any(
            tracked is not None and git_path_is_ignored(root, normalized)
            for root, tracked in tracked_by_root
        )
        if ignored:
            untracked.append(normalized)
            continue
        if not existing:
            # 不存在的目标可以是计划新增文件，但不能作为真实性锚点。
            continue
        if any(tracked is None for _, tracked in existing):
            trusted_anchor = True
        else:
            untracked.append(normalized)
    if untracked:
        raise RuntimeError(f"planner target_files are ignored or untracked: {', '.join(untracked)}")
    if trusted_anchor:
        return False
    if _has_trusted_anchor(tracked_by_root, assignment_scope_paths or []):
        # assignment scope 只证明 Planner 读到了真实仓库，不参与 Agent 写权限计算。
        return True
    raise RuntimeError("planner target_files do not exist in repository")


def _has_trusted_anchor(
    tracked_by_root: list[tuple[Path, set[str] | None]], paths: list[str],
) -> bool:
    for name in paths:
        normalized = name.strip("/")
        if not normalized or not _is_safe_relative(name):
            continue
        for root, tracked in tracked_by_root:
            if tracked is None:
                if (root / normalized).exists():
                    return True
            elif normalized in tracked or any(item.startswith(normalized + "/") for item in tracked):
                return True
    return False


def git_tracked_files(root: Path) -> set[str] | None:
    """返回仓库根目录的 tracked 文件；非 Git 目录返回 None 保留旧兼容路径。"""

    if not root.is_dir():
        return None
    top = subprocess.run(
        ["git", "rev-parse", "--show-toplevel"], cwd=root, text=True, capture_output=True,
    )
    if top.returncode != 0 or Path(top.stdout.strip()).resolve() != root.resolve():
        return None
    result = subprocess.run(["git", "ls-files", "-z"], cwd=root, capture_output=True)
    if result.returncode != 0:
        return None
    return {name for name in result.stdout.decode(errors="ignore").split("\0") if name}


def git_path_is_ignored(root: Path, path: str) -> bool:
    result = subprocess.run(
        ["git", "check-ignore", "--quiet", "--", path], cwd=root, capture_output=True,
    )
    return result.returncode == 0


def git_apply_check(repo_path: str, diff_patch: str) -> str:
    try:
        subprocess.run(["git", "apply", "--check"], cwd=repo_path, input=diff_patch,
                       text=True, check=True, capture_output=True)
        return ""
    except subprocess.CalledProcessError as error:
        return error.stderr.strip() or "git apply --check failed"


def patch_changes_already_present(repo_path: str, diff_patch: str) -> bool:
    """判断候选 Patch 的每个 hunk 是否已被真实工作区同区域的变更覆盖。"""

    expected = _diff_hunks(diff_patch)
    paths = sorted({hunk.path for hunk in expected})
    if not expected or not paths:
        return False
    current = subprocess.run(
        ["git", "diff", "--no-ext-diff", "--unified=0", "HEAD", "--", *paths],
        cwd=repo_path,
        text=True,
        capture_output=True,
    )
    if current.returncode != 0:
        return False
    actual_by_path: dict[str, list[DiffHunk]] = {}
    for hunk in _diff_hunks(current.stdout):
        actual_by_path.setdefault(hunk.path, []).append(hunk)
    return all(_hunk_changes_covered(hunk, actual_by_path.get(hunk.path, [])) for hunk in expected)


def _diff_hunks(diff_patch: str) -> list[DiffHunk]:
    """提取文本 Patch 的 hunk 位置和真实增删行；二进制或纯 mode 变更交回 Git 严格处理。"""

    header = re.compile(r"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@")
    path = ""
    current: DiffHunk | None = None
    hunks: list[DiffHunk] = []
    for line in diff_patch.splitlines():
        if line.startswith("diff --git "):
            parts = line.split()
            path = _strip_diff_prefix(parts[3]) if len(parts) >= 4 else ""
            current = None
            continue
        match = header.match(line)
        if match and path:
            current = DiffHunk(
                path=path,
                old_start=int(match.group(1)),
                old_count=int(match.group(2) or "1"),
                additions=Counter(),
                deletions=Counter(),
            )
            hunks.append(current)
            continue
        if current is None or line.startswith("\\ No newline at end of file"):
            continue
        if line.startswith("+"):
            current.additions[line[1:]] += 1
        elif line.startswith("-"):
            current.deletions[line[1:]] += 1
    return hunks


def _hunk_changes_covered(expected: DiffHunk, actual: list[DiffHunk]) -> bool:
    start = expected.old_start
    end = start + max(expected.old_count, 1)
    nearby = [
        hunk for hunk in actual
        if hunk.old_start <= end + 1 and hunk.old_start + max(hunk.old_count, 1) >= start - 1
    ]
    additions: Counter[str] = Counter()
    deletions: Counter[str] = Counter()
    for hunk in nearby:
        additions.update(hunk.additions)
        deletions.update(hunk.deletions)
    return expected.additions <= additions and expected.deletions <= deletions


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
    if not paths:
        raise RuntimeError("release diff is empty")
    ref = f"refs/heads/{branch}"
    previous = subprocess.run(["git", "rev-parse", "--verify", "--quiet", ref], cwd=repo_path,
                              capture_output=True, text=True).stdout.strip()
    head = subprocess.run(["git", "rev-parse", "HEAD"], cwd=repo_path,
                          check=True, capture_output=True, text=True).stdout.strip()
    # 临时 index 只应用本工作项 Patch，不切换真实仓库分支，也不带入用户同文件的其它改动。
    with tempfile.TemporaryDirectory(prefix="asterism-index-") as directory:
        env = {**os.environ, "GIT_INDEX_FILE": str(Path(directory) / "index")}
        subprocess.run(["git", "read-tree", head], cwd=repo_path, env=env,
                       check=True, capture_output=True, text=True)
        subprocess.run(["git", "apply", "--cached"], cwd=repo_path, env=env, input=diff_patch,
                       check=True, capture_output=True, text=True)
        tree = subprocess.run(["git", "write-tree"], cwd=repo_path, env=env,
                              check=True, capture_output=True, text=True).stdout.strip()
    if previous and subprocess.run(["git", "rev-parse", f"{previous}^{{tree}}"], cwd=repo_path,
                                   check=True, capture_output=True, text=True).stdout.strip() == tree:
        commit_hash = previous
    else:
        env = {**os.environ, "GIT_AUTHOR_NAME": "asterism", "GIT_AUTHOR_EMAIL": "asterism@example.invalid",
               "GIT_COMMITTER_NAME": "asterism", "GIT_COMMITTER_EMAIL": "asterism@example.invalid"}
        commit_hash = subprocess.run(["git", "commit-tree", tree, "-p", head, "-m", message], cwd=repo_path,
                                     env=env, check=True, capture_output=True, text=True).stdout.strip()
        update = ["git", "update-ref", ref, commit_hash]
        if previous:
            update.append(previous)
        subprocess.run(update, cwd=repo_path, check=True, capture_output=True, text=True)
    push_failed = ""
    if push:
        try:
            remote = subprocess.run(["git", "ls-remote", "--heads", "origin", ref], cwd=repo_path,
                                    check=True, capture_output=True, text=True).stdout.strip()
            remote_hash = remote.split()[0] if remote else ""
            if remote_hash and previous and remote_hash not in {previous, commit_hash}:
                raise RuntimeError("remote work-item branch changed")
            command = ["git", "push"]
            if remote_hash and remote_hash != commit_hash:
                command.append(f"--force-with-lease={ref}:{remote_hash}")
            command.extend(["origin", f"{ref}:{ref}"])
            subprocess.run(command, cwd=repo_path, check=True, capture_output=True, text=True)
        except subprocess.CalledProcessError as error:
            push_failed = error.stderr.strip() or "git push failed"
        except RuntimeError as error:
            push_failed = str(error)
    return ReleaseResult(branch=branch, commit_hash=commit_hash, push_failed=push_failed)


def tail(value: str) -> str:
    return value[-TAIL_CHARS:]


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
