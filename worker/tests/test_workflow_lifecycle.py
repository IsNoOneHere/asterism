import asyncio
from uuid import uuid4

import pytest
from temporalio import activity
from temporalio.client import WorkflowFailureError
from temporalio.contrib.pydantic import pydantic_data_converter
from temporalio.testing import WorkflowEnvironment
from temporalio.worker import Worker

from asterism_worker.contracts import CaseInput, PrdSpec
from asterism_worker.workflows.lifecycle import AsterismCaseWorkflow


TASK_QUEUE = "asterism-test"


def test_terminal_workflow_runs_local_lifecycle_without_legacy_activities():
    events, result, calls, _ = asyncio.run(_run_workflow([
        ("owner_approved", "approve-1"),
        ("start_modification", "start-1"),
        ("patch_apply_approved", "patch-1"),
        ("release_approved", "release-1"),
    ]))

    assert result == "completed"
    assert _business_types(events) == [
        "WorkItemActivated", "CodingAttemptStarted", "AgentStageCompleted", "ModificationCompleted",
        "PatchApplied", "ValidationPassed", "RepositoryReleasePrepared", "ReleaseCompleted",
    ]
    assert calls == {"fetch_context": 1, "run_coding_attempt": 1, "apply_patch_to_repo": 1,
                     "run_validation": 1, "run_release": 1}


def test_terminal_workflow_rejects_removed_architecture():
    with pytest.raises(WorkflowFailureError) as failure:
        asyncio.run(_run_workflow([], execution_architecture="legacy_planner_v1"))
    assert "claude_sdk_team" in str(failure.value.__cause__)


def test_coding_retry_reuses_context_and_previous_candidate():
    requests: list[dict] = []
    events, result, calls, requests = asyncio.run(_run_workflow([
        ("owner_approved", "approve-1"),
        ("start_modification", "start-1"),
        ("retry_current_phase", {"signal_id": "retry-1", "note": "只修复登录提示"}),
        ("patch_apply_approved", "patch-1"),
        ("release_approved", "release-1"),
    ], coding_failures=1, coding_requests=requests))

    assert result == "completed"
    assert calls["fetch_context"] == 1
    assert calls["run_coding_attempt"] == 2
    assert requests[1]["feedback"] == "重试补充：只修复登录提示"
    assert _business_types(events).count("CodingAttemptStarted") == 2


def test_patch_rejection_automatically_runs_incremental_revision_with_feedback():
    requests: list[dict] = []
    events, result, calls, requests = asyncio.run(_run_workflow([
        ("owner_approved", "approve-1"),
        ("start_modification", "start-1"),
        ("patch_apply_rejected", {
            "signal_id": "reject-1", "note": "错误提示需要放在输入框下方", "actor_id": "owner-1",
        }),
        ("patch_apply_approved", "patch-2"),
        ("release_approved", "release-1"),
    ], coding_requests=requests))

    assert result == "completed"
    assert calls["run_coding_attempt"] == 2
    assert calls["fetch_context"] == 1
    assert requests[1]["revision_context"] == {
        "revision": 1,
        "revision_mode": "incremental",
        "feedback": "人工审核反馈：错误提示需要放在输入框下方",
        "previous_diff_summary": [{
            "repo": "main", "summary": "仓库修改完成", "changedPaths": ["src/app.py"],
        }],
    }
    assert _business_types(events)[4:9] == [
        "PatchRejected", "ReworkStarted", "RevisionRequested", "CodingAttemptStarted", "AgentStageCompleted",
    ]
    requested = next(event for event in events if event["eventType"] == "RevisionRequested")["payload"]
    assert requested == {
        "note": "错误提示需要放在输入框下方",
        "revision": 1,
        "requestedBy": "owner-1",
        "phase": "review",
        "revisionMode": "incremental",
        "diffSummary": [{"repo": "main", "summary": "仓库修改完成", "changedPaths": ["src/app.py"]}],
    }
    revised = [event for event in events if event["eventType"] == "ModificationCompleted"][-1]["payload"]
    assert revised["revision"] == 1
    assert revised["revisionMode"] == "incremental"


def test_revision_candidate_restore_can_report_full_fallback():
    events, result, _, _ = asyncio.run(_run_workflow([
        ("owner_approved", "approve-1"),
        ("start_modification", "start-1"),
        ("patch_apply_rejected", {"signal_id": "reject-1", "note": "只修复提示位置"}),
        ("patch_apply_approved", "patch-2"),
        ("release_approved", "release-1"),
    ], revision_mode_overrides=["full"]))

    assert result == "completed"
    revised = [event for event in events if event["eventType"] == "ModificationCompleted"][-1]["payload"]
    assert revised["revision"] == 1
    assert revised["revisionMode"] == "full"


def test_revision_limit_blocks_until_owner_cancels_or_full_reworks():
    events, result, calls, _ = asyncio.run(_run_workflow([
        ("owner_approved", "approve-1"),
        ("start_modification", "start-1"),
        ("patch_apply_rejected", {"signal_id": "reject-1", "note": "第一轮意见"}),
        ("patch_apply_rejected", {"signal_id": "reject-2", "note": "第二轮意见"}),
        ("cancel_case", "cancel-1"),
    ], max_revisions=1))

    assert result == "cancelled"
    assert calls["run_coding_attempt"] == 2
    blocked = next(event for event in events if event["eventType"] == "WorkerBlocked")
    assert blocked["payload"] == {
        "reason": "revision_limit_reached",
        "detail": "已达到最大修订轮次 1",
        "revision": 1,
        "revisionMode": "incremental",
        "maxRevisions": 1,
        "phase": "review",
        "note": "第二轮意见",
    }


def test_full_rework_after_revision_limit_resets_revision_counter():
    events, result, calls, _ = asyncio.run(_run_workflow([
        ("owner_approved", "approve-1"),
        ("start_modification", "start-1"),
        ("patch_apply_rejected", {"signal_id": "reject-1", "note": "第一轮意见"}),
        ("patch_apply_rejected", {"signal_id": "reject-2", "note": "超出上限"}),
        ("rework", {"signal_id": "full-1", "note": "放弃候选并完整重做"}),
        ("patch_apply_approved", "patch-3"),
        ("release_approved", "release-1"),
    ], max_revisions=1))

    assert result == "completed"
    assert calls["run_coding_attempt"] == 3
    modifications = [event for event in events if event["eventType"] == "ModificationCompleted"]
    assert modifications[-1]["payload"]["revision"] == 0
    assert modifications[-1]["payload"]["revisionMode"] == "full"
    assert [event["payload"]["revision"] for event in events if event["eventType"] == "RevisionRequested"] == [1]


def test_patch_rejection_without_note_is_not_accepted():
    events, result, _, _ = asyncio.run(_run_workflow([
        ("owner_approved", "approve-1"),
        ("start_modification", "start-1"),
        ("patch_apply_rejected", {"signal_id": "reject-1", "note": "   "}),
        ("cancel_case", "cancel-1"),
    ]))

    assert result == "cancelled"
    assert "RevisionRequested" not in _business_types(events)
    rejected_action = next(
        event for event in events
        if event["eventType"] == "TemporalActionCompleted"
        and event["payload"]["action"] == "patch_apply_rejected"
    )
    assert rejected_action["payload"]["accepted"] is False


def test_waiting_merge_rework_revises_and_pushes_the_same_branch():
    publish_requests: list[dict] = []
    events, result, calls, _ = asyncio.run(_run_workflow([
        ("owner_approved", "approve-1"),
        ("start_modification", "start-1"),
        ("patch_apply_approved", "patch-1"),
        ("rework", {"signal_id": "review-1", "note": "MR 中的接口字段需要保持兼容", "actor_id": "owner-1"}),
        ("patch_apply_approved", "patch-2"),
    ], release_mode="gitlab", publish_requests=publish_requests))

    assert result == "completed"
    assert calls["publish_merge_request"] == 2
    assert publish_requests[0]["expected_remote_commit"] == ""
    assert publish_requests[1]["expected_remote_commit"] == "commit-1"
    assert {request["work_item_id"] for request in publish_requests} == {"wi-1"}
    revision = next(event for event in events if event["eventType"] == "RevisionRequested")["payload"]
    assert revision["phase"] == "merge"
    assert revision["note"] == "MR 中的接口字段需要保持兼容"


def test_duplicate_signal_id_is_processed_once():
    events, result, calls, _ = asyncio.run(_run_workflow([
        ("owner_approved", {"signal_id": "same"}),
        ("owner_approved", {"signal_id": "same"}),
        ("cancel_case", "cancel-1"),
    ]))

    assert result == "cancelled"
    assert _business_types(events) == ["WorkItemActivated", "CaseCancelled"]
    assert calls == {}


async def _run_workflow(
    signals: list[tuple[str, str | dict]],
    *,
    execution_architecture: str = "claude_sdk_team",
    coding_failures: int = 0,
    coding_requests: list[dict] | None = None,
    revision_mode_overrides: list[str] | None = None,
    max_revisions: int = 5,
    release_mode: str = "local",
    publish_requests: list[dict] | None = None,
) -> tuple[list[dict], str, dict[str, int], list[dict]]:
    events: list[dict] = []
    calls: dict[str, int] = {}
    requests = coding_requests if coding_requests is not None else []
    remaining_coding_failures = coding_failures
    revision_modes = list(revision_mode_overrides or [])
    gitlab_requests = publish_requests if publish_requests is not None else []

    def called(name: str) -> None:
        calls[name] = calls.get(name, 0) + 1

    @activity.defn(name="fetch_context")
    async def fetch_context(request: dict) -> dict:
        called("fetch_context")
        return {
            "system_id": request["system_id"], "manifest_id": "manifest-1",
            "approved_memories": [{"content": "遵守登录页约束"}],
        }

    @activity.defn(name="run_coding_attempt")
    async def run_coding_attempt(request: dict) -> dict:
        nonlocal remaining_coding_failures
        called("run_coding_attempt")
        requests.append(request)
        if remaining_coding_failures:
            remaining_coding_failures -= 1
            raise RuntimeError("coding unavailable")
        result = {
            "summary": "Supervisor 完成",
            "repo_changes": [{
                "repo": "main", "summary": "仓库修改完成", "changed_paths": ["src/app.py"],
                "diff_patch": "diff --git a/src/app.py b/src/app.py\n--- a/src/app.py\n+++ b/src/app.py\n@@ -1 +1 @@\n-old\n+new\n",
            }],
            "subagent_runs": [{
                "agent_id": "agent-main", "agent_type": "repo-main", "repo": "main", "status": "completed",
            }],
            "token_usage": {"input_tokens": 100}, "session_id": "session-1", "turns": 3,
            "execution_provider": "claude_sdk_team",
        }
        if request.get("revision_context"):
            result["revision_mode"] = revision_modes.pop(0) if revision_modes else request["revision_context"]["revision_mode"]
        return result

    @activity.defn(name="apply_patch_to_repo")
    async def apply_patch_to_repo(_request: dict) -> dict:
        called("apply_patch_to_repo")
        return {"blocked": False, "already_applied": False}

    @activity.defn(name="run_validation")
    async def run_validation(_request: dict) -> dict:
        called("run_validation")
        return {"passed": True, "commands": [{"command": "pytest", "exit_code": 0}]}

    @activity.defn(name="run_release")
    async def run_release(_request: dict) -> dict:
        called("run_release")
        return {"branch": "wi/wi-1", "commit_hash": "commit-1", "push_failed": ""}

    @activity.defn(name="revert_patch")
    async def revert_patch(_request: dict) -> dict:
        called("revert_patch")
        return {"failed": "", "already_reverted": False}

    @activity.defn(name="publish_merge_request")
    async def publish_merge_request(request: dict) -> dict:
        called("publish_merge_request")
        gitlab_requests.append(request)
        attempt = calls["publish_merge_request"]
        return {
            "repo": "main",
            "branch": "wi/wi-1",
            "commit_hash": f"commit-{attempt}",
            "validation": {"passed": True, "commands": []},
            "merge_request": {
                "repo": "main", "project": "group/demo", "mr_iid": 1,
                "mr_url": "https://gitlab.example/group/demo/-/merge_requests/1", "state": "opened",
            },
        }

    @activity.defn(name="check_merge_requests")
    async def check_merge_requests(_request: dict) -> list[dict]:
        called("check_merge_requests")
        state = "merged" if calls.get("publish_merge_request", 0) >= 2 else "opened"
        return [{
            "repo": "main", "project": "group/demo", "mr_iid": 1,
            "mr_url": "https://gitlab.example/group/demo/-/merge_requests/1", "state": state,
        }]

    @activity.defn(name="ready_merge_requests")
    async def ready_merge_requests(request: dict) -> list[dict]:
        called("ready_merge_requests")
        return request["merge_requests"]

    @activity.defn(name="send_projection_event")
    async def send_projection_event(event: dict) -> None:
        events.append(event)

    async with await WorkflowEnvironment.start_time_skipping(data_converter=pydantic_data_converter) as env:
        async with Worker(
            env.client,
            task_queue=TASK_QUEUE,
            workflows=[AsterismCaseWorkflow],
            activities=[fetch_context, run_coding_attempt, apply_patch_to_repo, run_validation,
                        run_release, revert_patch, publish_merge_request, check_merge_requests,
                        ready_merge_requests, send_projection_event],
        ):
            handle = await env.client.start_workflow(
                AsterismCaseWorkflow.run,
                _case_input(execution_architecture, max_revisions, release_mode),
                id=f"case-{uuid4()}",
                task_queue=TASK_QUEUE,
            )
            for signal_name, payload in signals:
                context = payload if isinstance(payload, dict) else {"signal_id": payload}
                await handle.signal(signal_name, context)
            result = await asyncio.wait_for(handle.result(), timeout=8)
    return events, result, calls, requests


def _case_input(execution_architecture: str, max_revisions: int, release_mode: str) -> CaseInput:
    return CaseInput(
        case_id="case-1",
        work_item_id="wi-1",
        prd_id="prd-1",
        system_id="system-1",
        prd=PrdSpec(
            title="登录页错误提示", goal="把登录页加错误提示",
            acceptance_criteria=["错误密码时显示提示"],
        ),
        repo_path="/tmp/repo",
        allowed_paths=["src"],
        test_commands=["pytest"],
        execution_architecture=execution_architecture,
        max_revisions=max_revisions,
        release_mode=release_mode,
    )


def _business_types(events: list[dict]) -> list[str]:
    return [event["eventType"] for event in events if event["eventType"] != "TemporalActionCompleted"]
