from asterism_worker.contracts import (
    CodingAttemptResult,
    ExecutionResult,
    RepoChangeResult,
)


def combine_patch_artifacts(patches: list[str]) -> str:
    """组合展示用 Patch；不清洗任何仓库 Patch 内部的空白字符。"""

    return "\n".join(patches)


def candidate_summary(candidates: list[dict]) -> list[dict]:
    """只向修订 Prompt 暴露候选摘要，完整 Patch 留在工作区恢复链。"""

    return [{
        "repo": item.get("repo", ""),
        "summary": item.get("summary", ""),
        "changedPaths": item.get("changed_paths", []),
    } for item in candidates]


def previous_candidates(
    completed_results: list[ExecutionResult],
    state_diff: str,
    default_repo: str,
    diff_paths,
) -> list[dict]:
    """统一提取成功候选或受阻 Attempt 留下的局部成果。"""

    if completed_results:
        return [
            {
                "repo": result.repo or default_repo,
                "diff_patch": result.diff_patch,
                "changed_paths": result.changed_paths or diff_paths(result.diff_patch),
                "summary": result.summary,
            }
            for result in completed_results
            if result.diff_patch.strip()
        ]
    if not state_diff.strip():
        return []
    return [{
        "repo": default_repo,
        "diff_patch": state_diff,
        "changed_paths": diff_paths(state_diff),
        "summary": "上一版候选",
    }]


def workflow_previous_candidates(owner) -> list[dict]:
    """从 Workflow 当前状态提取可恢复候选，避免编排文件堆积纯数据转换。"""

    return previous_candidates(
        owner.completed_stage_results,
        owner.state.diff_patch,
        owner._case_input().effective_repos()[0].repo_id,
        owner._diff_paths,
    )


def discard_candidate_checkpoint(owner) -> None:
    """完整重做只保留需求和人工意见，不复用旧候选代码。"""

    owner.completed_stage_results = []
    owner.state.diff_patch = ""


def attempt_stage_results(
    attempt: CodingAttemptResult, changes: list[RepoChangeResult],
) -> list[ExecutionResult]:
    """把真实仓库 Diff 转成 Workflow 可恢复的候选检查点。"""

    return [ExecutionResult(
        summary=change.summary or attempt.summary,
        diff_patch=change.diff_patch,
        execution_provider=attempt.execution_provider,
        engine="claude_sdk_team",
        repo=change.repo,
        changed_paths=change.changed_paths,
    ) for change in changes]


def combined_attempt_result(
    attempt: CodingAttemptResult, changes: list[RepoChangeResult],
) -> ExecutionResult:
    """供 ModificationCompleted 使用的跨仓顶层结果。"""

    return ExecutionResult(
        summary=attempt.summary,
        diff_patch=combine_patch_artifacts([change.diff_patch for change in changes]),
        execution_provider=attempt.execution_provider,
        engine="claude_sdk_team",
        turns=attempt.turns,
        token_usage=attempt.token_usage,
        changed_paths=sorted({path for change in changes for path in change.changed_paths}),
        session_id=attempt.session_id,
        subagent_runs=attempt.subagent_runs,
    )
