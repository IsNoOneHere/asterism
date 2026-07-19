import asyncio
from uuid import uuid4

import pytest
from temporalio import activity
from temporalio.client import WorkflowFailureError
from temporalio.contrib.pydantic import pydantic_data_converter
from temporalio.testing import WorkflowEnvironment
from temporalio.worker import Worker

from asterism_worker.contracts import CaseInput, PrdSpec
from asterism_worker.workflows.case_lifecycle import AsterismCaseWorkflow


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
) -> tuple[list[dict], str, dict[str, int], list[dict]]:
    events: list[dict] = []
    calls: dict[str, int] = {}
    requests = coding_requests if coding_requests is not None else []
    remaining_coding_failures = coding_failures

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
        return {
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

    @activity.defn(name="send_projection_event")
    async def send_projection_event(event: dict) -> None:
        events.append(event)

    async with await WorkflowEnvironment.start_time_skipping(data_converter=pydantic_data_converter) as env:
        async with Worker(
            env.client,
            task_queue=TASK_QUEUE,
            workflows=[AsterismCaseWorkflow],
            activities=[fetch_context, run_coding_attempt, apply_patch_to_repo, run_validation,
                        run_release, revert_patch, send_projection_event],
        ):
            handle = await env.client.start_workflow(
                AsterismCaseWorkflow.run,
                _case_input(execution_architecture),
                id=f"case-{uuid4()}",
                task_queue=TASK_QUEUE,
            )
            for signal_name, payload in signals:
                await handle.signal(signal_name, payload)
            result = await asyncio.wait_for(handle.result(), timeout=8)
    return events, result, calls, requests


def _case_input(execution_architecture: str) -> CaseInput:
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
    )


def _business_types(events: list[dict]) -> list[str]:
    return [event["eventType"] for event in events if event["eventType"] != "TemporalActionCompleted"]
