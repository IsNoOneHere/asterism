from enum import StrEnum
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field

PLAN_BASE_CHANGED_ERROR = "PlanBaseChanged"


def _to_camel(value: str) -> str:
    head, *tail = value.split("_")
    return head + "".join(part[:1].upper() + part[1:] for part in tail)


class ApiModel(BaseModel):
    """跨 Java API 的模型统一接受 snake_case，并按需输出 camelCase。"""

    model_config = ConfigDict(alias_generator=_to_camel, populate_by_name=True)


class ArtifactRef(ApiModel):
    artifact_id: str
    artifact_type: Literal["PRODUCT", "PLANNING", "CODING", "VALIDATION", "RELEASE"]
    version: int = Field(ge=1)
    content_hash: str
    root_artifact_id: str
    parent_artifact_id: str | None = None
    supersedes_artifact_id: str | None = None
    status: Literal["PROPOSED", "APPROVED", "REJECTED", "SUPERSEDED"]


ArtifactTransitionKind = Literal[
    "ProposePlanningArtifact",
    "ApprovePlanningArtifact",
    "RejectPlanningArtifact",
    "ProposeCodingArtifact",
    "ApproveCodingArtifact",
    "RejectCodingArtifact",
    "SupersedeArtifact",
]


class ArtifactTransitionRequest(ApiModel):
    kind: ArtifactTransitionKind
    transition_id: str
    artifact: ArtifactRef | None = None
    parent: ArtifactRef | None = None
    supersedes: ArtifactRef | None = None
    expected_head: ArtifactRef | None = None
    content: dict[str, Any] | None = None
    note: str = ""


ArtifactEvidenceType = Literal[
    "PlanningExecution",
    "CodingExecution",
    "WorkerBlocked",
    "ReworkStarted",
    "RevisionRequested",
    "PatchApplied",
    "PatchApplyBlocked",
    "PatchRejected",
    "ValidationPassed",
    "ValidationFailed",
    "RepositoryReleasePrepared",
    "MergeRequestCreated",
    "MergeRequestMerged",
    "MergeRequestClosed",
    "ReleaseCompleted",
]


class ArtifactEvidenceRequest(ApiModel):
    evidence_id: str
    artifact: ArtifactRef | None = None
    evidence_type: ArtifactEvidenceType
    transition_id: str | None = None
    payload: dict[str, Any] = Field(default_factory=dict)


class ProjectionResult(ApiModel):
    event: dict[str, Any]
    artifact_ref: ArtifactRef | None = None
    transition: dict[str, Any] | None = None
    evidence: dict[str, Any] | None = None


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


class ProjectionEvent(ApiModel):
    event_type: str
    system_id: str
    case_id: str
    prd_id: str
    work_item_id: str
    actor_id: str = "worker"
    payload: dict[str, Any] = Field(default_factory=dict)
    correlation_id: str
    causation_id: str | None = None
    idempotency_key: str
    artifact_transition: ArtifactTransitionRequest | None = None
    artifact_evidence: ArtifactEvidenceRequest | None = None


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


class CodingPlanDraft(BaseModel):
    """模型生成可读计划，系统只补充可确定的会话与 Git 基线。"""

    plan_markdown: str = Field(min_length=1)
    revision: int = Field(default=1, ge=1)
    session_id: str = ""
    turns: int | None = None
    token_usage: dict[str, Any] = Field(default_factory=dict)
    base_revisions: dict[str, str] = Field(default_factory=dict)
    acceptance_criteria_refs: list[str] = Field(default_factory=list)
    repositories: list[str] = Field(default_factory=list)
    evidence_refs: list[str] = Field(default_factory=list)
    risks: list[str] = Field(default_factory=list)
    open_questions: list[str] = Field(default_factory=list)


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


class ExecutionOutcome(BaseModel):
    """系统依据 SDK 终态与真实仓库事实生成的 Attempt 结果。"""

    status: Literal["completed", "blocked"]
    blockers: list[str] = Field(default_factory=list)
    changed_paths: list[str] = Field(default_factory=list)
    session_id: str = ""


class CodingAttemptResult(BaseModel):
    attempt_id: str = ""
    summary: str
    outcome: ExecutionOutcome
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
    # 兼容 Artifact Layer 上线前仍在运行的 Temporal Case。
    title: str = ""
    goal: str = ""
    acceptance_criteria: list[str] = Field(default_factory=list)
    draft_json: dict[str, Any] = Field(default_factory=dict)
    requirement_manifest_id: str
    product_artifact: ArtifactRef | None = None


class ContextSnapshot(ApiModel):
    # 新字段提供默认值，确保旧 Workflow 的 Activity 历史可以继续反序列化。
    snapshot_id: str = ""
    snapshot_hash: str = ""
    root_artifact_id: str = ""
    source_artifacts: list[ArtifactRef] = Field(default_factory=list)
    relationships: list[dict[str, Any]] = Field(default_factory=list)
    effective_heads: dict[str, ArtifactRef] = Field(default_factory=dict)
    system_id: str
    requirement_manifest_id: str
    requirement_items: list[dict[str, Any]] = Field(default_factory=list)
    execution_bundle_id: str = ""
    execution_items: list[dict[str, Any]] = Field(default_factory=list)
    git_base_revisions: dict[str, str] = Field(default_factory=dict)
    built_at: str = ""
    stale_references: list[str] = Field(default_factory=list)
    product_artifact: ArtifactRef | None = None
    product_content: dict[str, Any] = Field(default_factory=dict)
    planning_artifact: ArtifactRef | None = None
    planning_content: dict[str, Any] = Field(default_factory=dict)
    previous_artifact: ArtifactRef | None = None
    previous_content: dict[str, Any] = Field(default_factory=dict)
    previous_transitions: list[dict[str, Any]] = Field(default_factory=list)
    feedback_notes: list[str] = Field(default_factory=list)


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


class ProductAgentExecutionInput(BaseModel):
    """Product Agent 独立 Workflow 的持久化输入契约。"""

    execution_id: str
    workflow_id: str
    system_id: str
    prd_id: str
    conversation_id: str
    input_message_id: str
    context_bundle_id: str
    content: str
    attachment_ids: list[str] = Field(default_factory=list)
    current_draft: dict[str, Any] = Field(default_factory=dict)
    missing_fields: list[str] = Field(default_factory=list)
    conversation_history: list[dict[str, Any]] = Field(default_factory=list)
    context_items: list[dict[str, Any]] = Field(default_factory=list)
    attempt: int = Field(default=1, ge=1)


class ProductDraftActivityInput(BaseModel):
    """传给同一 Runner 内 Product Agent Activity 的持久化输入。"""

    execution_id: str
    attempt: int = Field(default=1, ge=1)
    system_id: str
    content: str
    attachment_ids: list[str] = Field(default_factory=list)
    current_draft: dict[str, Any] = Field(default_factory=dict)
    missing_fields: list[str] = Field(default_factory=list)
    conversation_history: list[dict[str, Any]] = Field(default_factory=list)
    context_items: list[dict[str, Any]] = Field(default_factory=list)


class ProductAgentDraftResult(BaseModel):
    """Worker 与新版 /prd-draft 之间的最小结果契约。"""

    patch: dict[str, Any]
    assistant_message: str
    citations: dict[str, list[str]] = Field(default_factory=dict)


class ProductImageObservation(BaseModel):
    page_title: str = ""
    text_anchors: list[str] = Field(default_factory=list)
    ui_elements: list[dict[str, str]] = Field(default_factory=list)
    error_messages: list[str] = Field(default_factory=list)
    user_visible_summary: str = ""

    def anchors(self) -> list[str]:
        return list(dict.fromkeys([
            value for value in [self.page_title, *self.text_anchors, *self.error_messages]
            if value.strip()
        ]))


class ProductAgentActivityResult(BaseModel):
    """Activity 只返回可持久化的草稿与图片观察，绝不返回图片字节。"""

    result: ProductAgentDraftResult
    observations: list[ProductImageObservation] = Field(default_factory=list)
    image_analysis_failed: bool = False
