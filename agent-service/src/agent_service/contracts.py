from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field


class PrdContent(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, populate_by_name=True)

    title: str | None = None
    goal: str | None = None
    scope: str | None = None
    acceptance_criteria: list[str] = Field(default_factory=list, alias="acceptanceCriteria")


class DraftRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    system_id: str
    content: str
    current_draft: PrdContent = Field(default_factory=PrdContent)
    missing_fields: list[str] = Field(default_factory=list)
    conversation_history: list[dict[str, Any]] = Field(default_factory=list)
    context_items: list[dict[str, Any]] = Field(default_factory=list)


class PrdPatch(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, populate_by_name=True)

    title: str | None = None
    goal: str | None = None
    scope: str | None = None
    acceptance_criteria: list[str] | None = Field(default=None, alias="acceptanceCriteria")


class DraftResult(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    patch: PrdPatch
    assistant_message: str
    citations: dict[str, list[str]]


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
