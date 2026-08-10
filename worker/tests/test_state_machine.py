from asterism_worker.contracts import ExecutionResult, LifecycleStatus
from asterism_worker.workflows.state_machine import CaseState, TERMINAL_STATUSES, TRANSITIONS
import json
from pathlib import Path


def test_owner_approval_is_the_only_activation_event():
    state = CaseState()

    event = state.owner_approved()

    assert event == "WorkItemActivated"
    assert state.status == LifecycleStatus.activated
    assert state.execution_allowed is True


def test_diff_gate_blocks_empty_or_non_git_diff():
    state = CaseState(status=LifecycleStatus.activated, execution_allowed=True)

    event = state.modification_finished(ExecutionResult(summary="bad", diff_patch="changed files"))

    assert event == "WorkerBlocked"
    assert state.status == LifecycleStatus.worker_blocked
    assert state.execution_allowed is False


def test_illegal_signal_is_ignored_without_state_change():
    state = CaseState()

    event = state.validation_passed()

    assert event is None
    assert state.status == LifecycleStatus.waiting_owner_approval
    assert state.execution_allowed is False


def test_duplicate_owner_approved_does_not_emit_again():
    state = CaseState()

    first = state.owner_approved()
    second = state.owner_approved()

    assert first == "WorkItemActivated"
    assert second is None
    assert state.status == LifecycleStatus.activated


def test_owner_rejected_moves_to_java_rejected_terminal_state():
    state = CaseState()

    event = state.owner_rejected()

    assert event == "WorkItemRejected"
    assert state.status == LifecycleStatus.rejected
    assert state.execution_allowed is False


def test_patch_apply_blocked_moves_back_to_worker_blocked():
    state = CaseState(status=LifecycleStatus.modification_completed, execution_allowed=True)

    event = state.patch_apply_blocked()

    assert event == "PatchApplyBlocked"
    assert state.status == LifecycleStatus.worker_blocked
    assert state.execution_allowed is False


def test_rework_emits_rework_started_instead_of_activation_event():
    state = CaseState(status=LifecycleStatus.validation_failed)

    event = state.rework()

    assert event == "ReworkStarted"
    assert state.status == LifecycleStatus.activated
    assert state.execution_allowed is True


def test_validation_passed_can_rework_back_to_activated():
    state = CaseState(status=LifecycleStatus.validation_passed)

    event = state.rework()

    assert event == "ReworkStarted"
    assert state.status == LifecycleStatus.activated
    assert state.execution_allowed is True


def test_release_failure_can_block_after_validation_passed():
    state = CaseState(status=LifecycleStatus.validation_passed)

    event = state.worker_blocked_on("release_failed")

    assert event == "WorkerBlocked"
    assert state.status == LifecycleStatus.worker_blocked
    assert state.execution_allowed is False


def test_gitlab_release_waits_for_merge_before_completion():
    state = CaseState(status=LifecycleStatus.validation_passed)

    assert state.merge_requests_created() == "MergeRequestCreated"
    assert state.status == LifecycleStatus.waiting_merge
    assert state.all_merged() == "ReleaseCompleted"
    assert state.status == LifecycleStatus.completed


def test_closed_merge_request_blocks_for_human_decision():
    state = CaseState(status=LifecycleStatus.waiting_merge)

    assert state.merge_request_closed() == "MergeRequestClosed"
    assert state.status == LifecycleStatus.worker_blocked


def test_python_state_machine_matches_shared_lifecycle_contract():
    contract_path = Path(__file__).resolve().parents[2] / "docs" / "lifecycle-transitions.json"
    transitions = json.loads(contract_path.read_text())

    for status in LifecycleStatus:
        assert status.value in transitions
        assert {target.value for target in TRANSITIONS[status]} == set(transitions[status.value])


def test_terminal_statuses_are_derived_from_empty_transitions():
    assert {status.value for status in TERMINAL_STATUSES} == {"completed", "cancelled", "rejected"}
