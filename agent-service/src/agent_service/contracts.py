from typing import Any

from pydantic import BaseModel, Field


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
