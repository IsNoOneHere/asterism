import asyncio
from uuid import uuid4

import pytest
from temporalio import activity
from temporalio.client import WorkflowFailureError
from temporalio.contrib.pydantic import pydantic_data_converter
from temporalio.exceptions import ApplicationError
from temporalio.testing import WorkflowEnvironment
from temporalio.worker import Worker

from asterism_worker.contracts import CaseInput, PrdSpec
from asterism_worker.workflows.lifecycle import AsterismCaseWorkflow


TASK_QUEUE = "asterism-test"


def test_terminal_workflow_runs_local_lifecycle_without_legacy_activities():
    events, result, calls, _ = asyncio.run(_run_workflow([
        ("owner_approved", "approve-1"),
        ("start_modification", "start-1"),
        ("coding_plan_approved", "plan-approve-1"),
        ("patch_apply_approved", "patch-1"),
        ("release_approved", "release-1"),
    ]))

    assert result == "completed"
    assert _business_types(events) == [
        "WorkItemActivated", "CodingPlanStarted", "CodingPlanProposed", "CodingPlanApproved",
        "CodingAttemptStarted", "AgentStageCompleted", "ModificationCompleted",
        "PatchApplied", "ValidationPassed", "RepositoryReleasePrepared", "ReleaseCompleted",
    ]
    assert calls == {"fetch_context": 1, "generate_coding_plan": 1, "run_coding_attempt": 1, "apply_patch_to_repo": 1,
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
        ("coding_plan_approved", "plan-approve-1"),
        ("retry_current_phase", {"signal_id": "retry-1", "note": "只修复登录提示"}),
        ("patch_apply_approved", "patch-1"),
        ("release_approved", "release-1"),
    ], coding_failures=2, coding_requests=requests))

    assert result == "completed"
    assert calls["fetch_context"] == 1
    assert calls["run_coding_attempt"] == 3
    assert requests[2]["feedback"] == "重试补充：只修复登录提示"
    assert _business_types(events).count("CodingAttemptStarted") == 2


def test_plan_rejection_automatically_replans_and_approved_plan_resumes_session():
    plan_requests: list[dict] = []
    coding_requests: list[dict] = []
    events, result, calls, _ = asyncio.run(_run_workflow([
        ("owner_approved", "approve-1"),
        ("start_modification", "start-1"),
        ("coding_plan_rejected", {
            "signal_id": "plan-reject-1", "note": "不要改接口，只调整前端提示", "actor_id": "owner-1",
        }),
        ("coding_plan_approved", {"signal_id": "plan-approve-2", "actor_id": "owner-1"}),
        ("patch_apply_approved", "patch-1"),
        ("release_approved", "release-1"),
    ], plan_requests=plan_requests, coding_requests=coding_requests))

    assert result == "completed"
    assert calls["generate_coding_plan"] == 2
    assert plan_requests[1]["feedback"] == "计划打回意见：不要改接口，只调整前端提示"
    assert plan_requests[1]["resume_session_id"] == ""
    assert plan_requests[1]["previous_plan"]["revision"] == 1
    assert coding_requests[0]["approved_plan"]["revision"] == 2
    assert coding_requests[0]["resume_session_id"] == "plan-session-2"
    proposed = [event["payload"]["planRevision"] for event in events if event["eventType"] == "CodingPlanProposed"]
    assert proposed == [1, 2]


def test_plan_base_drift_refreshes_workspace_and_replans_in_new_session():
    plan_requests: list[dict] = []
    events, result, calls, _ = asyncio.run(_run_workflow([
        ("owner_approved", "approve-1"),
        ("start_modification", "start-1"),
        ("coding_plan_approved", "plan-approve-1"),
        ("coding_plan_approved", "plan-approve-2"),
        ("patch_apply_approved", "patch-1"),
        ("release_approved", "release-1"),
    ], plan_base_changes=1, plan_requests=plan_requests))

    assert result == "completed"
    assert calls["generate_coding_plan"] == 2
    assert calls["run_coding_attempt"] == 2
    assert plan_requests[1]["refresh_workspace"] is True
    assert plan_requests[1]["resume_session_id"] == ""
    assert _contains_in_order(_business_types(events), [
        "CodingPlanApproved", "CodingAttemptStarted", "CodingPlanInvalidated",
        "CodingPlanStarted", "CodingPlanProposed",
    ])


def test_patch_rejection_automatically_runs_incremental_revision_with_feedback():
    requests: list[dict] = []
    events, result, calls, requests = asyncio.run(_run_workflow([
        ("owner_approved", "approve-1"),
        ("start_modification", "start-1"),
        ("coding_plan_approved", "plan-approve-1"),
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
    assert _contains_in_order(_business_types(events), [
        "PatchRejected", "ReworkStarted", "RevisionRequested", "CodingAttemptStarted", "AgentStageCompleted",
    ])
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
        ("coding_plan_approved", "plan-approve-1"),
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
        ("coding_plan_approved", "plan-approve-1"),
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
    plan_requests: list[dict] = []
    coding_requests: list[dict] = []
    events, result, calls, _ = asyncio.run(_run_workflow([
        ("owner_approved", "approve-1"),
        ("start_modification", "start-1"),
        ("coding_plan_approved", "plan-approve-1"),
        ("patch_apply_rejected", {"signal_id": "reject-1", "note": "第一轮意见"}),
        ("patch_apply_rejected", {"signal_id": "reject-2", "note": "超出上限"}),
        ("rework", {"signal_id": "full-1", "note": "放弃候选并完整重做"}),
        ("coding_plan_approved", "plan-approve-2"),
        ("patch_apply_approved", "patch-3"),
        ("release_approved", "release-1"),
    ], max_revisions=1, plan_requests=plan_requests, coding_requests=coding_requests))

    assert result == "completed"
    assert calls["run_coding_attempt"] == 3
    modifications = [event for event in events if event["eventType"] == "ModificationCompleted"]
    assert modifications[-1]["payload"]["revision"] == 0
    assert modifications[-1]["payload"]["revisionMode"] == "full"
    assert [event["payload"]["revision"] for event in events if event["eventType"] == "RevisionRequested"] == [1]
    assert plan_requests[-1]["refresh_workspace"] is True
    assert coding_requests[-1]["previous_candidate"] == []
    coding_started = [event for event in events if event["eventType"] == "CodingAttemptStarted"]
    assert coding_started[-1]["payload"]["candidateReused"] is False


def test_patch_rejection_without_note_is_not_accepted():
    events, result, _, _ = asyncio.run(_run_workflow([
        ("owner_approved", "approve-1"),
        ("start_modification", "start-1"),
        ("coding_plan_approved", "plan-approve-1"),
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
        ("coding_plan_approved", "plan-approve-1"),
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


def test_interrupt_attempt_stops_activity_and_preserves_recoverable_workflow():
    events, result, calls, _ = asyncio.run(_run_workflow([
        ("owner_approved", "approve-1"),
        ("start_modification", "start-1"),
        ("coding_plan_approved", "plan-approve-1"),
        ("interrupt_attempt", {
            "signal_id": "interrupt-1", "note": "实现方向偏离已批准计划", "actor_id": "owner-1",
        }),
        ("cancel_case", "cancel-1"),
    ], wait_for_coding_interrupt=True))

    assert result == "cancelled"
    assert calls["run_coding_attempt"] == 1
    assert "ModificationCompleted" not in _business_types(events)
    blocked = next(event for event in events if event["eventType"] == "WorkerBlocked")
    assert blocked["payload"] == {
        "reason": "attempt_interrupted",
        "detail": "实现方向偏离已批准计划",
        "failedPhase": "coding",
        "interruptedBy": "owner-1",
    }


def test_context_stale_requires_explicit_manifest_refresh_and_replans_with_one_snapshot():
    coding_requests: list[dict] = []
    plan_requests: list[dict] = []
    events, result, calls, _ = asyncio.run(_run_workflow([
        ("owner_approved", "approve-1"),
        ("start_modification", "start-1"),
        ("rework_with_latest_context", {
            "signal_id": "refresh-context-1", "requirement_manifest_id": "manifest-2",
        }),
        ("coding_plan_approved", "plan-approve-1"),
        ("patch_apply_approved", "patch-1"),
        ("release_approved", "release-1"),
    ], context_stale_once=True, coding_requests=coding_requests, plan_requests=plan_requests))

    assert result == "completed"
    assert calls["fetch_context"] == 2
    blocked = next(event for event in events if event["eventType"] == "WorkerBlocked")
    assert blocked["payload"]["reason"] == "context_stale"
    assert plan_requests[0]["requirement_manifest_id"] == "manifest-2"
    assert coding_requests[0]["requirement_manifest_id"] == "manifest-2"
    started = next(event for event in events if event["eventType"] == "CodingAttemptStarted")
    assert started["payload"]["requirementManifestId"] == "manifest-2"


def test_structured_blocked_with_partial_diff_never_completes_modification_until_retry():
    requests: list[dict] = []
    blocked = {
        "status": "blocked",
        "task_outcomes": [{
            "task_id": "task-01", "status": "blocked", "summary": "只有 import，业务实现未完成",
            "changed_paths": ["src/app.py"],
        }],
        "blockers": ["前后端业务实现和测试未完成"],
        "changed_paths": ["src/app.py"],
        "session_id": "session-1",
    }
    events, result, calls, requests = asyncio.run(_run_workflow([
        ("owner_approved", "approve-1"),
        ("start_modification", "start-1"),
        ("coding_plan_approved", "plan-approve-1"),
        ("retry_current_phase", {"signal_id": "retry-1", "note": "继续完成批准计划"}),
        ("patch_apply_approved", "patch-1"),
        ("release_approved", "release-1"),
    ], coding_requests=requests, coding_outcomes=[blocked]))

    assert result == "completed"
    assert calls["run_coding_attempt"] == 2
    assert _business_types(events).count("ModificationCompleted") == 1
    first_blocked = next(event for event in events if event["eventType"] == "WorkerBlocked")
    assert first_blocked["payload"]["reason"] == "coding_attempt_blocked"
    assert first_blocked["payload"]["partialChanges"] == [{
        "repo": "main", "changedPaths": ["src/app.py"],
    }]
    assert requests[1]["previous_candidate"][0]["diff_patch"].startswith("diff --git")
    assert requests[1]["resume_session_id"] == "session-1"


async def _run_workflow(
    signals: list[tuple[str, str | dict]],
    *,
    execution_architecture: str = "claude_sdk_team",
    coding_failures: int = 0,
    plan_base_changes: int = 0,
    coding_requests: list[dict] | None = None,
    revision_mode_overrides: list[str] | None = None,
    max_revisions: int = 5,
    release_mode: str = "local",
    publish_requests: list[dict] | None = None,
    plan_requests: list[dict] | None = None,
    coding_outcomes: list[dict] | None = None,
    wait_for_coding_interrupt: bool = False,
    context_stale_once: bool = False,
) -> tuple[list[dict], str, dict[str, int], list[dict]]:
    events: list[dict] = []
    calls: dict[str, int] = {}
    requests = coding_requests if coding_requests is not None else []
    remaining_coding_failures = coding_failures
    remaining_plan_base_changes = plan_base_changes
    revision_modes = list(revision_mode_overrides or [])
    gitlab_requests = publish_requests if publish_requests is not None else []
    planning_requests = plan_requests if plan_requests is not None else []
    outcomes = list(coding_outcomes or [])

    def called(name: str) -> None:
        calls[name] = calls.get(name, 0) + 1

    @activity.defn(name="fetch_context")
    async def fetch_context(request: dict) -> dict:
        called("fetch_context")
        stale = ["MEM:changed"] if context_stale_once and calls["fetch_context"] == 1 else []
        return {
            "system_id": request["system_id"],
            "requirement_manifest_id": request["requirement_manifest_id"],
            "requirement_items": [{"refId": "MEM:mem-1", "content": "遵守登录页约束"}],
            "execution_bundle_id": "bundle-execution-1",
            "execution_items": [], "stale_references": stale,
        }

    @activity.defn(name="run_coding_attempt")
    async def run_coding_attempt(request: dict) -> dict:
        nonlocal remaining_coding_failures, remaining_plan_base_changes
        called("run_coding_attempt")
        requests.append(request)
        if wait_for_coding_interrupt:
            while True:
                activity.heartbeat({"phase": "coding"})
                await asyncio.sleep(0.05)
        if remaining_plan_base_changes:
            remaining_plan_base_changes -= 1
            raise ApplicationError(
                "main: 审批期间仓库已更新，请重新生成计划",
                type="PlanBaseChanged",
                non_retryable=True,
            )
        if remaining_coding_failures:
            remaining_coding_failures -= 1
            raise RuntimeError("coding unavailable")
        result = {
            "summary": "Supervisor 完成",
            "outcome": outcomes.pop(0) if outcomes else {
                "status": "completed",
                "task_outcomes": [{
                    "task_id": "task-01", "status": "completed", "changed_paths": ["src/app.py"],
                }],
                "blockers": [], "changed_paths": ["src/app.py"], "session_id": "session-1",
            },
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

    @activity.defn(name="generate_coding_plan")
    async def generate_coding_plan(request: dict) -> dict:
        called("generate_coding_plan")
        planning_requests.append(request)
        revision = request["plan_revision"]
        return {
            "summary": f"第 {revision} 版计划",
            "tasks": [{
                "task_id": "task-01", "repo": "main", "objective": "修改登录提示",
                "acceptance_criteria_refs": ["AC-1"], "evidence": ["src/app.py:login"],
            }],
            "risks": [], "open_questions": [], "revision": revision,
            "session_id": f"plan-session-{revision}", "base_revisions": {"main": "base-1"},
        }

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
            activities=[fetch_context, generate_coding_plan, run_coding_attempt, apply_patch_to_repo, run_validation,
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
                if signal_name == "interrupt_attempt":
                    async def coding_started() -> None:
                        while not any(event["eventType"] == "CodingAttemptStarted" for event in events):
                            await asyncio.sleep(0.01)

                    await asyncio.wait_for(coding_started(), timeout=2)
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
            requirement_manifest_id="manifest-1",
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


def _contains_in_order(values: list[str], expected: list[str]) -> bool:
    iterator = iter(values)
    return all(any(value == target for value in iterator) for target in expected)
