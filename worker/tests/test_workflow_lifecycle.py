import asyncio
import subprocess
from uuid import uuid4

from temporalio import activity
from temporalio.contrib.pydantic import pydantic_data_converter
from temporalio.testing import WorkflowEnvironment
from temporalio.worker import Worker

from asterism_worker.contracts import CaseInput, ExecutionPlan, ExecutionResult, PatchApplyResult, PrdSpec
from asterism_worker.workflows.case_lifecycle import HANDOFF_DIFF_LIMIT_BYTES, AsterismCaseWorkflow, _handoff_diff
from asterism_worker.activities.execution import validate_patch_paths


TASK_QUEUE = "asterism-test"


def test_workflow_runs_legal_full_lifecycle():
    events, result = asyncio.run(_run_workflow([
        ("owner_approved", "owner-approved-wi-1"),
        ("start_modification", "start-modification-wi-1"),
        ("patch_apply_approved", "patch-apply-approved-wi-1"),
        ("release_approved", "release-approved-wi-1"),
    ]))

    assert result == "completed"
    assert _business_types(events) == [
        "WorkItemActivated",
        "ExecutionPlanDrafted",
        "ModificationCompleted",
        "PatchApplied",
        "ValidationPassed",
        "RepositoryReleasePrepared",
        "ReleaseCompleted",
    ]
    plan_event = next(event for event in events if event["eventType"] == "ExecutionPlanDrafted")
    modification = next(event for event in events if event["eventType"] == "ModificationCompleted")
    assert plan_event["payload"]["contextManifestId"] == "manifest-1"
    assert plan_event["payload"]["plan"]["steps"] == ["按验收标准修改: 错误密码时显示提示"]
    assert modification["payload"]["goal"] == "把登录页加错误提示"
    assert modification["payload"]["contextManifestId"] == "manifest-1"
    assert modification["payload"]["executionProvider"] == "claude_sdk"
    assert modification["payload"]["turns"] == 3
    assert modification["payload"]["tokenUsage"] == {"input_tokens": 120, "output_tokens": 30}
    release = next(event for event in events if event["eventType"] == "ReleaseCompleted")
    assert release["payload"]["branch"] == "wi/wi-1"
    assert release["payload"]["commitHash"] == "abc123"


def test_workflow_auto_validates_success_after_patch_apply():
    events, result = asyncio.run(_run_workflow([
        ("owner_approved", "owner-approved-wi-1"),
        ("start_modification", "start-modification-wi-1"),
        ("patch_apply_approved", "patch-apply-approved-wi-1"),
        ("release_approved", "release-approved-wi-1"),
    ], validation_result={
        "passed": True,
        "commands": [{"command": "python -c pass", "exit_code": 0, "stdout_tail": "ok", "stderr_tail": ""}],
    }))

    assert result == "completed"
    event_types = _business_types(events)
    assert event_types == [
        "WorkItemActivated",
        "ExecutionPlanDrafted",
        "ModificationCompleted",
        "PatchApplied",
        "ValidationPassed",
        "RepositoryReleasePrepared",
        "ReleaseCompleted",
    ]
    validation = next(event for event in events if event["eventType"] == "ValidationPassed")
    assert validation["actorId"] == "worker"
    assert validation["payload"]["commands"][0]["command"] == "python -c pass"


def test_workflow_auto_validates_failure_after_patch_apply():
    events, result = asyncio.run(_run_workflow([
        ("owner_approved", "owner-approved-wi-1"),
        ("start_modification", "start-modification-wi-1"),
        ("patch_apply_approved", "patch-apply-approved-wi-1"),
        ("cancel_case", "cancel-case-wi-1"),
    ], validation_result={
        "passed": False,
        "failed_command": "python -c fail",
        "stderr_tail": "boom",
        "commands": [{"command": "python -c fail", "exit_code": 1, "stdout_tail": "", "stderr_tail": "boom"}],
    }))

    assert result == "cancelled"
    validation = next(event for event in events if event["eventType"] == "ValidationFailed")
    assert validation["payload"]["failedCommand"] == "python -c fail"
    assert validation["payload"]["stderrTail"] == "boom"


def test_workflow_keeps_manual_validation_when_test_commands_empty():
    events, result = asyncio.run(_run_workflow([
        ("owner_approved", "owner-approved-wi-1"),
        ("start_modification", "start-modification-wi-1"),
        ("patch_apply_approved", "patch-apply-approved-wi-1"),
        ("validation_passed", "validation-passed-wi-1"),
        ("release_approved", "release-approved-wi-1"),
    ], test_commands=[], validation_mode="manual"))

    assert result == "completed"
    assert [event["eventType"] for event in events].count("ValidationPassed") == 1


def test_workflow_ignores_illegal_signal():
    events, result = asyncio.run(_run_workflow([
        ("validation_passed", "validation-passed-wi-1"),
        ("cancel_case", "cancel-case-wi-1"),
    ]))

    assert result == "cancelled"
    assert _business_types(events) == ["CaseCancelled"]


def test_workflow_emits_duplicate_owner_approved_once():
    events, result = asyncio.run(_run_workflow([
        ("owner_approved", "owner-approved-wi-1"),
        ("owner_approved", "owner-approved-wi-1"),
        ("cancel_case", "cancel-case-wi-1"),
    ]))

    assert result == "cancelled"
    assert [event["eventType"] for event in events].count("WorkItemActivated") == 1


def test_workflow_returns_when_owner_rejected():
    events, result = asyncio.run(_run_workflow([
        ("owner_rejected", "owner-rejected-wi-1"),
    ]))

    assert result == "rejected"
    assert _business_types(events) == ["WorkItemRejected"]


def test_workflow_rework_uses_distinct_modification_idempotency_keys():
    events, result = asyncio.run(_run_workflow([
        ("owner_approved", "owner-approved-wi-1"),
        ("start_modification", "start-modification-wi-1"),
        ("patch_apply_approved", "patch-apply-approved-wi-1"),
        ("validation_rejected", "validation-rejected-wi-1"),
        ("rework", "rework-wi-1"),
        ("start_modification", "start-modification-wi-2"),
        ("patch_apply_approved", "patch-apply-approved-wi-2"),
        ("validation_passed", "validation-passed-wi-2"),
        ("release_approved", "release-approved-wi-2"),
    ], test_commands=[], validation_mode="manual"))

    modification_keys = [
        event["idempotencyKey"]
        for event in events
        if event["eventType"] == "ModificationCompleted"
    ]
    assert result == "completed"
    assert "ReworkStarted" in [event["eventType"] for event in events]
    assert len(modification_keys) == 2
    assert modification_keys[0] != modification_keys[1]


def test_workflow_blocks_when_planner_fails():
    events, result = asyncio.run(_run_workflow([
        ("owner_approved", "owner-approved-wi-1"),
        ("start_modification", "start-modification-wi-1"),
        ("cancel_case", "cancel-case-wi-1"),
    ], planner_failure=True))

    assert result == "cancelled"
    blocked = next(event for event in events if event["eventType"] == "WorkerBlocked")
    assert blocked["payload"]["reason"] == "planner_failed"


def test_new_case_uses_single_coding_attempt_without_planner_activities():
    counts: dict[str, int] = {}
    requests: list[dict] = []
    events, result = asyncio.run(_run_workflow([
        ("owner_approved", "owner-approved-wi-1"),
        ("start_modification", "start-modification-wi-1"),
        ("cancel_case", "cancel-case-wi-1"),
    ], repos=_gitlab_repos(), agent_config_snapshot=_agent_snapshot(),
       execution_architecture="claude_supervisor_v1", activity_counts=counts,
       observed_coding_requests=requests))

    assert result == "cancelled"
    assert counts["fetch_context"] == counts["run_coding_attempt"] == 1
    assert counts.get("summarize_repo", 0) == counts.get("plan_execution", 0) == 0
    assert counts.get("validate_plan_targets", 0) == 0
    assert len(requests) == 1
    assert "plan" not in requests[0] and "target_files" not in requests[0]
    business = _business_types(events)
    assert business[:5] == [
        "WorkItemActivated", "CodingAttemptStarted", "AgentStageCompleted", "AgentStageCompleted", "ModificationCompleted",
    ]
    modification = next(event for event in events if event["eventType"] == "ModificationCompleted")
    assert {item["repo"] for item in modification["payload"]["repoDiffs"]} == {"frontend", "backend"}
    assert modification["payload"]["sessionId"] == "session-team"


def test_supervisor_refreshes_config_and_retries_whole_attempt_without_planner():
    counts: dict[str, int] = {}
    requests: list[dict] = []
    events, result = asyncio.run(_run_workflow([
        ("owner_approved", "owner-approved-wi-1"),
        ("start_modification", "start-modification-wi-1"),
        ("rework_with_latest_config", {
            "signal_id": "rework-latest-wi-1",
            "agent_config_snapshot": _agent_snapshot(),
            "resume_failed_stage": True,
        }),
        ("cancel_case", "cancel-case-wi-1"),
    ], repos=_gitlab_repos(), agent_config_snapshot=_agent_snapshot(),
       execution_architecture="claude_supervisor_v1", activity_counts=counts,
       observed_coding_requests=requests, coding_attempt_failures=1))

    assert result == "cancelled"
    assert counts["run_coding_attempt"] == 2
    assert counts.get("plan_execution", 0) == 0
    assert [event["eventType"] for event in events].count("CodingAttemptStarted") == 2
    assert any(event["eventType"] == "WorkerBlocked" and event["payload"]["reason"] == "coding_attempt_failed"
               for event in events)
    rework = next(event for event in events if event["eventType"] == "ReworkStarted")
    assert rework["payload"]["configurationRefreshed"] is True


def test_supervisor_rework_receives_feedback_and_complete_previous_candidate():
    requests: list[dict] = []
    events, result = asyncio.run(_run_workflow([
        ("owner_approved", "owner-approved-wi-1"),
        ("start_modification", "start-modification-wi-1"),
        ("patch_apply_rejected", {"signal_id": "reject-patch-wi-1", "note": "部门应读取 deptName"}),
        ("rework", {"signal_id": "rework-wi-1", "note": "不要使用 record.depts"}),
        ("cancel_case", "cancel-case-wi-1"),
    ], repos=_gitlab_repos(), agent_config_snapshot=_agent_snapshot(),
       execution_architecture="claude_supervisor_v1", observed_coding_requests=requests))

    assert result == "cancelled"
    assert len(requests) == 2
    assert "部门应读取 deptName" in requests[1]["feedback"]
    assert "不要使用 record.depts" in requests[1]["feedback"]
    assert {item["repo"] for item in requests[1]["previous_candidate"]} == {"frontend", "backend"}
    assert all("diff --git" in item["diff_patch"] for item in requests[1]["previous_candidate"])
    coding_events = [event for event in events if event["eventType"] == "CodingAttemptStarted"]
    assert [event["payload"]["candidateReused"] for event in coding_events] == [False, True]
    assert [event["eventType"] for event in events].count("ModificationCompleted") == 2


def test_workflow_blocks_when_context_fetch_keeps_failing():
    events, result = asyncio.run(_run_workflow([
        ("owner_approved", "owner-approved-wi-1"),
        ("start_modification", "start-modification-wi-1"),
        ("cancel_case", "cancel-case-wi-1"),
    ], failure_mode="fetch_context"))

    assert result == "cancelled"
    blocked = next(event for event in events if event["eventType"] == "WorkerBlocked")
    assert blocked["payload"]["reason"] == "context_fetch_failed"
    assert "context down" in blocked["payload"]["detail"]


def test_workflow_blocks_when_execution_keeps_failing():
    events, result = asyncio.run(_run_workflow([
        ("owner_approved", "owner-approved-wi-1"),
        ("start_modification", "start-modification-wi-1"),
        ("cancel_case", "cancel-case-wi-1"),
    ], failure_mode="run_execution"))

    assert result == "cancelled"
    blocked = next(event for event in events if event["eventType"] == "WorkerBlocked")
    assert blocked["payload"]["reason"] == "execution_failed"
    assert "executor down" in blocked["payload"]["detail"]


def test_workflow_blocks_when_patch_apply_activity_throws():
    events, result = asyncio.run(_run_workflow([
        ("owner_approved", "owner-approved-wi-1"),
        ("start_modification", "start-modification-wi-1"),
        ("patch_apply_approved", "patch-apply-approved-wi-1"),
        ("cancel_case", "cancel-case-wi-1"),
    ], failure_mode="apply_patch"))

    assert result == "cancelled"
    blocked = next(event for event in events if event["eventType"] == "WorkerBlocked")
    assert blocked["payload"]["reason"] == "patch_apply_failed"
    assert "git apply exploded" in blocked["payload"]["detail"]


def test_workflow_blocks_when_release_fails():
    events, result = asyncio.run(_run_workflow([
        ("owner_approved", "owner-approved-wi-1"),
        ("start_modification", "start-modification-wi-1"),
        ("patch_apply_approved", "patch-apply-approved-wi-1"),
        ("release_approved", "release-approved-wi-1"),
        ("cancel_case", "cancel-case-wi-1"),
    ], failure_mode="release"))

    assert result == "cancelled"
    blocked = next(event for event in events if event["eventType"] == "WorkerBlocked")
    assert blocked["payload"]["reason"] == "release_failed"
    assert "release exploded" in blocked["payload"]["detail"]


def test_patch_phase_retry_reuses_coding_candidate():
    counts: dict[str, int] = {}
    events, result = asyncio.run(_run_workflow([
        ("owner_approved", "owner-approved-wi-1"),
        ("start_modification", "start-modification-wi-1"),
        ("patch_apply_approved", "patch-apply-approved-wi-1"),
        ("retry_current_phase", "retry-patch-wi-1"),
        ("release_approved", "release-approved-wi-1"),
    ], repos=_local_repos(), agent_config_snapshot=_agent_snapshot(),
       execution_architecture="claude_supervisor_v1", activity_counts=counts,
       patch_failures=3))

    assert result == "completed"
    assert counts["fetch_context"] == counts["run_coding_attempt"] == 1
    assert counts.get("plan_execution", 0) == 0
    assert counts["apply_patch_to_repo"] == 5
    blocked = next(event for event in events if event["eventType"] == "WorkerBlocked")
    assert blocked["payload"]["failedPhase"] == "patch"
    rework = next(event for event in events if event["eventType"] == "ReworkStarted")
    assert rework["payload"] == {
        "configurationRefreshed": False,
        "retryPhase": "patch",
        "retryScope": "phase",
    }


def test_validation_phase_retry_skips_patch_and_coding():
    counts: dict[str, int] = {}
    events, result = asyncio.run(_run_workflow([
        ("owner_approved", "owner-approved-wi-1"),
        ("start_modification", "start-modification-wi-1"),
        ("patch_apply_approved", "patch-apply-approved-wi-1"),
        ("retry_current_phase", "retry-validation-wi-1"),
        ("release_approved", "release-approved-wi-1"),
    ], repos=_local_repos(), agent_config_snapshot=_agent_snapshot(),
       execution_architecture="claude_supervisor_v1", activity_counts=counts,
       validation_activity_failures=1))

    assert result == "completed"
    assert counts["run_coding_attempt"] == 1
    assert counts["apply_patch_to_repo"] == 2
    assert counts["run_validation"] == 3
    blocked = next(event for event in events if event["eventType"] == "WorkerBlocked")
    assert blocked["payload"]["failedPhase"] == "validation"
    reused_patch = next(event for event in events
                        if event["eventType"] == "PatchApplied" and event["payload"].get("reused"))
    assert reused_patch["payload"]["recoveryPhase"] == "validation"


def test_release_phase_retry_skips_coding_patch_and_validation():
    counts: dict[str, int] = {}
    events, result = asyncio.run(_run_workflow([
        ("owner_approved", "owner-approved-wi-1"),
        ("start_modification", "start-modification-wi-1"),
        ("patch_apply_approved", "patch-apply-approved-wi-1"),
        ("release_approved", "release-approved-wi-1"),
        ("retry_current_phase", "retry-release-wi-1"),
    ], repos=_local_repos(), agent_config_snapshot=_agent_snapshot(),
       execution_architecture="claude_supervisor_v1", activity_counts=counts,
       release_failures=2))

    assert result == "completed"
    assert counts["run_coding_attempt"] == 1
    assert counts["apply_patch_to_repo"] == 2
    assert counts["run_validation"] == 2
    assert counts["run_release"] == 4
    blocked = next(event for event in events if event["eventType"] == "WorkerBlocked")
    assert blocked["payload"]["failedPhase"] == "release"
    reused = [event["eventType"] for event in events if event["payload"].get("reused")]
    assert reused == ["PatchApplied", "ValidationPassed"]


def test_workflow_can_rework_after_context_fetch_failure():
    events, result = asyncio.run(_run_workflow([
        ("owner_approved", "owner-approved-wi-1"),
        ("start_modification", "start-modification-wi-1"),
        ("rework", "rework-wi-1"),
        ("start_modification", "start-modification-wi-2"),
        ("cancel_case", "cancel-case-wi-2"),
    ], fetch_failures=3))

    assert result == "cancelled"
    assert [event["eventType"] for event in events].count("WorkerBlocked") == 1
    assert "ReworkStarted" in [event["eventType"] for event in events]
    assert [event["eventType"] for event in events].count("ModificationCompleted") == 1


def test_workflow_handoff_uses_role_step_refs_and_unique_stage_keys():
    requests: list[dict] = []
    assignments = [
        {"role": "frontend", "scope_paths": ["web"], "step_refs": ["step-web"]},
        {"role": "backend", "scope_paths": ["api"], "step_refs": ["step-api"]},
    ]
    events, result = asyncio.run(_run_workflow([
        ("owner_approved", "owner-approved-wi-1"),
        ("start_modification", "start-modification-wi-1"),
        ("cancel_case", "cancel-case-wi-1"),
    ], assignments=assignments, observed_requests=requests))

    stages = [event for event in events if event["eventType"] == "AgentStageCompleted"]
    assert result == "cancelled"
    assert [request["step_refs"] for request in requests] == [["step-web"], ["step-api"]]
    assert requests[1]["handoff"] == [{
        "role": "frontend",
        "repo": "main",
        "summary": "frontend 完成",
        "diff_patch": "diff --git a/web/app.ts b/web/app.ts\n--- a/web/app.ts\n+++ b/web/app.ts\n"
                      "@@ -1 +1 @@\n-old-web\n+new-web\n",
        "interface_notes": "frontend 对外行为已更新",
    }]
    assert [event["payload"]["engine"] for event in stages] == ["claude_sdk", "deepagents"]
    assert [event["payload"]["stageIndex"] for event in stages] == [0, 1]
    assert len({event["idempotencyKey"] for event in stages}) == 2
    assert stages[0]["causationId"].endswith(":stage:0:frontend")
    assert stages[1]["causationId"].endswith(":stage:1:backend")


def test_handoff_diff_keeps_32kb_and_condenses_one_byte_over_limit():
    header = "diff --git a/src/app.py b/src/app.py\n@@ -1 +1 @@\n"
    exact = header + "x" * (HANDOFF_DIFF_LIMIT_BYTES - len(header.encode()))

    assert _handoff_diff(exact, ["src/app.py"]) == exact
    condensed = _handoff_diff(exact + "x", ["src/app.py"])
    assert condensed == "changed_paths: src/app.py\ndiff --git a/src/app.py b/src/app.py\n@@ -1 +1 @@\n"
    assert len(condensed.encode()) <= HANDOFF_DIFF_LIMIT_BYTES


def test_stage_failure_retry_resumes_failed_stage_without_replanning():
    requests: list[dict] = []
    counts: dict[str, int] = {}
    assignments = [
        {"role": "frontend", "scope_paths": ["web"], "step_refs": ["step-web"]},
        {"role": "backend", "scope_paths": ["api"], "step_refs": ["step-api"]},
    ]
    events, result = asyncio.run(_run_workflow([
        ("owner_approved", "owner-approved-wi-1"),
        ("start_modification", "start-modification-wi-1"),
        ("retry_current_phase", "retry-current-phase-wi-1"),
        ("cancel_case", "cancel-case-wi-1"),
    ], assignments=assignments, observed_requests=requests, agent_config_snapshot=_agent_snapshot(),
       fail_role="backend", fail_role_attempts=2, activity_counts=counts))

    blocked = next(event for event in events if event["eventType"] == "WorkerBlocked")
    assert result == "cancelled"
    assert blocked["payload"]["completed_stages"] == [{
        "role": "frontend", "repo": "main", "summary": "frontend 完成", "changed_paths": ["web/app.ts"],
    }]
    assert blocked["payload"]["failed_stage"] == {"index": 1, "role": "backend", "repo": "main"}
    assert [request["role_id"] for request in requests].count("frontend") == 1
    assert [request["role_id"] for request in requests].count("backend") == 3
    assert counts["fetch_context"] == 1
    assert counts["plan_execution"] == 1
    assert [event["eventType"] for event in events].count("ExecutionPlanDrafted") == 1
    assert "ReworkStarted" in [event["eventType"] for event in events]
    assert "ModificationCompleted" in [event["eventType"] for event in events]


def test_stage_failure_can_refresh_config_and_resume_without_replanning():
    requests: list[dict] = []
    counts: dict[str, int] = {}
    assignments = [
        {"role": "frontend", "scope_paths": ["web"], "step_refs": ["step-web"]},
        {"role": "backend", "scope_paths": ["api"], "step_refs": ["step-api"]},
    ]
    latest = _agent_snapshot()
    latest["model_profiles"].append({"id": "mp-latest", "provider": "anthropic", "model": "latest-model"})
    backend = next(agent for agent in latest["agents"] if agent["name"] == "backend")
    backend.update(engine="claude_sdk", model_profile_ref="mp-latest")
    events, result = asyncio.run(_run_workflow([
        ("owner_approved", "owner-approved-wi-1"),
        ("start_modification", "start-modification-wi-1"),
        ("rework_with_latest_config", {
            "signal_id": "rework-latest-wi-1",
            "agent_config_snapshot": latest,
            "resume_failed_stage": True,
        }),
        ("cancel_case", "cancel-case-wi-1"),
    ], assignments=assignments, observed_requests=requests, agent_config_snapshot=_agent_snapshot(),
       fail_role="backend", fail_role_attempts=2, activity_counts=counts))

    assert result == "cancelled"
    assert counts["fetch_context"] == counts["plan_execution"] == 1
    assert [request["role_id"] for request in requests].count("frontend") == 1
    assert [request["role_id"] for request in requests].count("backend") == 3
    assert requests[-1]["agent_config_snapshot"]["agents"][-1]["model_profile_ref"] == "mp-latest"
    assert [event["eventType"] for event in events].count("ExecutionPlanDrafted") == 1
    rework = next(event for event in events if event["eventType"] == "ReworkStarted")
    assert rework["payload"]["configurationRefreshed"] is True
    completed = next(event for event in events
                     if event["eventType"] == "TemporalActionCompleted"
                     and event["payload"]["action"] == "rework_with_latest_config")
    assert "agent_config_snapshot" not in completed["payload"]


def test_workflow_handoff_blocks_same_file_conflict():
    assignments = [
        {"role": "frontend", "scope_paths": ["src"], "step_refs": ["one"]},
        {"role": "backend", "scope_paths": ["src"], "step_refs": ["two"]},
    ]
    events, result = asyncio.run(_run_workflow([
        ("owner_approved", "owner-approved-wi-1"),
        ("start_modification", "start-modification-wi-1"),
        ("cancel_case", "cancel-case-wi-1"),
    ], assignments=assignments, handoff_mode="conflict"))

    blocked = next(event for event in events if event["eventType"] == "WorkerBlocked")
    assert result == "cancelled"
    assert blocked["payload"]["reason"] == "handoff_conflict"


def test_workflow_handoff_preserves_role_scope_block_reason():
    events, result = asyncio.run(_run_workflow([
        ("owner_approved", "owner-approved-wi-1"),
        ("start_modification", "start-modification-wi-1"),
        ("cancel_case", "cancel-case-wi-1"),
    ], assignments=[{"role": "frontend", "scope_paths": ["web"], "step_refs": ["web"]}],
       handoff_mode="scope"))

    blocked = next(event for event in events if event["eventType"] == "WorkerBlocked")
    assert result == "cancelled"
    assert blocked["payload"]["reason"] == "role_scope_violation"


def test_workflow_handoff_non_conflicting_diff_reaches_release(tmp_path):
    repo = tmp_path / "repo"
    (repo / "web").mkdir(parents=True)
    (repo / "api").mkdir()
    (repo / "web" / "app.ts").write_text("old-web\n")
    (repo / "api" / "app.py").write_text("old-api\n")
    subprocess.run(["git", "init"], cwd=repo, check=True, capture_output=True)
    subprocess.run(["git", "add", "."], cwd=repo, check=True, capture_output=True)
    subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@example.invalid", "commit", "-m", "init"], cwd=repo, check=True, capture_output=True)
    requests: list[dict] = []
    assignments = [
        {"role": "frontend", "scope_paths": ["web"], "step_refs": ["step-web"]},
        {"role": "backend", "scope_paths": ["api"], "step_refs": ["step-api"]},
    ]

    events, result = asyncio.run(_run_workflow([
        ("owner_approved", "owner-approved-wi-1"),
        ("start_modification", "start-modification-wi-1"),
        ("patch_apply_approved", "patch-apply-approved-wi-1"),
        ("release_approved", "release-approved-wi-1"),
    ], assignments=assignments, observed_requests=requests, repo_path=str(repo), verify_combined_diff=True))

    assert result == "completed"
    assert _business_types(events) == [
        "WorkItemActivated", "ExecutionPlanDrafted", "AgentStageCompleted", "AgentStageCompleted",
        "ModificationCompleted", "PatchApplied", "ValidationPassed", "RepositoryReleasePrepared", "ReleaseCompleted",
    ]
    modification = next(event for event in events if event["eventType"] == "ModificationCompleted")
    assert "web/app.ts" in modification["payload"]["diffPatch"]
    assert "api/app.py" in modification["payload"]["diffPatch"]
    assert all("api_key" not in str(request).lower() for request in requests)


def test_two_repositories_route_assignments_and_diff_gates_independently():
    requests: list[dict] = []
    apply_requests: list[dict] = []
    release_requests: list[dict] = []
    repos = [
        {"repo_id": "frontend", "name": "Web", "kind": "frontend", "clone_mode": "local",
         "local_path": "/repos/web", "allowed_paths": ["src"], "forbidden_paths": ["secrets"],
         "test_commands": []},
        {"repo_id": "backend", "name": "API", "kind": "backend", "clone_mode": "local",
         "local_path": "/repos/api", "allowed_paths": ["src"], "forbidden_paths": ["secrets"],
         "test_commands": []},
    ]
    assignments = [
        {"role": "frontend", "repo": "frontend", "scope_paths": ["src"], "step_refs": ["web"]},
        {"role": "backend", "repo": "backend", "scope_paths": ["src"], "step_refs": ["api"]},
    ]

    events, result = asyncio.run(_run_workflow([
        ("owner_approved", "owner-approved-wi-1"),
        ("start_modification", "start-modification-wi-1"),
        ("patch_apply_approved", "patch-apply-approved-wi-1"),
        ("validation_passed", "validation-passed-wi-1"),
        ("release_approved", "release-approved-wi-1"),
    ], test_commands=[], assignments=assignments, handoff_mode="same-path-cross-repo",
       observed_requests=requests, observed_apply_requests=apply_requests,
       observed_release_requests=release_requests, repos=repos, validation_mode="manual"))

    assert result == "completed"
    assert [(request["repo"]["repo_id"], request["repo_path"]) for request in requests] == [
        ("frontend", "/repos/web"), ("backend", "/repos/api"),
    ]
    assert requests[1]["handoff"][0]["repo"] == "frontend"
    assert requests[1]["handoff"][0]["interface_notes"] == "frontend 对外行为已更新"
    assert [(request["repo_path"], request["allowed_paths"]) for request in apply_requests] == [
        ("/repos/web", ["src"]), ("/repos/api", ["src"]),
    ]
    assert [request["repo_path"] for request in release_requests] == ["/repos/web", "/repos/api"]
    modification = next(event for event in events if event["eventType"] == "ModificationCompleted")
    assert [item["repo"] for item in modification["payload"]["repoDiffs"]] == ["frontend", "backend"]
    completed = next(event for event in events if event["eventType"] == "ReleaseCompleted")
    assert [item["repo"] for item in completed["payload"]["repositories"]] == ["frontend", "backend"]


def test_gitlab_all_merged_completes_after_waiting_merge():
    events, result = asyncio.run(_run_workflow([
        ("owner_approved", "owner-approved-wi-1"),
        ("start_modification", "start-modification-wi-1"),
        ("patch_apply_approved", "patch-apply-approved-wi-1"),
    ], assignments=_gitlab_assignments(), repos=_gitlab_repos(), release_mode="gitlab",
       merge_states=["merged", "merged"]))

    types = [event["eventType"] for event in events]
    assert result == "completed"
    assert types.count("MergeRequestCreated") == 2
    assert types.count("MergeRequestMerged") == 2
    assert _business_types(events)[-1] == "ReleaseCompleted"
    assert next(event for event in events if event["eventType"] == "ReleaseCompleted")["payload"]["repositories"][0]["state"] == "merged"


def test_manual_gitlab_waits_for_evidence_before_ready_mr():
    counts: dict[str, int] = {}
    events, result = asyncio.run(_run_workflow([
        ("owner_approved", "owner-approved-wi-1"),
        ("start_modification", "start-modification-wi-1"),
        ("patch_apply_approved", "patch-apply-approved-wi-1"),
        ("validation_passed", {"signal_id": "validation-passed-wi-1", "evidence": "staging 通过"}),
        ("release_approved", "release-approved-wi-1"),
    ], assignments=_gitlab_assignments(), repos=_gitlab_repos(), release_mode="gitlab",
       validation_mode="manual", merge_states=["merged", "merged"], activity_counts=counts))

    types = _business_types(events)
    assert result == "completed"
    assert types.index("RepositoryReleasePrepared") < types.index("ValidationPassed") < types.index("MergeRequestCreated")
    assert counts["ready_merge_requests"] == 1
    completed = next(event for event in events if event["eventType"] == "TemporalActionCompleted"
                     and event["payload"]["action"] == "validation_passed")
    assert completed["payload"]["evidence"] == "staging 通过"


def test_gitlab_partial_merge_does_not_complete():
    events, result = asyncio.run(_run_workflow([
        ("owner_approved", "owner-approved-wi-1"),
        ("start_modification", "start-modification-wi-1"),
        ("patch_apply_approved", "patch-apply-approved-wi-1"),
        ("cancel_case", "cancel-case-wi-1"),
    ], assignments=_gitlab_assignments(), repos=_gitlab_repos(), release_mode="gitlab",
       merge_states=["merged", "opened"]))

    types = [event["eventType"] for event in events]
    assert result == "cancelled"
    assert types.count("MergeRequestMerged") == 1
    assert "ReleaseCompleted" not in types


def test_gitlab_closed_merge_request_blocks_until_human_decision():
    events, result = asyncio.run(_run_workflow([
        ("owner_approved", "owner-approved-wi-1"),
        ("start_modification", "start-modification-wi-1"),
        ("patch_apply_approved", "patch-apply-approved-wi-1"),
        ("cancel_case", "cancel-case-wi-1"),
    ], assignments=_gitlab_assignments(), repos=_gitlab_repos(), release_mode="gitlab",
       merge_states=["closed", "opened"]))

    closed = next(event for event in events if event["eventType"] == "MergeRequestClosed")
    assert result == "cancelled"
    assert closed["payload"]["reason"] == "mr_closed"
    assert "ReleaseCompleted" not in [event["eventType"] for event in events]


def test_gitlab_rework_runs_modification_again_before_review():
    counts: dict[str, int] = {}
    events, result = asyncio.run(_run_workflow([
        ("owner_approved", "owner-approved-wi-1"),
        ("start_modification", "start-modification-wi-1"),
        ("patch_apply_approved", "patch-apply-approved-wi-1"),
        ("rework", "rework-wi-1"),
        ("cancel_case", "cancel-case-wi-1"),
    ], assignments=_gitlab_assignments(), repos=_gitlab_repos(), release_mode="gitlab",
       merge_states=["opened", "opened"], activity_counts=counts))

    assert result == "cancelled"
    assert counts["fetch_context"] == 2
    assert counts["plan_execution"] == 2
    assert [event["eventType"] for event in events].count("ModificationCompleted") == 2


def test_gitlab_push_failure_blocks_with_repo_reason():
    events, result = asyncio.run(_run_workflow([
        ("owner_approved", "owner-approved-wi-1"),
        ("start_modification", "start-modification-wi-1"),
        ("patch_apply_approved", "patch-apply-approved-wi-1"),
        ("cancel_case", "cancel-case-wi-1"),
    ], assignments=_gitlab_assignments(), repos=_gitlab_repos(), release_mode="gitlab",
       publish_failure_repo="backend"))

    blocked = next(event for event in events if event["eventType"] == "WorkerBlocked")
    assert result == "cancelled"
    assert blocked["payload"]["reason"] == "mr_create_failed"
    assert blocked["payload"]["repo"] == "backend"


def test_gitlab_validation_failure_uses_existing_validation_failed_path():
    events, result = asyncio.run(_run_workflow([
        ("owner_approved", "owner-approved-wi-1"),
        ("start_modification", "start-modification-wi-1"),
        ("patch_apply_approved", "patch-apply-approved-wi-1"),
        ("cancel_case", "cancel-case-wi-1"),
    ], assignments=_gitlab_assignments(), repos=_gitlab_repos(), release_mode="gitlab",
       validation_failure_repo="frontend"))

    failed = next(event for event in events if event["eventType"] == "ValidationFailed")
    assert result == "cancelled"
    assert failed["payload"]["repo"] == "frontend"
    assert "MergeRequestCreated" not in [event["eventType"] for event in events]


def test_snapshot_unknown_assignment_blocks_with_unknown_role():
    events, result = asyncio.run(_run_workflow([
        ("owner_approved", "owner-approved-wi-1"),
        ("start_modification", "start-modification-wi-1"),
        ("cancel_case", "cancel-case-wi-1"),
    ], assignments=[{"role": "missing-agent"}], agent_config_snapshot=_agent_snapshot()))

    blocked = next(event for event in events if event["eventType"] == "WorkerBlocked")
    assert result == "cancelled"
    assert blocked["payload"]["reason"] == "unknown_role"
    assert blocked["payload"]["detail"] == "missing-agent"


def test_snapshot_replaces_legacy_execution_fields_in_activity_request():
    requests: list[dict] = []
    asyncio.run(_run_workflow([
        ("owner_approved", "owner-approved-wi-1"),
        ("start_modification", "start-modification-wi-1"),
        ("cancel_case", "cancel-case-wi-1"),
    ], assignments=[{"role": "frontend"}], observed_requests=requests,
       agent_config_snapshot=_agent_snapshot()))

    assert "agent_config_snapshot" in requests[0]
    assert "execution_provider" not in requests[0]
    assert "claude_max_turns" not in requests[0]
    assert "execution_timeout_seconds" not in requests[0]
    assert "api_key" not in str(requests[0]).lower()


async def _run_workflow(
    signals: list[tuple[str, str | dict]],
    planner_failure: bool = False,
    failure_mode: str | None = None,
    fetch_failures: int = 0,
    validation_result: dict | None = None,
    test_commands: list[str] | None = None,
    assignments: list[dict] | None = None,
    handoff_mode: str = "",
    observed_requests: list[dict] | None = None,
    observed_apply_requests: list[dict] | None = None,
    observed_release_requests: list[dict] | None = None,
    repo_path: str = "/tmp/repo",
    repos: list[dict] | None = None,
    verify_combined_diff: bool = False,
    agent_config_snapshot: dict | None = None,
    fail_role: str = "",
    fail_role_attempts: int = 0,
    activity_counts: dict[str, int] | None = None,
    release_mode: str = "local",
    validation_mode: str = "auto",
    merge_states: list[str] | None = None,
    publish_failure_repo: str = "",
    validation_failure_repo: str = "",
    execution_architecture: str = "legacy_planner_v1",
    observed_coding_requests: list[dict] | None = None,
    coding_attempt_failures: int = 0,
    patch_failures: int = 0,
    validation_activity_failures: int = 0,
    release_failures: int = 0,
) -> tuple[list[dict], str]:
    events: list[dict] = []
    execution_requests: list[dict] = []
    fetch_attempts = 0
    remaining_role_failures = fail_role_attempts
    remaining_coding_failures = coding_attempt_failures
    remaining_patch_failures = patch_failures
    remaining_validation_activity_failures = validation_activity_failures
    remaining_release_failures = release_failures

    @activity.defn(name="fetch_context")
    async def fake_fetch_context(request: dict) -> dict:
        nonlocal fetch_attempts
        if activity_counts is not None:
            activity_counts["fetch_context"] = activity_counts.get("fetch_context", 0) + 1
        assert request["work_item_id"] == "wi-1"
        fetch_attempts += 1
        if failure_mode == "fetch_context" or fetch_attempts <= fetch_failures:
            raise RuntimeError("context down")
        return {
            "system_id": request["system_id"],
            "manifest_id": "manifest-1",
            "approved_memories": [{"memory_id": "mem-1", "content": "遵守登录页约束"}],
        }

    @activity.defn(name="plan_execution")
    async def fake_plan_execution(request: dict) -> dict:
        if activity_counts is not None:
            activity_counts["plan_execution"] = activity_counts.get("plan_execution", 0) + 1
        assert request["system_id"] == "system-1"
        assert request["prd"]["goal"] == "把登录页加错误提示"
        assert request["context_manifest_id"] == "manifest-1"
        assert request["repo_summary"] == "repo summary"
        if planner_failure:
            raise RuntimeError("planner down")
        return ExecutionPlan(
            steps=["按验收标准修改: 错误密码时显示提示"],
            target_files=["src/login.tsx"],
            test_plan=["pytest"],
            risks=[],
            assignments=assignments or [],
        ).model_dump()

    @activity.defn(name="run_coding_attempt")
    async def fake_run_coding_attempt(request: dict) -> dict:
        nonlocal remaining_coding_failures
        if activity_counts is not None:
            activity_counts["run_coding_attempt"] = activity_counts.get("run_coding_attempt", 0) + 1
        if observed_coding_requests is not None:
            observed_coding_requests.append(request)
        if remaining_coding_failures > 0:
            remaining_coding_failures -= 1
            raise RuntimeError("coding supervisor down")
        repo_items = request["repos"]
        changes = []
        runs = []
        for index, repo in enumerate(repo_items):
            repo_id = repo["repo_id"]
            path = "web/app.ts" if repo_id == "frontend" else "api/app.py"
            changes.append({
                "repo": repo_id,
                "diff_patch": (
                    f"diff --git a/{path} b/{path}\n--- a/{path}\n+++ b/{path}\n"
                    f"@@ -1 +1 @@\n-old-{repo_id}\n+new-{repo_id}\n"
                ),
                "changed_paths": [path],
                "summary": f"{repo_id} 完成",
            })
            runs.append({
                "agent_id": f"agent-{index}",
                "agent_type": f"{repo_id}-dev",
                "repo": repo_id,
                "status": "completed",
            })
        return {
            "summary": "Supervisor 完成",
            "repo_changes": changes,
            "subagent_runs": runs,
            "token_usage": {"input_tokens": 200, "output_tokens": 50},
            "session_id": "session-team",
            "turns": 5,
            "execution_provider": "claude_sdk_supervisor",
        }

    @activity.defn(name="run_execution")
    async def fake_run_execution(request: dict) -> dict:
        nonlocal remaining_role_failures
        # 测试只关心 workflow 编排，执行结果用稳定 diff 固定。
        if failure_mode == "run_execution":
            raise RuntimeError("executor down")
        assert request["system_id"] == "system-1"
        if not assignments:
            assert request["execution_provider"] == "claude_sdk"
            assert request["claude_max_turns"] == 25
        execution_requests.append(request)
        if observed_requests is not None:
            observed_requests.append(request)
        if request["role_id"] == fail_role and remaining_role_failures > 0:
            remaining_role_failures -= 1
            raise RuntimeError(f"{fail_role} executor down")
        if handoff_mode == "scope":
            return ExecutionResult(summary="越界", diff_patch="diff --git a/api/app.py b/api/app.py\n",
                                   execution_provider="claude_sdk", engine="claude_sdk",
                                   blocked_reason="role_scope_violation", blocked_detail="api/app.py").model_dump()
        if assignments:
            frontend = request["role_id"] == "frontend"
            path = ("src/shared.py" if handoff_mode in {"conflict", "same-path-cross-repo"}
                    else ("web/app.ts" if frontend else "api/app.py"))
            engine = "claude_sdk" if frontend else "deepagents"
            old = "old-web" if frontend else "old-api"
            new = "new-web" if frontend else "new-api"
            return ExecutionResult(
                summary=f"{request['role_id']} 完成",
                diff_patch=(f"diff --git a/{path} b/{path}\n--- a/{path}\n+++ b/{path}\n"
                            f"@@ -1 +1 @@\n-{old}\n+{new}\n"),
                execution_provider=engine,
                engine=engine,
                role_id=request["role_id"],
                changed_paths=[path],
                interface_notes=f"{request['role_id']} 对外行为已更新",
                token_usage={"input_tokens": 10},
            ).model_dump()
        return ExecutionResult(
            summary=f"修改完成 {request['work_item_id']}",
            diff_patch="diff --git a/src/app.py b/src/app.py\n",
            execution_provider="claude_sdk",
            turns=3,
            token_usage={"input_tokens": 120, "output_tokens": 30},
        ).model_dump()

    @activity.defn(name="validate_plan_targets_activity")
    async def fake_validate_plan_targets(request: dict) -> None:
        if activity_counts is not None:
            activity_counts["validate_plan_targets"] = activity_counts.get("validate_plan_targets", 0) + 1
        assert request["repo_path"] == repo_path
        assert request["target_files"] == ["src/login.tsx"]

    @activity.defn(name="apply_patch_to_repo")
    async def fake_apply_patch_to_repo(request: dict) -> dict:
        nonlocal remaining_patch_failures
        # patch 应用活动在 workflow 测试中不碰真实仓库。
        if activity_counts is not None:
            activity_counts["apply_patch_to_repo"] = activity_counts.get("apply_patch_to_repo", 0) + 1
        if observed_apply_requests is not None:
            observed_apply_requests.append(request)
        if failure_mode == "apply_patch" or remaining_patch_failures > 0:
            remaining_patch_failures = max(0, remaining_patch_failures - 1)
            raise RuntimeError("git apply exploded")
        if verify_combined_diff:
            gate = validate_patch_paths(request["diff_patch"], ["web", "api"], [])
            assert gate.blocked is False
            subprocess.run(["git", "apply", "--check"], cwd=repo_path, input=request["diff_patch"],
                           text=True, check=True, capture_output=True)
        return PatchApplyResult().model_dump()

    @activity.defn(name="run_release")
    async def fake_run_release(request: dict) -> dict:
        nonlocal remaining_release_failures
        if activity_counts is not None:
            activity_counts["run_release"] = activity_counts.get("run_release", 0) + 1
        if failure_mode == "release" or remaining_release_failures > 0:
            remaining_release_failures = max(0, remaining_release_failures - 1)
            raise RuntimeError("release exploded")
        if observed_release_requests is not None:
            observed_release_requests.append(request)
        assert request["repo_path"] in ({item["local_path"] for item in repos} if repos else {repo_path})
        assert request["work_item_id"] == "wi-1"
        if verify_combined_diff:
            assert "web/app.ts" in request["diff_patch"] and "api/app.py" in request["diff_patch"]
        elif assignments or repos:
            assert "diff --git" in request["diff_patch"]
        else:
            assert request["diff_patch"] == "diff --git a/src/app.py b/src/app.py\n"
        return {"branch": "wi/wi-1", "commit_hash": "abc123", "push_failed": ""}

    @activity.defn(name="revert_patch")
    async def fake_revert_patch(request: dict) -> dict:
        assert request["repo_path"] == repo_path
        return {"changed_paths": ["src/app.py"], "failed": ""}

    @activity.defn(name="summarize_repo")
    async def fake_summarize_repo(request: dict) -> str:
        if activity_counts is not None:
            activity_counts["summarize_repo"] = activity_counts.get("summarize_repo", 0) + 1
        assert request["repo_path"] == repo_path
        return "repo summary"

    @activity.defn(name="run_validation")
    async def fake_run_validation(request: dict) -> dict:
        nonlocal remaining_validation_activity_failures
        if activity_counts is not None:
            activity_counts["run_validation"] = activity_counts.get("run_validation", 0) + 1
        if remaining_validation_activity_failures > 0:
            remaining_validation_activity_failures -= 1
            raise RuntimeError("validation unavailable")
        if not request["test_commands"]:
            raise AssertionError("empty test_commands should not trigger validation")
        expected_paths = {item["local_path"] for item in repos} if repos else {repo_path}
        assert request["repo_path"] in expected_paths
        return validation_result or {"passed": True, "commands": []}

    @activity.defn(name="publish_merge_request")
    async def fake_publish_merge_request(request: dict) -> dict:
        repo_id = request["repo"]["repo_id"]
        if repo_id == publish_failure_repo:
            raise RuntimeError("push failed")
        validation = ({"passed": False, "commands": [], "failed_command": "test", "stderr_tail": "failed"}
                      if repo_id == validation_failure_repo else {"passed": True, "commands": []})
        return {
            "repo": repo_id,
            "branch": "wi/wi-1",
            "commit_hash": f"commit-{repo_id}",
            "merge_request": {"repo": repo_id, "mr_iid": 1 if repo_id == "frontend" else 2,
                              "mr_url": f"https://gitlab/{repo_id}/mr", "state": "opened"},
            "validation": validation,
        }

    @activity.defn(name="check_merge_requests")
    async def fake_check_merge_requests(request: dict) -> list[dict]:
        states = merge_states or ["merged"] * len(request["merge_requests"])
        return [{**item, "state": states[index]} for index, item in enumerate(request["merge_requests"])]

    @activity.defn(name="ready_merge_requests")
    async def fake_ready_merge_requests(request: dict) -> list[dict]:
        if activity_counts is not None:
            activity_counts["ready_merge_requests"] = activity_counts.get("ready_merge_requests", 0) + 1
        return request["merge_requests"]

    @activity.defn(name="send_projection_event")
    async def fake_send_projection_event(event: dict) -> None:
        # 收集 worker 回写，断言事件顺序和幂等键。
        events.append(event)

    async with await WorkflowEnvironment.start_time_skipping(data_converter=pydantic_data_converter) as env:
        async with Worker(
            env.client,
            task_queue=TASK_QUEUE,
            workflows=[AsterismCaseWorkflow],
            activities=[
                fake_fetch_context,
                fake_summarize_repo,
                fake_plan_execution,
                fake_run_coding_attempt,
                fake_validate_plan_targets,
                fake_run_execution,
                fake_apply_patch_to_repo,
                fake_run_release,
                fake_revert_patch,
                fake_run_validation,
                fake_publish_merge_request,
                fake_check_merge_requests,
                fake_ready_merge_requests,
                fake_send_projection_event,
            ],
        ):
            handle = await env.client.start_workflow(
                AsterismCaseWorkflow.run,
                _case_input(test_commands, repo_path, agent_config_snapshot, repos, release_mode, validation_mode,
                            execution_architecture),
                id=f"case-{uuid4()}",
                task_queue=TASK_QUEUE,
            )
            for signal_name, signal_id in signals:
                await handle.signal(signal_name, signal_id)
            result = await asyncio.wait_for(handle.result(), timeout=8)
    return events, result


def _case_input(test_commands: list[str] | None = None, repo_path: str = "/tmp/repo",
                agent_config_snapshot: dict | None = None, repos: list[dict] | None = None,
                release_mode: str = "local", validation_mode: str = "auto",
                execution_architecture: str = "legacy_planner_v1") -> CaseInput:
    payload = dict(
        case_id="case-1",
        work_item_id="wi-1",
        prd_id="prd-1",
        system_id="system-1",
        prd=PrdSpec(
            title="登录页错误提示",
            goal="把登录页加错误提示",
            acceptance_criteria=["错误密码时显示提示"],
            draft_json={"goal": "把登录页加错误提示"},
        ),
        repo_path=repo_path,
        allowed_paths=["src"],
        forbidden_paths=["secrets"],
        test_commands=["pytest"] if test_commands is None else test_commands,
        repos=repos or [],
        release_mode=release_mode,
        validation_mode=validation_mode,
        mr_target_branch="main",
        execution_architecture=execution_architecture,
    )
    if agent_config_snapshot is None:
        payload.update(execution_provider="claude_sdk", claude_max_turns=25, execution_timeout_seconds=900)
    else:
        payload["agent_config_snapshot"] = agent_config_snapshot
    return CaseInput.model_validate(payload)


def _business_types(events: list[dict]) -> list[str]:
    return [event["eventType"] for event in events if event["eventType"] != "TemporalActionCompleted"]


def _agent_snapshot() -> dict:
    return {
        "model_profiles": [
            {"id": "mp-front", "model": "front-model"},
            {"id": "mp-back", "model": "back-model"},
        ],
        "agents": [
            {"name": "product", "kind": "builtin"},
            {"name": "planner", "kind": "builtin"},
            {"name": "developer", "kind": "builtin", "engine": "claude_sdk",
             "model_profile_ref": "mp-front", "max_turns": 25, "timeout_seconds": 900},
            {"name": "frontend", "kind": "custom", "engine": "claude_sdk",
             "model_profile_ref": "mp-front", "path_scope": ["web"], "timeout_seconds": 900},
            {"name": "backend", "kind": "custom", "engine": "deepagents",
             "model_profile_ref": "mp-back", "path_scope": ["api"], "timeout_seconds": 900},
        ],
    }


def _gitlab_repos() -> list[dict]:
    return [
        {"repo_id": "frontend", "name": "Web", "kind": "frontend", "clone_mode": "gitlab",
         "gitlab_project": "group/web", "default_branch": "main", "allowed_paths": ["web"],
         "forbidden_paths": [], "test_commands": ["test-web"]},
        {"repo_id": "backend", "name": "API", "kind": "backend", "clone_mode": "gitlab",
         "gitlab_project": "group/api", "default_branch": "main", "allowed_paths": ["api"],
         "forbidden_paths": [], "test_commands": ["test-api"]},
    ]


def _local_repos() -> list[dict]:
    """阶段恢复测试使用稳定本地路径，GitLab 临时克隆不会写入 Workflow 状态。"""

    return [
        {"repo_id": "frontend", "name": "Web", "kind": "frontend", "clone_mode": "local",
         "local_path": "/repos/web", "allowed_paths": ["web"], "forbidden_paths": [],
         "test_commands": ["test-web"]},
        {"repo_id": "backend", "name": "API", "kind": "backend", "clone_mode": "local",
         "local_path": "/repos/api", "allowed_paths": ["api"], "forbidden_paths": [],
         "test_commands": ["test-api"]},
    ]


def _gitlab_assignments() -> list[dict]:
    return [
        {"role": "frontend", "repo": "frontend", "scope_paths": ["web"], "step_refs": ["web"]},
        {"role": "backend", "repo": "backend", "scope_paths": ["api"], "step_refs": ["api"]},
    ]
