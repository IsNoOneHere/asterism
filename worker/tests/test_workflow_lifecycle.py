import asyncio
from uuid import uuid4
from types import SimpleNamespace

import pytest
from temporalio import activity
from temporalio.client import WorkflowFailureError
from temporalio.contrib.pydantic import pydantic_data_converter
from temporalio.exceptions import ApplicationError
from temporalio.testing import WorkflowEnvironment
from temporalio.worker import Worker

from asterism_worker.contracts import (
    ArtifactRef, ArtifactTransitionRequest, CaseInput, LifecycleStatus, PrdSpec, ProjectionResult,
)
from asterism_worker.workflows.coding import ExecutionPhase
from asterism_worker.workflows.lifecycle import ACTION_SPECS, AsterismCaseWorkflow


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
    assert calls == {"fetch_context": 2, "generate_coding_plan": 1, "run_coding_attempt": 1, "apply_patch_to_repo": 1,
                     "run_validation": 1, "run_release": 1}
    proposed = next(event for event in events if event["eventType"] == "CodingPlanProposed")
    assert "sessionId" not in proposed["payload"]
    assert proposed["artifactEvidence"]["evidenceType"] == "PlanningExecution"
    assert proposed["artifactEvidence"]["payload"]["sessionId"] == "plan-session-1"
    completed = next(event for event in events if event["eventType"] == "ModificationCompleted")
    assert not {"sessionId", "tokenUsage", "subagentRuns", "turns"} & completed["payload"].keys()
    assert completed["artifactEvidence"]["evidenceType"] == "CodingExecution"
    assert completed["artifactEvidence"]["payload"] == {
        "executionProvider": "claude_sdk_team",
        "sessionId": "session-1",
        "turns": 3,
        "tokenUsage": {"input_tokens": 100},
        "subagentRuns": [{
            "agent_id": "agent-main",
            "agent_type": "repo-main",
            "repo": "main",
            "status": "completed",
        }],
    }
    agent_stage = next(event for event in events if event["eventType"] == "AgentStageCompleted")
    assert not {"agentId", "engine", "tokenUsage"} & agent_stage["payload"].keys()


def test_selected_planning_version_becomes_the_next_coding_context():
    selected_product = _artifact_ref(
        "art-product-selected", "PRODUCT", 1, "APPROVED",
    )
    selected_planning = _artifact_ref(
        "art-plan-selected", "PLANNING", 1, "APPROVED",
        parent_artifact_id=selected_product.artifact_id,
    )
    coding_requests: list[dict] = []

    events, result, calls, _ = asyncio.run(_run_workflow([
        ("owner_approved", "approve-1"),
        ("artifact_version_selected", {
            "signal_id": "artifact-version-continue-select-plan-v1",
            "selected_type": "PLANNING",
            "selected_artifact": selected_planning.model_dump(),
            "product_artifact": selected_product.model_dump(),
            "planning_artifact": selected_planning.model_dump(),
            "requirement_manifest_id": "manifest-selected",
        }),
        ("patch_apply_approved", "patch-1"),
        ("release_approved", "release-1"),
    ], coding_requests=coding_requests, artifact_content_overrides={
        selected_product.artifact_id: {
            "title": "登录页错误提示",
            "goal": "把登录页加错误提示",
            "acceptanceCriteria": ["错误密码时显示提示"],
            "requirementManifestId": "manifest-selected",
        },
        selected_planning.artifact_id: {
            "planMarkdown": "# 历史批准计划",
            "baseRevisions": {"main": "base-1"},
        },
    }))

    assert result == "completed"
    assert calls.get("generate_coding_plan", 0) == 0
    assert calls["run_coding_attempt"] == 1
    assert coding_requests[0]["requirement_manifest_id"] == "manifest-selected"
    started = next(event for event in events if event["eventType"] == "CodingAttemptStarted")
    assert started["payload"]["productArtifactId"] == selected_product.artifact_id
    assert started["payload"]["planningArtifactId"] == selected_planning.artifact_id


def test_legacy_case_old_selection_only_syncs_route_without_starting_worker():
    selected_product = _artifact_ref(
        "art-product-selected", "PRODUCT", 1, "APPROVED",
    )
    selected_planning = _artifact_ref(
        "art-plan-selected", "PLANNING", 1, "APPROVED",
        parent_artifact_id=selected_product.artifact_id,
    )

    events, result, calls, _ = asyncio.run(_run_workflow([
        ("owner_approved", "approve-1"),
        ("artifact_version_selected", {
            "signal_id": "artifact-version-selected-existing-request",
            "selected_type": "PLANNING",
            "selected_artifact": selected_planning.model_dump(),
            "product_artifact": selected_product.model_dump(),
            "planning_artifact": selected_planning.model_dump(),
            "requirement_manifest_id": "manifest-selected",
        }),
        ("cancel_case", "cancel-1"),
    ], legacy_case=True))

    assert result == "cancelled"
    assert calls.get("generate_coding_plan", 0) == 0
    assert calls.get("run_coding_attempt", 0) == 0
    assert "CodingAttemptStarted" not in _business_types(events)


def test_legacy_prd_contract_accepts_pre_artifact_case_input():
    case_input = _legacy_case_input("claude_sdk_team", 5, "local")

    restored = CaseInput.model_validate(case_input.model_dump())

    assert restored.prd.goal == "把登录页加错误提示"
    assert restored.prd.product_artifact is None


def test_legacy_planning_history_keeps_original_activity_sequence():
    plan_requests: list[dict] = []

    events, result, calls, _ = asyncio.run(_run_workflow([
        ("owner_approved", "approve-1"),
        ("start_modification", "start-1"),
        ("coding_plan_rejected", {
            "signal_id": "plan-reject-1",
            "note": "按原需求重新规划",
            "actor_id": "owner-1",
        }),
        ("cancel_case", "cancel-1"),
    ], legacy_case=True, plan_requests=plan_requests))

    assert result == "cancelled"
    assert calls["generate_coding_plan"] == 2
    assert [event for event in _business_types(events) if event == "CodingPlanProposed"] == [
        "CodingPlanProposed",
        "CodingPlanProposed",
    ]
    assert set(plan_requests[1]["previous_plan"]) == {
        "plan_markdown", "revision", "session_id", "base_revisions",
    }


def test_workflow_applies_exact_repo_patch_without_whitespace_cleanup():
    patch = (
        "diff --git a/src/app.py b/src/app.py\n"
        "--- a/src/app.py\n"
        "+++ b/src/app.py\n"
        "@@ -1,3 +1,4 @@\n"
        " old\n"
        "+new\n"
        " tail\n"
        " \n"
    )
    apply_requests: list[dict] = []
    events, result, _, _ = asyncio.run(_run_workflow([
        ("owner_approved", "approve-1"),
        ("start_modification", "start-1"),
        ("coding_plan_approved", "plan-approve-1"),
        ("patch_apply_approved", "patch-1"),
        ("release_approved", "release-1"),
    ], coding_patch=patch, apply_requests=apply_requests))

    assert result == "completed"
    completed = next(event for event in events if event["eventType"] == "ModificationCompleted")
    assert completed["payload"]["repoDiffs"] == [{
        "repo": "main",
        "diffPatch": patch,
        "changedPaths": ["src/app.py"],
        "summary": "仓库修改完成",
    }]
    assert apply_requests[0]["diff_patch"] == patch


def test_code_confirmation_applies_the_selected_approved_coding_artifact():
    selected_patch = (
        "diff --git a/src/selected.py b/src/selected.py\n"
        "--- a/src/selected.py\n"
        "+++ b/src/selected.py\n"
        "@@ -1 +1 @@\n-old\n+selected\n"
    )
    product = _artifact_ref("art-product-1", "PRODUCT", 1, "APPROVED")
    planning = _artifact_ref(
        "art-plan-1", "PLANNING", 1, "APPROVED",
        parent_artifact_id=product.artifact_id,
    )
    selected_coding = _artifact_ref(
        "art-code-selected", "CODING", 7, "APPROVED",
        parent_artifact_id=planning.artifact_id,
    )
    apply_requests: list[dict] = []

    events, result, _, _ = asyncio.run(_run_workflow([
        ("owner_approved", "approve-1"),
        ("start_modification", "start-1"),
        ("coding_plan_approved", "plan-approve-1"),
        ("patch_apply_approved", {
            "signal_id": "patch-selected-1",
            "artifact_ref": selected_coding.model_dump(),
            "selected_type": "CODING",
            "selected_artifact": selected_coding.model_dump(),
            "product_artifact": product.model_dump(),
            "planning_artifact": planning.model_dump(),
            "coding_artifact": selected_coding.model_dump(),
            "requirement_manifest_id": "manifest-1",
        }),
        ("release_approved", "release-1"),
    ], apply_requests=apply_requests, artifact_content_overrides={
        selected_coding.artifact_id: {
            "summary": "选中的历史代码版本",
            "repoChanges": [{
                "repo": "main",
                "diffPatch": selected_patch,
                "changedPaths": ["src/selected.py"],
                "summary": "应用选中版本",
            }],
            "executionOutcome": {"status": "completed", "blockers": []},
            "baseRevisions": {"main": "base-1"},
        },
    }))

    assert result == "completed"
    assert apply_requests[0]["diff_patch"] == selected_patch
    validation = next(event for event in events if event["eventType"] == "ValidationPassed")
    assert validation.get("artifactTransition") is None


def test_patch_blocked_can_retry_exact_artifact_without_rerunning_coding():
    patch = (
        "diff --git a/src/app.py b/src/app.py\n"
        "--- a/src/app.py\n"
        "+++ b/src/app.py\n"
        "@@ -1,2 +1,3 @@\n"
        " old\n"
        "+new\n"
        " \n"
    )
    apply_requests: list[dict] = []
    events, result, calls, _ = asyncio.run(_run_workflow([
        ("owner_approved", "approve-1"),
        ("start_modification", "start-1"),
        ("coding_plan_approved", "plan-approve-1"),
        ("patch_apply_approved", "patch-1"),
        ("retry_current_phase", {"signal_id": "retry-1", "retry_phase": "patch"}),
        ("release_approved", "release-1"),
    ], coding_patch=patch, apply_requests=apply_requests, patch_blocked_once=True))

    assert result == "completed"
    assert calls["run_coding_attempt"] == 1
    assert [request["diff_patch"] for request in apply_requests] == [patch, patch]
    assert _business_types(events).count("ModificationCompleted") == 1
    assert _contains_in_order(_business_types(events), [
        "PatchApplyBlocked", "ReworkStarted", "ModificationCheckpointRestored", "PatchApplied",
    ])
    assert sum(
        event.get("artifactTransition", {}).get("kind") == "ProposeCodingArtifact"
        for event in events
    ) == 1
    blocked = next(event for event in events if event["eventType"] == "PatchApplyBlocked")
    assert blocked["payload"]["failedPhase"] == "patch"


def test_validation_retry_restores_exact_coding_artifact_without_new_proposal():
    events, result, calls, _ = asyncio.run(_run_workflow([
        ("owner_approved", "approve-1"),
        ("start_modification", "start-1"),
        ("coding_plan_approved", "plan-approve-1"),
        ("patch_apply_approved", "patch-1"),
        ("retry_current_phase", {"signal_id": "retry-1", "retry_phase": "validation"}),
        ("release_approved", "release-1"),
    ], validation_failures=1))

    assert result == "completed"
    assert calls["run_coding_attempt"] == 1
    assert calls["run_validation"] == 2
    assert _business_types(events).count("ModificationCompleted") == 1
    assert _business_types(events).count("ValidationPassed") == 1
    assert _contains_in_order(_business_types(events), [
        "WorkerBlocked", "ReworkStarted", "ModificationCheckpointRestored",
        "PatchCheckpointRestored", "ValidationPassed",
    ])
    assert sum(
        event.get("artifactTransition", {}).get("kind") == "ProposeCodingArtifact"
        for event in events
    ) == 1
    blocked = next(
        event for event in events
        if event["eventType"] == "WorkerBlocked"
        and event["payload"].get("failedPhase") == "validation"
    )
    assert blocked["payload"]["artifactResultVersion"] == 1
    assert blocked["payload"]["artifactRef"]["artifactType"] == "VALIDATION"
    assert blocked["artifactEvidence"].get("artifact") is None
    assert blocked["artifactEvidence"].get("transitionId") is None


def test_failed_validation_artifact_is_superseded_by_retry_on_same_coding_head():
    events, result, calls, _ = asyncio.run(_run_workflow([
        ("owner_approved", "approve-1"),
        ("start_modification", "start-1"),
        ("coding_plan_approved", "plan-approve-1"),
        ("patch_apply_approved", "patch-1"),
        ("validation_retry", {"signal_id": "validation-retry-1"}),
        ("release_approved", "release-1"),
    ], validation_rejections=1))

    assert result == "completed"
    assert calls["run_coding_attempt"] == 1
    assert calls["run_validation"] == 2
    validation_events = [
        event for event in events
        if event["eventType"] in {"ValidationFailed", "ValidationPassed"}
    ]
    failed_ref = validation_events[0]["payload"]["artifactRef"]
    passed_ref = validation_events[1]["payload"]["artifactRef"]
    assert failed_ref["artifactType"] == "VALIDATION"
    assert passed_ref["artifactType"] == "VALIDATION"
    assert passed_ref["parentArtifactId"] == failed_ref["parentArtifactId"]
    assert passed_ref["supersedesArtifactId"] == failed_ref["artifactId"]
    assert all(event.get("artifactTransition") is None for event in validation_events)
    assert _contains_in_order(_business_types(events), [
        "ValidationFailed", "ReworkStarted", "ModificationCheckpointRestored",
        "PatchApplied", "ValidationPassed",
    ])


def test_explicit_skip_materializes_skipped_validation_before_release():
    events, result, calls, _ = asyncio.run(_run_workflow([
        ("owner_approved", "approve-1"),
        ("start_modification", "start-1"),
        ("coding_plan_approved", "plan-approve-1"),
        ("patch_apply_approved", "patch-1"),
        ("release_approved", "release-1"),
    ], validation_mode="skip"))

    assert result == "completed"
    assert calls.get("run_validation", 0) == 0
    validation = next(event for event in events if event["eventType"] == "ValidationPassed")
    assert validation["payload"]["validationMode"] == "SKIP"
    assert validation["payload"]["skipped"] is True
    assert validation["payload"]["artifactRef"]["artifactType"] == "VALIDATION"
    release = next(event for event in events if event["eventType"] == "ReleaseCompleted")
    assert release["payload"]["validationArtifactId"] == validation["payload"]["artifactId"]


def test_validation_failure_can_rework_coding_with_a_new_result_chain():
    events, result, calls, _ = asyncio.run(_run_workflow([
        ("owner_approved", "approve-1"),
        ("start_modification", "start-1"),
        ("coding_plan_approved", "plan-approve-1"),
        ("patch_apply_approved", "patch-1"),
        ("validation_rework_coding", {
            "signal_id": "validation-rework-coding-1",
            "note": "修复验证失败的实现",
        }),
        ("patch_apply_approved", "patch-2"),
        ("release_approved", "release-1"),
    ], validation_rejections=1))

    assert result == "completed"
    assert calls["run_coding_attempt"] == 2
    assert calls["run_validation"] == 2
    coding_refs = [
        event["payload"]["artifactRef"] for event in events
        if event.get("artifactTransition", {}).get("kind") == "ProposeCodingArtifact"
    ]
    assert coding_refs[1]["supersedesArtifactId"] == coding_refs[0]["artifactId"]
    validation_refs = [
        event["payload"]["artifactRef"] for event in events
        if event["eventType"] in {"ValidationFailed", "ValidationPassed"}
    ]
    assert validation_refs[1]["parentArtifactId"] == coding_refs[1]["artifactId"]


def test_validation_failure_can_rework_planning_and_rebuild_downstream_chain():
    events, result, calls, _ = asyncio.run(_run_workflow([
        ("owner_approved", "approve-1"),
        ("start_modification", "start-1"),
        ("coding_plan_approved", "plan-approve-1"),
        ("patch_apply_approved", "patch-1"),
        ("validation_rework_planning", {
            "signal_id": "validation-rework-planning-1",
            "note": "验证表明计划需要调整",
        }),
        ("coding_plan_approved", "plan-approve-2"),
        ("patch_apply_approved", "patch-2"),
        ("release_approved", "release-1"),
    ], validation_rejections=1))

    assert result == "completed"
    assert calls["generate_coding_plan"] == 2
    assert calls["run_coding_attempt"] == 2
    planning_refs = [
        event["payload"]["artifactRef"] for event in events
        if event.get("artifactTransition", {}).get("kind") == "ProposePlanningArtifact"
    ]
    assert planning_refs[1]["supersedesArtifactId"] == planning_refs[0]["artifactId"]
    latest_coding = [
        event["payload"]["artifactRef"] for event in events
        if event.get("artifactTransition", {}).get("kind") == "ProposeCodingArtifact"
    ][-1]
    assert latest_coding["parentArtifactId"] == planning_refs[1]["artifactId"]


def test_validation_passed_can_rework_coding_after_reverting_local_patch():
    events, result, calls, _ = asyncio.run(_run_workflow([
        ("owner_approved", "approve-1"),
        ("start_modification", "start-1"),
        ("coding_plan_approved", "plan-approve-1"),
        ("patch_apply_approved", "patch-1"),
        ("validation_rework_coding", {
            "signal_id": "validation-passed-rework-coding-1",
            "note": "验证通过后仍发现业务实现不完整",
        }),
        ("patch_apply_approved", "patch-2"),
        ("release_approved", "release-1"),
    ]))

    assert result == "completed"
    assert calls["revert_patch"] == 1
    assert calls["run_coding_attempt"] == 2
    assert calls["run_validation"] == 2
    coding_refs = [
        event["payload"]["artifactRef"] for event in events
        if event.get("artifactTransition", {}).get("kind") == "ProposeCodingArtifact"
    ]
    assert coding_refs[1]["supersedesArtifactId"] == coding_refs[0]["artifactId"]
    validation_refs = [
        event["payload"]["artifactRef"] for event in events
        if event["eventType"] == "ValidationPassed"
    ]
    assert [reference["version"] for reference in validation_refs] == [1, 2]
    assert validation_refs[1]["parentArtifactId"] == coding_refs[1]["artifactId"]


def test_validation_passed_can_rework_planning_after_reverting_local_patch():
    events, result, calls, _ = asyncio.run(_run_workflow([
        ("owner_approved", "approve-1"),
        ("start_modification", "start-1"),
        ("coding_plan_approved", "plan-approve-1"),
        ("patch_apply_approved", "patch-1"),
        ("validation_rework_planning", {
            "signal_id": "validation-passed-rework-planning-1",
            "note": "验证结果说明原计划边界不完整",
        }),
        ("coding_plan_approved", "plan-approve-2"),
        ("patch_apply_approved", "patch-2"),
        ("release_approved", "release-1"),
    ]))

    assert result == "completed"
    assert calls["revert_patch"] == 1
    assert calls["generate_coding_plan"] == 2
    assert calls["run_coding_attempt"] == 2
    planning_refs = [
        event["payload"]["artifactRef"] for event in events
        if event.get("artifactTransition", {}).get("kind") == "ProposePlanningArtifact"
    ]
    assert planning_refs[1]["supersedesArtifactId"] == planning_refs[0]["artifactId"]


def test_gitlab_validation_passed_rework_does_not_revert_remote_patch():
    _, result, calls, _ = asyncio.run(_run_workflow([
        ("owner_approved", "approve-1"),
        ("start_modification", "start-1"),
        ("coding_plan_approved", "plan-approve-1"),
        ("patch_apply_approved", "patch-1"),
        ("validation_passed", "manual-validation-1"),
        ("validation_rework_coding", {
            "signal_id": "gitlab-validation-rework-1",
            "note": "验证通过后补充业务修订",
        }),
        ("cancel_case", "cancel-after-gitlab-rework"),
    ], release_mode="gitlab", validation_mode="manual"))

    assert result == "cancelled"
    assert calls.get("revert_patch", 0) == 0
    assert calls["publish_merge_request"] == 1


def test_validation_passed_rework_blocks_when_local_patch_cannot_be_reverted():
    events, result, calls, _ = asyncio.run(_run_workflow([
        ("owner_approved", "approve-1"),
        ("start_modification", "start-1"),
        ("coding_plan_approved", "plan-approve-1"),
        ("patch_apply_approved", "patch-1"),
        ("validation_rework_coding", {
            "signal_id": "validation-rework-revert-failed-1",
            "note": "重新实现",
        }),
        ("cancel_case", "cancel-after-revert-failure"),
    ], revert_failure="working tree changed"))

    assert result == "cancelled"
    assert calls["revert_patch"] == 1
    assert calls["run_coding_attempt"] == 1
    blocked = next(event for event in events if event["eventType"] == "WorkerBlocked")
    assert blocked["payload"]["reason"] == "patch_revert_failed"


def test_release_retry_restores_approved_coding_artifact_without_reapproval():
    events, result, calls, _ = asyncio.run(_run_workflow([
        ("owner_approved", "approve-1"),
        ("start_modification", "start-1"),
        ("coding_plan_approved", "plan-approve-1"),
        ("patch_apply_approved", "patch-1"),
        ("release_approved", "release-1"),
        ("release_retry", {"signal_id": "retry-1"}),
    ], release_failures=2))

    assert result == "completed"
    assert calls["run_coding_attempt"] == 1
    assert calls["run_release"] == 3
    assert _business_types(events).count("ModificationCompleted") == 1
    assert _business_types(events).count("ValidationPassed") == 1
    assert _contains_in_order(_business_types(events), [
        "WorkerBlocked", "ReworkStarted", "ModificationCheckpointRestored",
        "PatchCheckpointRestored", "ValidationCheckpointRestored", "ReleaseCompleted",
    ])
    assert sum(
        event.get("artifactTransition", {}).get("kind") == "ApproveCodingArtifact"
        for event in events
    ) == 1


def test_release_failure_can_revalidate_without_reapplying_patch():
    events, result, calls, _ = asyncio.run(_run_workflow([
        ("owner_approved", "approve-1"),
        ("start_modification", "start-1"),
        ("coding_plan_approved", "plan-approve-1"),
        ("patch_apply_approved", "patch-1"),
        ("release_approved", "release-1"),
        ("release_revalidate", {"signal_id": "release-revalidate-1"}),
        ("release_approved", "release-2"),
    ], release_failures=2))

    assert result == "completed"
    assert calls["apply_patch_to_repo"] == 1
    assert calls["run_validation"] == 2
    assert calls["run_release"] == 3
    assert _contains_in_order(_business_types(events), [
        "WorkerBlocked", "ReworkStarted", "ModificationCheckpointRestored",
        "PatchCheckpointRestored", "ValidationPassed",
    ])
    validation_refs = [
        event["payload"]["artifactRef"] for event in events
        if event["eventType"] == "ValidationPassed"
    ]
    assert validation_refs[1]["supersedesArtifactId"] == validation_refs[0]["artifactId"]


def test_release_failure_can_rework_coding_and_replace_validation_result():
    events, result, calls, _ = asyncio.run(_run_workflow([
        ("owner_approved", "approve-1"),
        ("start_modification", "start-1"),
        ("coding_plan_approved", "plan-approve-1"),
        ("patch_apply_approved", "patch-1"),
        ("release_approved", "release-1"),
        ("release_rework_coding", {
            "signal_id": "release-rework-coding-1",
            "note": "发布检查发现实现需要调整",
        }),
        ("patch_apply_approved", "patch-2"),
        ("release_approved", "release-2"),
    ], release_failures=2))

    assert result == "completed"
    assert calls["run_coding_attempt"] == 2
    assert calls["run_validation"] == 2
    assert calls["run_release"] == 3
    coding_refs = [
        event["payload"]["artifactRef"] for event in events
        if event.get("artifactTransition", {}).get("kind") == "ProposeCodingArtifact"
    ]
    assert coding_refs[1]["supersedesArtifactId"] == coding_refs[0]["artifactId"]
    latest_validation = [
        event["payload"]["artifactRef"] for event in events
        if event["eventType"] == "ValidationPassed"
    ][-1]
    assert latest_validation["parentArtifactId"] == coding_refs[1]["artifactId"]


def test_terminal_workflow_rejects_removed_architecture():
    with pytest.raises(WorkflowFailureError) as failure:
        asyncio.run(_run_workflow([], execution_architecture="legacy_planner_v1"))
    assert "claude_sdk_team" in str(failure.value.__cause__)


def test_missing_plan_text_is_not_retried_by_temporal():
    events, result, calls, _ = asyncio.run(_run_workflow([
        ("owner_approved", "approve-1"),
        ("start_modification", "start-1"),
        ("cancel_case", "cancel-1"),
    ], plan_result_missing=True))

    assert result == "cancelled"
    assert calls["generate_coding_plan"] == 1
    blocked = next(event for event in events if event["eventType"] == "WorkerBlocked")
    assert blocked["payload"]["reason"] == "coding_plan_failed"
    assert blocked["payload"]["failedPhase"] == "planning"


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
    assert calls["fetch_context"] == 3
    assert calls["run_coding_attempt"] == 3
    assert requests[2]["feedback"] == "重试补充：只修复登录提示"
    blocked = next(event for event in events if event["eventType"] == "WorkerBlocked")
    assert blocked.get("artifactTransition") is None
    assert blocked["artifactEvidence"]["artifact"]["artifactType"] == "PLANNING"
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
    assert plan_requests[1]["previous_plan"]["plan_markdown"] == "# 第 1 版计划"
    assert coding_requests[0]["approved_plan"]["revision"] == 2
    assert coding_requests[0]["resume_session_id"] == "plan-session-2"
    proposed = [event["payload"] for event in events if event["eventType"] == "CodingPlanProposed"]
    assert [payload["planRevision"] for payload in proposed] == [1, 2]
    assert proposed[1]["planMarkdown"] == "# 第 2 版计划"
    assert "tasks" not in proposed[1]


def test_planning_and_coding_feedback_only_use_persisted_artifact_snapshot():
    workflow_instance = AsterismCaseWorkflow()
    workflow_instance.rework_feedback = "Temporal 内存中的旧意见"
    snapshot = SimpleNamespace(feedback_notes=["Artifact Transition 中的正式意见"])

    assert workflow_instance._planning_feedback(snapshot) == "Artifact Transition 中的正式意见"
    assert workflow_instance._artifact_feedback(snapshot) == "Artifact Transition 中的正式意见"


def test_workflow_rejects_mismatched_artifact_transition_result():
    workflow_instance = AsterismCaseWorkflow()
    product = _artifact_ref("art-product-1", "PRODUCT", 1, "APPROVED")
    planning = _artifact_ref(
        "art-plan-1", "PLANNING", 1, "PROPOSED",
        parent_artifact_id=product.artifact_id,
    )
    request = ArtifactTransitionRequest(
        kind="ProposePlanningArtifact",
        transition_id="transition-expected",
        parent=product,
        content={"planMarkdown": "# 计划"},
    )
    result = ProjectionResult(
        event={},
        artifact_ref=planning,
        transition={"transitionId": "transition-other"},
    )

    with pytest.raises(ApplicationError, match="transitionId 不一致"):
        workflow_instance._accept_transition(request, result)


def test_legacy_in_flight_release_keeps_exact_coding_artifact_contract():
    workflow_instance = AsterismCaseWorkflow()
    product = _artifact_ref("art-product-1", "PRODUCT", 1, "APPROVED")
    planning = _artifact_ref(
        "art-plan-1", "PLANNING", 1, "APPROVED", parent_artifact_id=product.artifact_id,
    )
    coding = _artifact_ref(
        "art-code-1", "CODING", 1, "APPROVED", parent_artifact_id=planning.artifact_id,
    )
    workflow_instance.case_input = _case_input("claude_sdk_team", 5, "local")
    workflow_instance.product_artifact = product
    workflow_instance.planning_artifact = planning
    workflow_instance.coding_artifact = coding
    workflow_instance.effective_heads = {"PRODUCT": product, "PLANNING": planning, "CODING": coding}
    workflow_instance.artifact_mode = True
    workflow_instance.result_artifact_mode = False
    context = {
        "artifact_ref": coding.model_dump(),
        "selected_type": "CODING",
        "selected_artifact": coding.model_dump(),
        "product_artifact": product.model_dump(),
        "planning_artifact": planning.model_dump(),
        "coding_artifact": coding.model_dump(),
        "requirement_manifest_id": "manifest-1",
    }

    assert workflow_instance._validate_action_artifact("release_approved", context) is True


def test_pre_v22_validation_rework_keeps_exact_coding_artifact_contract():
    workflow_instance = AsterismCaseWorkflow()
    product = _artifact_ref("art-product-1", "PRODUCT", 1, "APPROVED")
    planning = _artifact_ref(
        "art-plan-1", "PLANNING", 1, "APPROVED", parent_artifact_id=product.artifact_id,
    )
    coding = _artifact_ref(
        "art-code-1", "CODING", 1, "APPROVED", parent_artifact_id=planning.artifact_id,
    )
    workflow_instance.case_input = _case_input("claude_sdk_team", 5, "local")
    workflow_instance.product_artifact = product
    workflow_instance.planning_artifact = planning
    workflow_instance.coding_artifact = coding
    workflow_instance.effective_heads = {"PRODUCT": product, "PLANNING": planning, "CODING": coding}
    workflow_instance.artifact_mode = True
    workflow_instance.result_artifact_mode = False

    for action in ("validation_rework_coding", "validation_rework_planning"):
        context = {
            "artifact_ref": coding.model_dump(),
            "selected_type": "CODING",
            "selected_artifact": coding.model_dump(),
            "product_artifact": product.model_dump(),
            "planning_artifact": planning.model_dump(),
            "coding_artifact": coding.model_dump(),
            "requirement_manifest_id": "manifest-1",
        }
        assert workflow_instance._validate_action_artifact(action, context) is True


def test_new_manual_validation_pass_never_sends_a_second_coding_transition():
    workflow_instance = AsterismCaseWorkflow()
    workflow_instance.case_input = _case_input("claude_sdk_team", 5, "local")
    workflow_instance.result_artifact_mode = True
    workflow_instance.state.status = LifecycleStatus.patch_applied
    workflow_instance.coding_artifact = _artifact_ref(
        "art-code-1", "CODING", 1, "APPROVED", parent_artifact_id="art-plan-1",
    )
    emitted: list[tuple[str | None, ArtifactTransitionRequest | None]] = []

    async def emit(event_type, _signal_id, _payload, *, artifact_transition=None, **_kwargs):
        emitted.append((event_type, artifact_transition))

    workflow_instance._emit = emit
    accepted = asyncio.run(workflow_instance._transition_action(
        "validation_passed", ACTION_SPECS["validation_passed"], "manual-validation-1", {},
    ))

    assert accepted is True
    assert emitted == [("ValidationPassed", None)]


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
    assert calls["fetch_context"] == 3
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
    assert {
        "note": "错误提示需要放在输入框下方",
        "revision": 1,
        "requestedBy": "owner-1",
        "phase": "review",
        "revisionMode": "incremental",
        "diffSummary": [{"repo": "main", "summary": "仓库修改完成", "changedPaths": ["src/app.py"]}],
    }.items() <= requested.items()
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
    assert {
        "reason": "revision_limit_reached",
        "detail": "已达到最大修订轮次 1",
        "revision": 1,
        "revisionMode": "incremental",
        "maxRevisions": 1,
        "phase": "review",
        "note": "第二轮意见",
    }.items() <= blocked["payload"].items()


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
        ("patch_apply_approved", "patch-3"),
        ("release_approved", "release-1"),
    ], max_revisions=1, plan_requests=plan_requests, coding_requests=coding_requests))

    assert result == "completed"
    assert calls["run_coding_attempt"] == 3
    modifications = [event for event in events if event["eventType"] == "ModificationCompleted"]
    assert modifications[-1]["payload"]["revision"] == 0
    assert modifications[-1]["payload"]["revisionMode"] == "full"
    assert [event["payload"]["revision"] for event in events if event["eventType"] == "RevisionRequested"] == [1]
    assert len(plan_requests) == 1
    assert coding_requests[-1]["previous_candidate"] == []
    assert coding_requests[-1]["resume_session_id"] == ""
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
        ("release_rework_coding", {
            "signal_id": "review-1", "note": "MR 中的接口字段需要保持兼容", "actor_id": "owner-1",
        }),
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
    assert {
        "reason": "attempt_interrupted",
        "detail": "实现方向偏离已批准计划",
        "failedPhase": "coding",
        "interruptedBy": "owner-1",
    }.items() <= blocked["payload"].items()


def test_context_stale_requires_explicit_manifest_refresh_and_replans_with_one_snapshot():
    coding_requests: list[dict] = []
    plan_requests: list[dict] = []
    events, result, calls, _ = asyncio.run(_run_workflow([
        ("owner_approved", "approve-1"),
        ("start_modification", "start-1"),
        ("rework_with_latest_context", {
            "signal_id": "refresh-context-1", "requirement_manifest_id": "manifest-2",
            "product_artifact": _artifact_ref(
                "art-product-2", "PRODUCT", 2, "APPROVED",
                supersedes_artifact_id="art-product-1",
            ).model_dump(),
        }),
        ("coding_plan_approved", "plan-approve-1"),
        ("patch_apply_approved", "patch-1"),
        ("release_approved", "release-1"),
    ], context_stale_once=True, coding_requests=coding_requests, plan_requests=plan_requests))

    assert result == "completed"
    assert calls["fetch_context"] == 3
    blocked = next(event for event in events if event["eventType"] == "WorkerBlocked")
    assert blocked["payload"]["reason"] == "context_stale"
    assert plan_requests[0]["requirement_manifest_id"] == "manifest-2"
    assert coding_requests[0]["requirement_manifest_id"] == "manifest-2"
    started = next(event for event in events if event["eventType"] == "CodingAttemptStarted")
    assert started["payload"]["requirementManifestId"] == "manifest-2"


def test_product_refresh_after_approved_plan_explicitly_supersedes_previous_planning():
    events, result, calls, _ = asyncio.run(_run_workflow([
        ("owner_approved", "approve-1"),
        ("start_modification", "start-1"),
        ("coding_plan_approved", "plan-approve-1"),
        ("rework_with_latest_context", {
            "signal_id": "refresh-context-1", "requirement_manifest_id": "manifest-2",
            "product_artifact": _artifact_ref(
                "art-product-2", "PRODUCT", 2, "APPROVED",
                supersedes_artifact_id="art-product-1",
            ).model_dump(),
        }),
        ("coding_plan_approved", "plan-approve-2"),
        ("patch_apply_approved", "patch-1"),
        ("release_approved", "release-1"),
    ], context_stale_at_call=2))

    assert result == "completed"
    assert calls["fetch_context"] == 4
    blocked = next(event for event in events if event["eventType"] == "WorkerBlocked")
    assert blocked["payload"]["reason"] == "context_stale"
    proposed = [
        event for event in events if event["eventType"] == "CodingPlanProposed"
    ]
    assert len(proposed) == 2
    supersedes = proposed[1]["artifactTransition"]["supersedes"]
    assert supersedes["artifactId"] == "art-plan-1"
    assert supersedes["status"] == "SUPERSEDED"


def test_system_blocked_with_partial_diff_never_completes_modification_until_retry():
    requests: list[dict] = []
    blocked = {
        "status": "blocked",
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
    assert first_blocked["artifactTransition"]["kind"] == "ProposeCodingArtifact"
    assert not {"sessionId", "tokenUsage", "subagentRuns", "turns"} & first_blocked["payload"].keys()
    assert first_blocked["artifactEvidence"]["payload"]["sessionId"] == "session-1"
    assert first_blocked["artifactEvidence"]["payload"]["tokenUsage"] == {"input_tokens": 100}
    assert first_blocked["artifactEvidence"]["payload"]["subagentRuns"][0]["agent_id"] == "agent-main"
    assert first_blocked["payload"]["partialChanges"] == [{
        "repo": "main", "changedPaths": ["src/app.py"],
    }]
    assert requests[1]["previous_candidate"][0]["diff_patch"].startswith("diff --git")
    assert requests[1]["resume_session_id"] == "session-1"


def test_non_git_patch_records_evidence_without_creating_coding_artifact():
    events, result, calls, _ = asyncio.run(_run_workflow([
        ("owner_approved", "approve-1"),
        ("start_modification", "start-1"),
        ("coding_plan_approved", "plan-approve-1"),
        ("cancel_case", "cancel-1"),
    ], coding_patch="updated src/app.py"))

    assert result == "cancelled"
    assert calls["run_coding_attempt"] == 1
    blocked = next(event for event in events if event["eventType"] == "WorkerBlocked")
    assert blocked["payload"]["reason"] == "coding_attempt_failed"
    assert blocked.get("artifactTransition") is None
    assert blocked["artifactEvidence"]["artifact"]["artifactType"] == "PLANNING"
    assert blocked["artifactEvidence"]["payload"]["sessionId"] == "session-1"
    assert blocked["artifactEvidence"]["payload"]["turns"] == 3
    assert "ModificationCompleted" not in _business_types(events)


def test_phase_recovery_rebuilds_missing_workflow_candidate_from_coding_artifact(monkeypatch):
    workflow_instance = AsterismCaseWorkflow()
    workflow_instance.case_input = _case_input("claude_sdk_team", 5, "local")
    workflow_instance.product_artifact = _artifact_ref(
        "art-product-1", "PRODUCT", 1, "APPROVED",
    )
    workflow_instance.planning_artifact = _artifact_ref(
        "art-plan-1", "PLANNING", 1, "APPROVED",
        parent_artifact_id="art-product-1",
    )
    workflow_instance.coding_artifact = _artifact_ref(
        "art-code-1", "CODING", 1, "PROPOSED",
        parent_artifact_id="art-plan-1",
    )
    workflow_instance.effective_heads = {
        "PRODUCT": workflow_instance.product_artifact,
        "PLANNING": workflow_instance.planning_artifact,
    }
    workflow_instance.state.status = LifecycleStatus.activated
    workflow_instance.state.diff_patch = ""
    workflow_instance.completed_stage_results = []
    workflow_instance.context_snapshot = None
    async def fetch_context(name, *_args, **_kwargs):
        if name == "capture_case_revisions":
            return {"main": "base-1"}
        return {
            "snapshot_id": "snapshot-1",
            "snapshot_hash": "snapshot-hash-1",
            "root_artifact_id": "art-product-1",
            "source_artifacts": [
                workflow_instance.product_artifact.model_dump(),
                workflow_instance.planning_artifact.model_dump(),
                workflow_instance.coding_artifact.model_dump(),
            ],
            "relationships": [],
            "effective_heads": {
                key: value.model_dump()
                for key, value in workflow_instance.effective_heads.items()
            },
            "system_id": "system-1",
            "requirement_manifest_id": "manifest-1",
            "requirement_items": [],
            "execution_items": [],
            "git_base_revisions": {"main": "base-1"},
            "stale_references": [],
            "product_artifact": workflow_instance.product_artifact.model_dump(),
            "product_content": {
                "goal": "把登录页加错误提示",
                "acceptanceCriteria": ["错误密码时显示提示"],
            },
            "planning_artifact": workflow_instance.planning_artifact.model_dump(),
            "planning_content": {
                "planMarkdown": "# 批准计划",
                "baseRevisions": {"main": "base-1"},
            },
            "previous_artifact": workflow_instance.coding_artifact.model_dump(),
            "previous_content": {
                "repoChanges": [{
                    "repo": "main",
                    "diffPatch": (
                        "diff --git a/src/app.py b/src/app.py\n"
                        "--- a/src/app.py\n+++ b/src/app.py\n@@ -1 +1 @@\n-old\n+new\n"
                    ),
                    "changedPaths": ["src/app.py"],
                    "summary": "从 Artifact 恢复",
                }],
            },
        }

    async def finish(*_args, **_kwargs):
        raise AssertionError("阶段恢复不能重新提交 ModificationCompleted")

    monkeypatch.setattr(
        "asterism_worker.workflows.coding.workflow.execute_activity", fetch_context,
    )
    patch_ids: list[str] = []
    monkeypatch.setattr(
        "asterism_worker.workflows.coding.workflow.patched",
        lambda patch_id: patch_ids.append(patch_id) or True,
    )
    workflow_instance._finish_modification = finish
    emitted: list[str] = []

    async def emit(event_type, *_args, **_kwargs):
        emitted.append(event_type)

    workflow_instance._emit = emit

    recovered = asyncio.run(workflow_instance._restore_modification_checkpoint(
        "retry-artifact-1", ExecutionPhase.patch,
    ))

    assert recovered is True
    assert workflow_instance.state.status == LifecycleStatus.modification_completed
    assert workflow_instance.state.diff_patch.startswith("diff --git")
    assert workflow_instance.context_snapshot.previous_artifact == workflow_instance.coding_artifact
    assert workflow_instance.completed_stage_results[0].repo == "main"
    assert emitted == ["ModificationCheckpointRestored"]
    assert patch_ids == ["artifact-checkpoint-projection-v1:retry-artifact-1"]


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
    validation_mode: str = "auto",
    publish_requests: list[dict] | None = None,
    plan_requests: list[dict] | None = None,
    coding_outcomes: list[dict] | None = None,
    wait_for_coding_interrupt: bool = False,
    context_stale_once: bool = False,
    context_stale_at_call: int | None = None,
    plan_result_missing: bool = False,
    coding_patch: str | None = None,
    apply_requests: list[dict] | None = None,
    patch_blocked_once: bool = False,
    validation_failures: int = 0,
    validation_rejections: int = 0,
    release_failures: int = 0,
    revert_failure: str = "",
    artifact_content_overrides: dict[str, dict] | None = None,
    legacy_case: bool = False,
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
    remaining_patch_blocks = 1 if patch_blocked_once else 0
    remaining_validation_failures = validation_failures
    remaining_validation_rejections = validation_rejections
    remaining_release_failures = release_failures
    product_ref = _artifact_ref("art-product-1", "PRODUCT", 1, "APPROVED")
    current_artifacts: dict[str, ArtifactRef] = {"PRODUCT": product_ref}
    artifact_refs: dict[str, ArtifactRef] = {product_ref.artifact_id: product_ref}
    artifact_contents: dict[str, dict] = {
        product_ref.artifact_id: {
            "title": "登录页错误提示",
            "goal": "把登录页加错误提示",
            "acceptanceCriteria": ["错误密码时显示提示"],
            "requirementManifestId": "manifest-1",
        },
    }
    # 模拟控制面 Artifact 存储，使历史版本切换后的 Context 能读取对应内容。
    artifact_contents.update(artifact_content_overrides or {})
    artifact_feedback: dict[str, list[str]] = {}
    artifact_versions = {"PLANNING": 0, "CODING": 0, "VALIDATION": 0, "RELEASE": 0}

    def replace_head(reference: ArtifactRef) -> None:
        """模拟控制面 effective head CAS 与同事务下游失效。"""

        order = ["PRODUCT", "PLANNING", "CODING", "VALIDATION", "RELEASE"]
        previous = current_artifacts.get(reference.artifact_type)
        if previous and previous.artifact_id != reference.artifact_id:
            artifact_refs[previous.artifact_id] = previous.model_copy(
                update={"status": "SUPERSEDED"},
            )
        current_artifacts[reference.artifact_type] = reference
        for artifact_type in order[order.index(reference.artifact_type) + 1:]:
            downstream = current_artifacts.pop(artifact_type, None)
            if downstream:
                artifact_refs[downstream.artifact_id] = downstream.model_copy(
                    update={"status": "SUPERSEDED"},
                )

    def called(name: str) -> None:
        calls[name] = calls.get(name, 0) + 1

    @activity.defn(name="capture_case_revisions")
    async def capture_case_revisions(_request: dict) -> dict:
        return {"main": "base-1"}

    @activity.defn(name="fetch_context")
    async def fetch_context(request: dict) -> dict:
        called("fetch_context")
        stale_call = context_stale_at_call or (1 if context_stale_once else None)
        stale = ["MEM:changed"] if calls["fetch_context"] == stale_call else []
        if "product_artifact" not in request:
            return {
                "system_id": request["system_id"],
                "requirement_manifest_id": request["requirement_manifest_id"],
                "requirement_items": [{"refId": "MEM:mem-1", "content": "遵守登录页约束"}],
                "execution_bundle_id": "bundle-execution-1",
                "execution_items": [],
                "stale_references": stale,
            }
        product = ArtifactRef.model_validate(request["product_artifact"])
        planning = (
            ArtifactRef.model_validate(request["planning_artifact"])
            if request.get("planning_artifact") else None
        )
        previous = (
            ArtifactRef.model_validate(request["previous_artifact"])
            if request.get("previous_artifact") else None
        )
        artifact_refs[product.artifact_id] = product
        current_artifacts["PRODUCT"] = product
        artifact_contents.setdefault(product.artifact_id, {
            "title": "登录页错误提示",
            "goal": "把登录页加错误提示",
            "acceptanceCriteria": ["错误密码时显示提示"],
            "requirementManifestId": request["requirement_manifest_id"],
        })
        source_artifacts = [product]
        if planning:
            source_artifacts.append(planning)
        if previous:
            source_artifacts.append(previous)
        feedback_notes: list[str] = []
        for reference in (product, planning, previous):
            if reference is None:
                continue
            for note in artifact_feedback.get(reference.artifact_id, []):
                if note and note not in feedback_notes:
                    feedback_notes.append(note)
        return {
            "snapshot_id": f"snapshot-{calls['fetch_context']}",
            "snapshot_hash": f"snapshot-hash-{calls['fetch_context']}",
            "root_artifact_id": product.root_artifact_id,
            "source_artifacts": [value.model_dump() for value in source_artifacts],
            "relationships": [],
            "effective_heads": {
                key: value.model_dump() for key, value in current_artifacts.items()
            },
            "system_id": request["system_id"],
            "requirement_manifest_id": request["requirement_manifest_id"],
            "requirement_items": [{"refId": "MEM:mem-1", "content": "遵守登录页约束"}],
            "execution_bundle_id": "bundle-execution-1",
            "execution_items": [], "stale_references": stale,
            "git_base_revisions": request.get("git_base_revisions", {}),
            "built_at": "2026-07-29T00:00:00Z",
            "product_artifact": product.model_dump(),
            "product_content": artifact_contents[product.artifact_id],
            "planning_artifact": planning.model_dump() if planning else None,
            "planning_content": artifact_contents.get(planning.artifact_id, {}) if planning else {},
            "previous_artifact": previous.model_dump() if previous else None,
            "previous_content": artifact_contents.get(previous.artifact_id, {}) if previous else {},
            "previous_transitions": [],
            "feedback_notes": feedback_notes,
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
                "blockers": [], "changed_paths": ["src/app.py"], "session_id": "session-1",
            },
            "repo_changes": [{
                "repo": "main", "summary": "仓库修改完成", "changed_paths": ["src/app.py"],
                "diff_patch": coding_patch or (
                    "diff --git a/src/app.py b/src/app.py\n--- a/src/app.py\n+++ b/src/app.py\n"
                    "@@ -1 +1 @@\n-old\n+new\n"
                ),
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
        if plan_result_missing:
            raise ApplicationError(
                "planning_text_missing",
                type="PLAN_RESULT_MISSING",
                non_retryable=True,
            )
        revision = request["plan_revision"]
        return {
            "plan_markdown": f"# 第 {revision} 版计划",
            "revision": revision,
            "session_id": f"plan-session-{revision}", "base_revisions": {"main": "base-1"},
        }

    @activity.defn(name="apply_patch_to_repo")
    async def apply_patch_to_repo(request: dict) -> dict:
        nonlocal remaining_patch_blocks
        called("apply_patch_to_repo")
        if apply_requests is not None:
            apply_requests.append(request)
        if remaining_patch_blocks:
            remaining_patch_blocks -= 1
            return {"blocked": True, "already_applied": False, "reason": "temporary patch failure"}
        return {"blocked": False, "already_applied": False}

    @activity.defn(name="run_validation")
    async def run_validation(_request: dict) -> dict:
        nonlocal remaining_validation_failures, remaining_validation_rejections
        called("run_validation")
        if remaining_validation_failures:
            remaining_validation_failures -= 1
            raise RuntimeError("validation unavailable")
        if remaining_validation_rejections:
            remaining_validation_rejections -= 1
            return {
                "passed": False,
                "commands": [{"command": "pytest", "exit_code": 1}],
                "failed_command": "pytest",
                "stderr_tail": "1 failed",
            }
        return {"passed": True, "commands": [{"command": "pytest", "exit_code": 0}]}

    @activity.defn(name="run_release")
    async def run_release(_request: dict) -> dict:
        nonlocal remaining_release_failures
        called("run_release")
        if remaining_release_failures:
            remaining_release_failures -= 1
            raise RuntimeError("release unavailable")
        return {"branch": "wi/wi-1", "commit_hash": "commit-1", "push_failed": ""}

    @activity.defn(name="revert_patch")
    async def revert_patch(_request: dict) -> dict:
        called("revert_patch")
        return {"failed": revert_failure, "already_reverted": False}

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
    async def send_projection_event(event: dict) -> dict:
        payload = dict(event["payload"])
        transition = event.get("artifactTransition")
        result_ref = None
        if transition:
            kind = transition["kind"]
            if kind.startswith("Propose"):
                artifact_type = "PLANNING" if "Planning" in kind else "CODING"
                artifact_versions[artifact_type] += 1
                version = artifact_versions[artifact_type]
                artifact_id = (
                    f"art-plan-{version}" if artifact_type == "PLANNING"
                    else f"art-code-{version}"
                )
                parent = ArtifactRef.model_validate(transition["parent"])
                supersedes = (
                    ArtifactRef.model_validate(transition["supersedes"])
                    if transition.get("supersedes") else None
                )
                result_ref = _artifact_ref(
                    artifact_id, artifact_type, version, "PROPOSED",
                    parent_artifact_id=parent.artifact_id,
                    supersedes_artifact_id=(
                        supersedes.artifact_id if supersedes else None
                    ),
                )
                artifact_contents[artifact_id] = dict(transition.get("content") or {})
            else:
                previous = ArtifactRef.model_validate(transition["artifact"])
                status = (
                    "APPROVED" if kind.startswith("Approve")
                    else "REJECTED" if kind.startswith("Reject")
                    else "SUPERSEDED"
                )
                result_ref = previous.model_copy(update={"status": status})
                if status == "APPROVED":
                    replace_head(result_ref)
                elif status == "SUPERSEDED":
                    head = current_artifacts.get(previous.artifact_type)
                    if head and head.artifact_id == previous.artifact_id:
                        current_artifacts.pop(previous.artifact_type)
                        order = ["PRODUCT", "PLANNING", "CODING", "VALIDATION", "RELEASE"]
                        for artifact_type in order[order.index(previous.artifact_type) + 1:]:
                            downstream = current_artifacts.pop(artifact_type, None)
                            if downstream:
                                artifact_refs[downstream.artifact_id] = downstream.model_copy(
                                    update={"status": "SUPERSEDED"},
                                )
            artifact_refs[result_ref.artifact_id] = result_ref
            if not kind.startswith("Propose"):
                note = str(transition.get("note", "")).strip()
                if note:
                    artifact_feedback.setdefault(result_ref.artifact_id, []).append(note)
            payload.update({
                "artifactRef": result_ref.model_dump(by_alias=True),
                "artifactId": result_ref.artifact_id,
                "artifactType": result_ref.artifact_type,
                "version": result_ref.version,
            })
            if result_ref.artifact_type == "PLANNING":
                payload["planningArtifactId"] = result_ref.artifact_id
            if result_ref.artifact_type == "CODING":
                payload["codingArtifactId"] = result_ref.artifact_id
        elif payload.get("artifactResultVersion") == 1 and event["eventType"] in {
            "ValidationPassed", "ValidationFailed", "WorkerBlocked", "ReleaseCompleted",
        }:
            if event["eventType"] == "ReleaseCompleted":
                artifact_type = "RELEASE"
                parent = current_artifacts.get("VALIDATION")
            elif event["eventType"] != "WorkerBlocked" or payload.get("failedPhase") == "validation":
                artifact_type = "VALIDATION"
                parent = current_artifacts.get("CODING")
            else:
                artifact_type = ""
                parent = None
            if artifact_type:
                if parent is None:
                    raise AssertionError(f"{artifact_type} 结果缺少有效父 Artifact")
                artifact_versions[artifact_type] += 1
                version = artifact_versions[artifact_type]
                prefix = "validation" if artifact_type == "VALIDATION" else "release"
                previous = current_artifacts.get(artifact_type)
                result_ref = _artifact_ref(
                    f"art-{prefix}-{version}", artifact_type, version, "APPROVED",
                    parent_artifact_id=parent.artifact_id,
                    supersedes_artifact_id=previous.artifact_id if previous else None,
                )
                replace_head(result_ref)
                artifact_refs[result_ref.artifact_id] = result_ref
                artifact_contents[result_ref.artifact_id] = dict(payload)
                payload.update({
                    "artifactRef": result_ref.model_dump(by_alias=True),
                    "artifactId": result_ref.artifact_id,
                    "artifactType": result_ref.artifact_type,
                    "version": result_ref.version,
                })
                payload[
                    "validationArtifactId" if artifact_type == "VALIDATION" else "releaseArtifactId"
                ] = result_ref.artifact_id
        evidence = event.get("artifactEvidence")
        if evidence:
            evidence_artifact = (
                result_ref
                if result_ref is not None
                else ArtifactRef.model_validate(evidence.get("artifact"))
                if evidence.get("artifact") else None
            )
            evidence_payload = dict(evidence.get("payload") or {})
            note = str(evidence_payload.get("note") or "").strip()
            if evidence_artifact is not None and note:
                notes = artifact_feedback.setdefault(evidence_artifact.artifact_id, [])
                if not any(existing in note or note in existing for existing in notes):
                    notes.append(note)
        event = {**event, "payload": payload}
        events.append(event)
        return {
            "event": event,
            "artifactRef": result_ref.model_dump(by_alias=True) if result_ref else None,
            "transition": (
                {"transitionId": transition["transitionId"]} if transition else None
            ),
            "evidence": (
                {"evidenceId": event["artifactEvidence"]["evidenceId"]}
                if event.get("artifactEvidence") else None
            ),
        }

    async with await WorkflowEnvironment.start_time_skipping(data_converter=pydantic_data_converter) as env:
        async with Worker(
            env.client,
            task_queue=TASK_QUEUE,
            workflows=[AsterismCaseWorkflow],
            activities=[capture_case_revisions, fetch_context, generate_coding_plan,
                        run_coding_attempt, apply_patch_to_repo, run_validation,
                        run_release, revert_patch, publish_merge_request, check_merge_requests,
                        ready_merge_requests, send_projection_event],
        ):
            handle = await env.client.start_workflow(
                AsterismCaseWorkflow.run,
                (
                    _legacy_case_input(
                        execution_architecture, max_revisions, release_mode, validation_mode,
                    )
                    if legacy_case
                    else _case_input(
                        execution_architecture, max_revisions, release_mode, validation_mode,
                    )
                ),
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
                artifact_requirement = {
                    "coding_plan_approved": ("PLANNING", "PROPOSED"),
                    "coding_plan_rejected": ("PLANNING", "PROPOSED"),
                    "patch_apply_approved": ("CODING", "PROPOSED"),
                    "patch_apply_rejected": ("CODING", "PROPOSED"),
                    "validation_passed": ("CODING", "APPROVED"),
                    "validation_rejected": ("CODING", "APPROVED"),
                    "validation_retry": ("VALIDATION", "APPROVED"),
                    "validation_rework_coding": ("VALIDATION", "APPROVED"),
                    "validation_rework_planning": ("VALIDATION", "APPROVED"),
                    "release_approved": ("VALIDATION", "APPROVED"),
                    "release_retry": ("VALIDATION", "APPROVED"),
                    "release_revalidate": ("VALIDATION", "APPROVED"),
                    "release_rework_coding": ("VALIDATION", "APPROVED"),
                }.get(signal_name)
                if artifact_requirement and not legacy_case:
                    async def current_artifact() -> ArtifactRef:
                        while True:
                            candidates = [
                                value for value in artifact_refs.values()
                                if value.artifact_type == artifact_requirement[0]
                                and value.status == artifact_requirement[1]
                            ]
                            if candidates:
                                return max(candidates, key=lambda value: value.version)
                            await asyncio.sleep(0.01)

                    reference = await asyncio.wait_for(current_artifact(), timeout=2)
                    context = dict(context)
                    context.setdefault("artifact_ref", reference.model_dump())
                    if reference.status == "APPROVED":
                        context.setdefault("selected_type", reference.artifact_type)
                        context.setdefault("selected_artifact", reference.model_dump())
                        for artifact_type, key in (
                            ("PRODUCT", "product_artifact"),
                            ("PLANNING", "planning_artifact"),
                            ("CODING", "coding_artifact"),
                            ("VALIDATION", "validation_artifact"),
                            ("RELEASE", "release_artifact"),
                        ):
                            current = current_artifacts.get(artifact_type)
                            if current is not None:
                                context.setdefault(key, current.model_dump())
                        context.setdefault("requirement_manifest_id", "manifest-1")
                if context.get("artifact_ref") and not legacy_case:
                    # 自定义动作可模拟控制面已选中的 Approved Coding Head。
                    action_ref = ArtifactRef.model_validate(context["artifact_ref"])
                    artifact_refs[action_ref.artifact_id] = action_ref
                    if action_ref.status == "APPROVED":
                        current_artifacts[action_ref.artifact_type] = action_ref
                if not legacy_case:
                    # 版本切换和精确动作都由控制面同时发送完整 effective route。
                    for artifact_type, key in (
                        ("PRODUCT", "product_artifact"),
                        ("PLANNING", "planning_artifact"),
                        ("CODING", "coding_artifact"),
                        ("VALIDATION", "validation_artifact"),
                        ("RELEASE", "release_artifact"),
                    ):
                        if context.get(key):
                            route_ref = ArtifactRef.model_validate(context[key])
                            if route_ref.artifact_type != artifact_type:
                                raise AssertionError("测试动作携带了错误 Artifact 路线")
                            artifact_refs[route_ref.artifact_id] = route_ref
                            current_artifacts[artifact_type] = route_ref
                await handle.signal(signal_name, context)
                signal_id = str(context["signal_id"])
                if wait_for_coding_interrupt and signal_name == "coding_plan_approved":
                    continue

                async def action_completed() -> None:
                    while not any(
                        event["eventType"] == "TemporalActionCompleted"
                        and event["payload"].get("signalId") == signal_id
                        for event in events
                    ):
                        await asyncio.sleep(0.01)

                await asyncio.wait_for(action_completed(), timeout=4)
            result = await asyncio.wait_for(handle.result(), timeout=8)
    return events, result, calls, requests


def _case_input(
    execution_architecture: str,
    max_revisions: int,
    release_mode: str,
    validation_mode: str = "auto",
) -> CaseInput:
    return CaseInput(
        case_id="case-1",
        work_item_id="wi-1",
        prd_id="prd-1",
        system_id="system-1",
        prd=PrdSpec(
            requirement_manifest_id="manifest-1",
            product_artifact=_artifact_ref(
                "art-product-1", "PRODUCT", 1, "APPROVED",
            ),
        ),
        repo_path="/tmp/repo",
        allowed_paths=["src"],
        test_commands=["pytest"],
        execution_architecture=execution_architecture,
        max_revisions=max_revisions,
        release_mode=release_mode,
        validation_mode=validation_mode,
    )


def _legacy_case_input(
    execution_architecture: str,
    max_revisions: int,
    release_mode: str,
    validation_mode: str = "auto",
) -> CaseInput:
    return CaseInput(
        case_id="case-legacy-1",
        work_item_id="wi-legacy-1",
        prd_id="prd-legacy-1",
        system_id="system-1",
        prd=PrdSpec(
            title="登录页错误提示",
            goal="把登录页加错误提示",
            acceptance_criteria=["错误密码时显示提示"],
            draft_json={"goal": "把登录页加错误提示"},
            requirement_manifest_id="manifest-1",
        ),
        repo_path="/tmp/repo",
        allowed_paths=["src"],
        test_commands=["pytest"],
        execution_architecture=execution_architecture,
        max_revisions=max_revisions,
        release_mode=release_mode,
        validation_mode=validation_mode,
    )


def _artifact_ref(
    artifact_id: str,
    artifact_type: str,
    version: int,
    status: str,
    *,
    parent_artifact_id: str | None = None,
    supersedes_artifact_id: str | None = None,
) -> ArtifactRef:
    return ArtifactRef(
        artifact_id=artifact_id,
        artifact_type=artifact_type,
        version=version,
        content_hash=f"hash-{artifact_id}",
        root_artifact_id="art-product-1",
        parent_artifact_id=parent_artifact_id,
        supersedes_artifact_id=supersedes_artifact_id,
        status=status,
    )


def _business_types(events: list[dict]) -> list[str]:
    return [event["eventType"] for event in events if event["eventType"] != "TemporalActionCompleted"]


def _contains_in_order(values: list[str], expected: list[str]) -> bool:
    iterator = iter(values)
    return all(any(value == target for value in iterator) for target in expected)
