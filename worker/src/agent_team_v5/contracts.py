from enum import StrEnum
from typing import Any

from pydantic import BaseModel, Field


class LifecycleStatus(StrEnum):
    allocated = "allocated"
    waiting_owner_approval = "waiting_owner_approval"
    activated = "activated"
    modification_completed = "modification_completed"
    worker_blocked = "worker_blocked"
    patch_applied = "patch_applied"
    patch_rejected = "patch_rejected"
    validation_passed = "validation_passed"
    validation_failed = "validation_failed"
    completed = "completed"
    cancelled = "cancelled"
    rejected = "rejected"


class CaseInput(BaseModel):
    case_id: str
    work_item_id: str
    prd_id: str
    system_id: str
    prd: "PrdSpec"
    repo_path: str = ""
    allowed_paths: list[str] = Field(default_factory=list)
    forbidden_paths: list[str] = Field(default_factory=list)
    test_commands: list[str] = Field(default_factory=list)
    execution_provider: str = ""
    claude_max_turns: int | None = None
    execution_timeout_seconds: int | None = None


class ProjectionEvent(BaseModel):
    eventType: str
    systemId: str
    caseId: str
    prdId: str
    workItemId: str
    actorId: str = "worker"
    payload: dict[str, Any] = Field(default_factory=dict)
    correlationId: str
    causationId: str | None = None
    idempotencyKey: str


class ExecutionRequest(BaseModel):
    case_id: str
    work_item_id: str
    system_id: str
    repo_path: str
    goal: str
    acceptance_criteria: list[str] = Field(default_factory=list)
    plan: "ExecutionPlan"
    memories: list[dict[str, Any]] = Field(default_factory=list)
    context_manifest_id: str = ""
    allowed_paths: list[str] = Field(default_factory=list)
    forbidden_paths: list[str] = Field(default_factory=list)
    test_commands: list[str] = Field(default_factory=list)
    execution_provider: str = ""
    claude_max_turns: int | None = None
    file_listing: str = ""
    file_contents: dict[str, str] = Field(default_factory=dict)
    previous_attempt: "PreviousAttempt | None" = None
    role_id: str = ""
    role_name: str = ""
    model_profile_id: str = ""
    role_scope: list[str] = Field(default_factory=list)
    role_prompt: str = ""
    handoff_summary: str = ""
    assignment_index: int = 0
    step_refs: list[str] = Field(default_factory=list)


class PreviousAttempt(BaseModel):
    diff: str
    apply_error: str


class PatchApplyResult(BaseModel):
    blocked: bool = False
    reason: str = ""


class PatchApplyRequest(BaseModel):
    repo_path: str
    diff_patch: str
    allowed_paths: list[str] = Field(default_factory=list)
    forbidden_paths: list[str] = Field(default_factory=list)


class ValidationCommandResult(BaseModel):
    command: str
    exit_code: int
    stdout_tail: str = ""
    stderr_tail: str = ""


class ValidationResult(BaseModel):
    passed: bool
    commands: list[ValidationCommandResult] = Field(default_factory=list)
    failed_command: str = ""
    stderr_tail: str = ""


class ExecutionResult(BaseModel):
    summary: str
    diff_patch: str
    execution_provider: str = ""
    turns: int | None = None
    token_usage: dict[str, Any] = Field(default_factory=dict)
    role_id: str = ""
    engine: str = ""
    changed_paths: list[str] = Field(default_factory=list)
    blocked_reason: str = ""
    blocked_detail: str = ""

    @property
    def passes_diff_gate(self) -> bool:
        return bool(self.diff_patch.strip()) and "diff --git" in self.diff_patch


class ReleaseResult(BaseModel):
    branch: str
    commit_hash: str
    push_failed: str = ""


class PrdSpec(BaseModel):
    title: str = ""
    goal: str
    acceptance_criteria: list[str] = Field(default_factory=list)
    draft_json: dict[str, Any] = Field(default_factory=dict)


class ContextSnapshot(BaseModel):
    system_id: str
    manifest_id: str
    approved_memories: list[dict[str, Any]] = Field(default_factory=list)


class ExecutionPlan(BaseModel):
    steps: list[str] = Field(default_factory=list)
    target_files: list[str] = Field(default_factory=list)
    test_plan: list[str] = Field(default_factory=list)
    risks: list[str] = Field(default_factory=list)
    assignments: list["AgentAssignment"] = Field(default_factory=list)


class AgentAssignment(BaseModel):
    role: str
    scope_paths: list[str] = Field(default_factory=list)
    step_refs: list[str] = Field(default_factory=list)


class AvailableRole(BaseModel):
    id: str
    name: str = ""
    engine: str
    path_scope: list[str] = Field(default_factory=list)


class PlanRequest(BaseModel):
    system_id: str
    prd: PrdSpec
    repo_summary: str = ""
    memories: list[dict[str, Any]] = Field(default_factory=list)
    allowed_paths: list[str] = Field(default_factory=list)
    context_manifest_id: str
    available_roles: list[AvailableRole] = Field(default_factory=list)
