import asyncio
import subprocess
from uuid import uuid4

from temporalio import activity
from temporalio.contrib.pydantic import pydantic_data_converter
from temporalio.testing import WorkflowEnvironment
from temporalio.worker import Worker

from asterism_worker.contracts import CaseInput, ExecutionPlan, ExecutionResult, PatchApplyResult, PrdSpec
from asterism_worker.workflows.case_lifecycle import AsterismCaseWorkflow
from asterism_worker.activities.execution import validate_patch_paths


TASK_QUEUE = "asterism-test"


def test_workflow_runs_legal_full_lifecycle():
    events, result = asyncio.run(_run_workflow([
        ("owner_approved", "owner-approved-wi-1"),
        ("start_modification", "start-modification-wi-1"),
        ("patch_apply_approved", "patch-apply-approved-wi-1"),
        ("validation_passed", "validation-passed-wi-1"),
        ("release_approved", "release-approved-wi-1"),
    ]))

    assert result == "completed"
    assert [event["eventType"] for event in events] == [
        "WorkItemActivated",
        "ExecutionPlanDrafted",
        "ModificationCompleted",
        "PatchApplied",
        "ValidationPassed",
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
    event_types = [event["eventType"] for event in events]
    assert event_types == [
        "WorkItemActivated",
        "ExecutionPlanDrafted",
        "ModificationCompleted",
        "PatchApplied",
        "ValidationPassed",
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
    ], test_commands=[]))

    assert result == "completed"
    assert [event["eventType"] for event in events].count("ValidationPassed") == 1


def test_workflow_ignores_illegal_signal():
    events, result = asyncio.run(_run_workflow([
        ("validation_passed", "validation-passed-wi-1"),
        ("cancel_case", "cancel-case-wi-1"),
    ]))

    assert result == "cancelled"
    assert [event["eventType"] for event in events] == ["CaseCancelled"]


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
    assert [event["eventType"] for event in events] == ["WorkItemRejected"]


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
    ], test_commands=[]))

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
    assert requests[1]["handoff_summary"].startswith("frontend:")
    assert [event["payload"]["engine"] for event in stages] == ["claude_sdk", "deepagents"]
    assert len({event["idempotencyKey"] for event in stages}) == 2
    assert stages[0]["causationId"].endswith(":stage:0:frontend")
    assert stages[1]["causationId"].endswith(":stage:1:backend")


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
    assert [event["eventType"] for event in events] == [
        "WorkItemActivated", "ExecutionPlanDrafted", "AgentStageCompleted", "AgentStageCompleted",
        "ModificationCompleted", "PatchApplied", "ValidationPassed", "ReleaseCompleted",
    ]
    modification = next(event for event in events if event["eventType"] == "ModificationCompleted")
    assert "web/app.ts" in modification["payload"]["diffPatch"]
    assert "api/app.py" in modification["payload"]["diffPatch"]
    assert all("api_key" not in str(request).lower() for request in requests)


async def _run_workflow(
    signals: list[tuple[str, str]],
    planner_failure: bool = False,
    failure_mode: str | None = None,
    fetch_failures: int = 0,
    validation_result: dict | None = None,
    test_commands: list[str] | None = None,
    assignments: list[dict] | None = None,
    handoff_mode: str = "",
    observed_requests: list[dict] | None = None,
    repo_path: str = "/tmp/repo",
    verify_combined_diff: bool = False,
) -> tuple[list[dict], str]:
    events: list[dict] = []
    execution_requests: list[dict] = []
    fetch_attempts = 0

    @activity.defn(name="fetch_context")
    async def fake_fetch_context(request: dict) -> dict:
        nonlocal fetch_attempts
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

    @activity.defn(name="run_execution")
    async def fake_run_execution(request: dict) -> dict:
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
        if handoff_mode == "scope":
            return ExecutionResult(summary="越界", diff_patch="diff --git a/api/app.py b/api/app.py\n",
                                   execution_provider="claude_sdk", engine="claude_sdk",
                                   blocked_reason="role_scope_violation", blocked_detail="api/app.py").model_dump()
        if assignments:
            frontend = request["role_id"] == "frontend"
            path = "src/shared.py" if handoff_mode == "conflict" else ("web/app.ts" if frontend else "api/app.py")
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
        assert request["repo_path"] == repo_path
        assert request["target_files"] == ["src/login.tsx"]

    @activity.defn(name="apply_patch_to_repo")
    async def fake_apply_patch_to_repo(request: dict) -> dict:
        # patch 应用活动在 workflow 测试中不碰真实仓库。
        if failure_mode == "apply_patch":
            raise RuntimeError("git apply exploded")
        if verify_combined_diff:
            gate = validate_patch_paths(request["diff_patch"], ["web", "api"], [])
            assert gate.blocked is False
            subprocess.run(["git", "apply", "--check"], cwd=repo_path, input=request["diff_patch"],
                           text=True, check=True, capture_output=True)
        return PatchApplyResult().model_dump()

    @activity.defn(name="run_release")
    async def fake_run_release(request: dict) -> dict:
        if failure_mode == "release":
            raise RuntimeError("release exploded")
        assert request["repo_path"] == repo_path
        assert request["work_item_id"] == "wi-1"
        if verify_combined_diff:
            assert "web/app.ts" in request["diff_patch"] and "api/app.py" in request["diff_patch"]
        else:
            assert request["diff_patch"] == "diff --git a/src/app.py b/src/app.py\n"
        return {"branch": "wi/wi-1", "commit_hash": "abc123", "push_failed": ""}

    @activity.defn(name="revert_patch")
    async def fake_revert_patch(request: dict) -> dict:
        assert request["repo_path"] == repo_path
        return {"changed_paths": ["src/app.py"], "failed": ""}

    @activity.defn(name="summarize_repo")
    async def fake_summarize_repo(request: dict) -> str:
        assert request["repo_path"] == repo_path
        return "repo summary"

    @activity.defn(name="run_validation")
    async def fake_run_validation(request: dict) -> dict:
        if not request["test_commands"]:
            raise AssertionError("empty test_commands should not trigger validation")
        assert request["repo_path"] == repo_path
        return validation_result or {"passed": True, "commands": []}

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
                fake_validate_plan_targets,
                fake_run_execution,
                fake_apply_patch_to_repo,
                fake_run_release,
                fake_revert_patch,
                fake_run_validation,
                fake_send_projection_event,
            ],
        ):
            handle = await env.client.start_workflow(
                AsterismCaseWorkflow.run,
                _case_input(test_commands, repo_path),
                id=f"case-{uuid4()}",
                task_queue=TASK_QUEUE,
            )
            for signal_name, signal_id in signals:
                await handle.signal(signal_name, signal_id)
            result = await asyncio.wait_for(handle.result(), timeout=8)
    return events, result


def _case_input(test_commands: list[str] | None = None, repo_path: str = "/tmp/repo") -> CaseInput:
    return CaseInput(
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
        execution_provider="claude_sdk",
        claude_max_turns=25,
        execution_timeout_seconds=900,
    )
