from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field


class DraftRequest(BaseModel):
    system_id: str
    content: str
    current_draft: dict[str, Any] = Field(default_factory=dict)
    missing_fields: list[str] = Field(default_factory=list)
    conversation_history: list[dict[str, Any]] = Field(default_factory=list)
    context_items: list[dict[str, Any]] = Field(default_factory=list)


class PrdDraftPayload(BaseModel):
    model_config = ConfigDict(extra="allow", populate_by_name=True)

    title: str
    goal: str
    scope: str
    acceptance_criteria: list[str] = Field(alias="acceptanceCriteria")


class DraftResult(BaseModel):
    title: str
    draft: PrdDraftPayload
    # 仅用于辅助模型组织追问文案，生命周期状态由 Java 根据 draft 内容重新计算。
    missing_fields: list[str] = Field(
        default_factory=list,
        description="Suggestion for assistant_message only; never authoritative for lifecycle state.",
    )
    assistant_message: str
    used_context_refs: list[str] = Field(default_factory=list)
    citations: dict[str, list[str]] = Field(default_factory=dict)
    memory_candidates: list[dict[str, Any]] = Field(default_factory=list)


class UiElement(BaseModel):
    type: str
    description: str


class UiObservation(BaseModel):
    page_title: str = ""
    text_anchors: list[str] = Field(default_factory=list)
    ui_elements: list[UiElement] = Field(default_factory=list)
    error_messages: list[str] = Field(default_factory=list)
    user_visible_summary: str = ""


class StructuredOutputProbe(BaseModel):
    marker: Literal["asterism"]
    count: Literal[1]


def normalize_draft_result(value: Any) -> Any:
    # 仅修复已确认安全且可枚举的形态，其他非法结构交给 Pydantic 拒绝。
    if not isinstance(value, dict):
        return value
    normalized = dict(value)
    draft = value.get("draft")
    if isinstance(draft, dict) and "acceptanceCriteria" in draft:
        normalized_draft = dict(draft)
        normalized_draft["acceptanceCriteria"] = _normalize_acceptance_criteria(
            draft["acceptanceCriteria"],
        )
        normalized["draft"] = normalized_draft
    if isinstance(value.get("citations"), dict):
        normalized["citations"] = {
            key: [refs] if isinstance(refs, str) else refs
            for key, refs in value["citations"].items()
        }
    return normalized


def _normalize_acceptance_criteria(value: Any) -> Any:
    if isinstance(value, str):
        return [line.strip() for line in value.splitlines() if line.strip()]
    if not isinstance(value, list) or not all(isinstance(item, dict) for item in value):
        return value
    normalized: list[str] = []
    for item in value:
        text = item.get("text")
        if not isinstance(text, str):
            text = item.get("description")
        if not isinstance(text, str):
            return value
        normalized.append(text)
    return normalized


def normalize_ui_observation(value: Any) -> Any:
    # 旧模型返回字符串元素时升级为结构化对象，其他非法形态交给 Pydantic 拒绝。
    if not isinstance(value, dict) or not isinstance(value.get("ui_elements"), list):
        return value
    normalized = dict(value)
    normalized["ui_elements"] = [
        {"type": "element", "description": item} if isinstance(item, str) else item
        for item in value["ui_elements"]
    ]
    return normalized
