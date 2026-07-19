import os
import re
import shlex
import subprocess
import tempfile
from collections import Counter
from dataclasses import dataclass
from pathlib import Path, PurePosixPath

from asterism_worker.contracts import PatchApplyResult, ReleaseResult, ValidationCommandResult, ValidationResult

TAIL_CHARS = 4000


@dataclass(slots=True)
class DiffHunk:
    path: str
    old_start: int
    old_count: int
    additions: Counter[str]
    deletions: Counter[str]


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
