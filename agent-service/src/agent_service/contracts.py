from typing import Any

from pydantic import BaseModel, Field


class PrdSpec(BaseModel):
    title: str = ""
    goal: str
    acceptance_criteria: list[str] = Field(default_factory=list)
    draft_json: dict[str, Any] = Field(default_factory=dict)


class AgentAssignment(BaseModel):
    role: str
    scope_paths: list[str] = Field(default_factory=list)
    step_refs: list[str] = Field(default_factory=list)


class AvailableRole(BaseModel):
    id: str
    name: str = ""
    engine: str
    path_scope: list[str] = Field(default_factory=list)


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
    memories: list[dict[str, Any]] = Field(default_factory=list)
    allowed_paths: list[str] = Field(default_factory=list)
    context_manifest_id: str
    available_roles: list[AvailableRole] = Field(default_factory=list)


class ExecutionRequest(BaseModel):
    case_id: str
    work_item_id: str
    system_id: str
    repo_path: str
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
    handoff_summary: str = ""
    assignment_index: int = 0
    step_refs: list[str] = Field(default_factory=list)


class ExecutionResult(BaseModel):
    summary: str
    diff_patch: str


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
