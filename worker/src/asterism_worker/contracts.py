from enum import StrEnum
from typing import Any

from pydantic import BaseModel, ConfigDict, Field


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
    waiting_merge = "waiting_merge"
    completed = "completed"
    cancelled = "cancelled"
    rejected = "rejected"


class ModelProfileSnapshot(BaseModel):
    id: str
    name: str = ""
    provider: str = "anthropic"
    base_url: str = ""
    model: str = ""
    supports_vision: bool = False


class AgentSnapshot(BaseModel):
    name: str
    kind: str
    engine: str = ""
    model_profile_ref: str = ""
    path_scope: list[str] = Field(default_factory=list)
    prompt: str = ""
    max_turns: int | None = None
    timeout_seconds: int | None = None


class AgentConfigSnapshot(BaseModel):
    model_profiles: list[ModelProfileSnapshot] = Field(default_factory=list)
    agents: list[AgentSnapshot] = Field(default_factory=list)


class RepoSnapshot(BaseModel):
    repo_id: str
    name: str = ""
    kind: str = "other"
    gitlab_project: str = ""
    default_branch: str = "main"
    clone_mode: str = "local"
    local_path: str = ""
    allowed_paths: list[str] = Field(default_factory=list)
    forbidden_paths: list[str] = Field(default_factory=list)
    test_commands: list[str] = Field(default_factory=list)


class CaseInput(BaseModel):
    # 旧 history 的散字段保留在 model_extra，仅用于无快照 replay。
    model_config = ConfigDict(extra="allow")

    case_id: str
    work_item_id: str
    prd_id: str
    system_id: str
    prd: "PrdSpec"
    repo_path: str = ""
    allowed_paths: list[str] = Field(default_factory=list)
    forbidden_paths: list[str] = Field(default_factory=list)
    test_commands: list[str] = Field(default_factory=list)
    repos: list[RepoSnapshot] = Field(default_factory=list)
    release_mode: str = "local"
    validation_mode: str = "auto"
    mr_target_branch: str = ""
    mr_labels: list[str] = Field(default_factory=list)
    agent_config_snapshot: AgentConfigSnapshot | None = None

    def effective_repos(self) -> list[RepoSnapshot]:
        # 旧 workflow history 没有 repos，继续使用原单仓字段。
        if self.repos:
            return self.repos
        return [RepoSnapshot(
            repo_id="main",
            name="main",
            local_path=self.repo_path,
            allowed_paths=self.allowed_paths,
            forbidden_paths=self.forbidden_paths,
            test_commands=self.test_commands,
        )]


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


class HandoffContext(BaseModel):
    role: str
    repo: str = ""
    summary: str
    diff_patch: str
    interface_notes: str = ""


class ExecutionRequest(BaseModel):
    model_config = ConfigDict(extra="allow")

    case_id: str
    work_item_id: str
    system_id: str
    repo_path: str
    repo: RepoSnapshot | None = None
    goal: str
    acceptance_criteria: list[str] = Field(default_factory=list)
    plan: "ExecutionPlan"
    memories: list[dict[str, Any]] = Field(default_factory=list)
    context_manifest_id: str = ""
    allowed_paths: list[str] = Field(default_factory=list)
    forbidden_paths: list[str] = Field(default_factory=list)
    test_commands: list[str] = Field(default_factory=list)
    agent_config_snapshot: AgentConfigSnapshot | None = None
    file_listing: str = ""
    file_contents: dict[str, str] = Field(default_factory=dict)
    previous_attempt: "PreviousAttempt | None" = None
    role_id: str = ""
    role_name: str = ""
    model_profile_id: str = ""
    role_scope: list[str] = Field(default_factory=list)
    role_prompt: str = ""
    handoff: list[HandoffContext] = Field(default_factory=list)
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
    interface_notes: str | None = None
    execution_provider: str = ""
    turns: int | None = None
    token_usage: dict[str, Any] = Field(default_factory=dict)
    role_id: str = ""
    repo: str = ""
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


class MergeRequestRef(BaseModel):
    repo: str
    mr_iid: int
    mr_url: str
    state: str = "opened"


class GitlabPublishResult(BaseModel):
    repo: str
    branch: str = ""
    commit_hash: str = ""
    merge_request: MergeRequestRef | None = None
    validation: ValidationResult = Field(default_factory=lambda: ValidationResult(passed=True))


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
    repo: str = ""
    scope_paths: list[str] = Field(default_factory=list)
    step_refs: list[str] = Field(default_factory=list)


class AvailableAgent(BaseModel):
    name: str
    engine: str
    path_scope: list[str] = Field(default_factory=list)


class PlanRequest(BaseModel):
    system_id: str
    prd: PrdSpec
    repo_summary: str = ""
    repos: list[RepoSnapshot] = Field(default_factory=list)
    memories: list[dict[str, Any]] = Field(default_factory=list)
    allowed_paths: list[str] = Field(default_factory=list)
    context_manifest_id: str
    available_agents: list[AvailableAgent] | None = None
    agent_config_snapshot: AgentConfigSnapshot | None = None


class RouteIndexInput(BaseModel):
    system_id: str
    repo_path: str = ""
    repos: list[RepoSnapshot] = Field(default_factory=list)

    def effective_repos(self) -> list[RepoSnapshot]:
        return self.repos or [RepoSnapshot(repo_id="main", name="main", local_path=self.repo_path)]


class KnowledgeCandidate(BaseModel):
    repo: str = "main"
    kind: str
    title: str
    anchorTexts: list[str] = Field(default_factory=list)
    routePath: str = ""
    apiEndpoints: list[str] = Field(default_factory=list)
    codeRefs: list[str] = Field(default_factory=list)
    sourceRef: str = ""
