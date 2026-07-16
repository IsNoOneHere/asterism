from typing import Any

from pydantic import BaseModel, ConfigDict, Field


class PrdSpec(BaseModel):
    title: str = ""
    goal: str
    acceptance_criteria: list[str] = Field(default_factory=list)
    draft_json: dict[str, Any] = Field(default_factory=dict)


class AgentAssignment(BaseModel):
    role: str
    repo: str = ""
    scope_paths: list[str] = Field(default_factory=list)
    step_refs: list[str] = Field(default_factory=list)


class AvailableAgent(BaseModel):
    name: str
    engine: str
    path_scope: list[str] = Field(default_factory=list)


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


class ModelProfileSnapshot(BaseModel):
    id: str
    name: str = ""
    provider: str = "openai-compat"
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


class ExecutionPlan(BaseModel):
    steps: list[str] = Field(default_factory=list)
    target_files: list[str] = Field(default_factory=list)
    test_plan: list[str] = Field(default_factory=list)
    risks: list[str] = Field(default_factory=list)
    assignments: list[AgentAssignment] = Field(default_factory=list)


class PlanRequest(BaseModel):
    system_id: str
    prd: PrdSpec
    repo_summary: str = ""
    repos: list[RepoSnapshot] = Field(default_factory=list)
    memories: list[dict[str, Any]] = Field(default_factory=list)
    allowed_paths: list[str] = Field(default_factory=list)
    context_manifest_id: str
    available_agents: list[AvailableAgent] = Field(default_factory=list)
    agent_config_snapshot: AgentConfigSnapshot | None = None


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
    plan: ExecutionPlan
    memories: list[dict[str, Any]] = Field(default_factory=list)
    context_manifest_id: str = ""
    allowed_paths: list[str] = Field(default_factory=list)
    forbidden_paths: list[str] = Field(default_factory=list)
    test_commands: list[str] = Field(default_factory=list)
    file_listing: str = ""
    file_contents: dict[str, str] = Field(default_factory=dict)
    previous_attempt: dict[str, str] | None = None
    role_id: str = ""
    role_name: str = ""
    model_profile_id: str = ""
    role_scope: list[str] = Field(default_factory=list)
    role_prompt: str = ""
    handoff: list[HandoffContext] = Field(default_factory=list)
    assignment_index: int = 0
    step_refs: list[str] = Field(default_factory=list)
    agent_config_snapshot: AgentConfigSnapshot | None = None


class ExecutionResult(BaseModel):
    summary: str = "llm diff generated"
    diff_patch: str
    interface_notes: str | None = None


class DraftRequest(BaseModel):
    system_id: str
    content: str
    current_draft: dict[str, Any] = Field(default_factory=dict)
    missing_fields: list[str] = Field(default_factory=list)
    conversation_history: list[dict[str, Any]] = Field(default_factory=list)
    approved_memories: list[Any] = Field(default_factory=list)


class DraftResult(BaseModel):
    title: str
    draft: dict[str, Any]
    missing_fields: list[str] = Field(default_factory=list)
    assistant_message: str


class UiObservation(BaseModel):
    page_title: str = ""
    text_anchors: list[str] = Field(default_factory=list)
    ui_elements: list[str] = Field(default_factory=list)
    error_messages: list[str] = Field(default_factory=list)
    user_visible_summary: str = ""
