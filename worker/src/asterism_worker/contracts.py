from enum import StrEnum
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field

PLAN_BASE_CHANGED_ERROR = "PlanBaseChanged"


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
    image_input: bool = False
    structured_output: Literal["json_schema", "json_object", "prompt_only"] = "json_object"


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
    execution_architecture: str = "claude_sdk_team"
    max_revisions: int = Field(default=5, ge=1, le=20)

    def effective_repos(self) -> list[RepoSnapshot]:
        # 单仓调用可继续使用顶层字段，多仓统一读取 repos。
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


class PatchApplyResult(BaseModel):
    blocked: bool = False
    reason: str = ""
    already_applied: bool = False


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
    repo: str = ""
    engine: str = ""
    changed_paths: list[str] = Field(default_factory=list)
    session_id: str = ""
    subagent_runs: list["SubagentRun"] = Field(default_factory=list)

    @property
    def passes_diff_gate(self) -> bool:
        return bool(self.diff_patch.strip()) and "diff --git" in self.diff_patch


class RepoChangeResult(BaseModel):
    repo: str
    diff_patch: str = ""
    changed_paths: list[str] = Field(default_factory=list)
    summary: str = ""


class SubagentRun(BaseModel):
    agent_id: str
    agent_type: str
    repo: str = ""
    status: str = "completed"


class RevisionContext(BaseModel):
    """人工修订的结构化上下文，供 Supervisor 精确收敛修改范围。"""

    revision: int = Field(ge=1)
    revision_mode: Literal["incremental", "full"]
    feedback: str
    previous_diff_summary: list[dict[str, Any]] = Field(default_factory=list)
    instruction: str = "只修订人工意见涉及的部分，不推翻已通过的改动"


class CodingPlanTask(BaseModel):
    """Supervisor 基于真实仓库证据给出的单仓任务。"""

    task_id: str = ""
    repo: str
    objective: str
    acceptance_criteria_refs: list[str] = Field(default_factory=list)
    evidence: list[str] = Field(default_factory=list)


class CodingPlanTaskProposal(BaseModel):
    """模型只负责提出业务任务，不得生成系统身份字段。"""

    model_config = ConfigDict(extra="forbid", strict=True)

    repo: str
    objective: str
    acceptance_criteria_refs: list[str] = Field(default_factory=list)
    evidence: list[str] = Field(default_factory=list)


class CodingPlanProposal(BaseModel):
    """Claude SDK 结构化输出的唯一规划契约。"""

    model_config = ConfigDict(extra="forbid", strict=True)

    summary: str
    tasks: list[CodingPlanTaskProposal] = Field(min_length=1)
    risks: list[str] = Field(default_factory=list)
    open_questions: list[str] = Field(default_factory=list)


class CodingPlanDraft(BaseModel):
    """可供人工审批的轻量执行计划；路径证据不参与权限裁决。"""

    summary: str
    tasks: list[CodingPlanTask] = Field(min_length=1)
    risks: list[str] = Field(default_factory=list)
    open_questions: list[str] = Field(default_factory=list)
    revision: int = Field(default=1, ge=1)
    session_id: str = ""
    base_revisions: dict[str, str] = Field(default_factory=dict)


class CodingPlanRequest(BaseModel):
    case_id: str
    work_item_id: str
    system_id: str
    repos: list[RepoSnapshot]
    goal: str
    acceptance_criteria: list[str] = Field(default_factory=list)
    feedback: str = ""
    requirement_context: list[dict[str, Any]] = Field(default_factory=list)
    execution_context: list[dict[str, Any]] = Field(default_factory=list)
    requirement_manifest_id: str
    execution_bundle_id: str = ""
    agent_config_snapshot: AgentConfigSnapshot | None = None
    plan_revision: int = Field(default=1, ge=1)
    previous_plan: CodingPlanDraft | None = None
    resume_session_id: str = ""
    refresh_workspace: bool = False


class CodingAttemptRequest(BaseModel):
    attempt_id: str = ""
    case_id: str
    work_item_id: str
    system_id: str
    repos: list[RepoSnapshot]
    goal: str
    acceptance_criteria: list[str] = Field(default_factory=list)
    feedback: str = ""
    requirement_context: list[dict[str, Any]] = Field(default_factory=list)
    execution_context: list[dict[str, Any]] = Field(default_factory=list)
    requirement_manifest_id: str
    execution_bundle_id: str = ""
    agent_config_snapshot: AgentConfigSnapshot | None = None
    previous_candidate: list[RepoChangeResult] = Field(default_factory=list)
    revision_context: RevisionContext | None = None
    approved_plan: CodingPlanDraft | None = None
    resume_session_id: str = ""


class ExecutionTaskOutcome(BaseModel):
    """已批准计划中单个任务的顶层执行结果，不映射为子 Agent Stage。"""

    task_id: str
    status: Literal["completed", "blocked"]
    summary: str = ""
    changed_paths: list[str] = Field(default_factory=list)


class ExecutionOutcome(BaseModel):
    """Root Supervisor 对整个 Coding Attempt 一次性负责的结构化终态。"""

    status: Literal["completed", "blocked"]
    task_outcomes: list[ExecutionTaskOutcome] = Field(default_factory=list)
    blockers: list[str] = Field(default_factory=list)
    changed_paths: list[str] = Field(default_factory=list)
    session_id: str = ""


class CodingAttemptResult(BaseModel):
    attempt_id: str = ""
    summary: str
    # 仅允许旧 Temporal Activity history 缺失；新 Provider 必须返回结构化 outcome。
    outcome: ExecutionOutcome | None = None
    repo_changes: list[RepoChangeResult] = Field(default_factory=list)
    subagent_runs: list[SubagentRun] = Field(default_factory=list)
    token_usage: dict[str, Any] = Field(default_factory=dict)
    session_id: str = ""
    turns: int | None = None
    execution_provider: str = "claude_sdk_team"
    revision_mode: Literal["incremental", "full"] | None = None


class ReleaseResult(BaseModel):
    branch: str
    commit_hash: str
    push_failed: str = ""


class MergeRequestRef(BaseModel):
    repo: str
    mr_iid: int
    mr_url: str
    state: str = "opened"
    project: str = ""


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
    requirement_manifest_id: str


class ContextSnapshot(BaseModel):
    system_id: str
    requirement_manifest_id: str
    requirement_items: list[dict[str, Any]] = Field(default_factory=list)
    execution_bundle_id: str = ""
    execution_items: list[dict[str, Any]] = Field(default_factory=list)
    stale_references: list[str] = Field(default_factory=list)


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
