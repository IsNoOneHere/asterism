from typing import Any

from pydantic import BaseModel, Field


class DraftRequest(BaseModel):
    system_id: str
    content: str
    current_draft: dict[str, Any] = Field(default_factory=dict)
    missing_fields: list[str] = Field(default_factory=list)
    conversation_history: list[dict[str, Any]] = Field(default_factory=list)
    context_items: list[dict[str, Any]] = Field(default_factory=list)


class DraftResult(BaseModel):
    title: str
    draft: dict[str, Any]
    missing_fields: list[str] = Field(default_factory=list)
    assistant_message: str
    used_context_refs: list[str] = Field(default_factory=list)
    citations: dict[str, list[str]] = Field(default_factory=dict)
    memory_candidates: list[dict[str, Any]] = Field(default_factory=list)


class UiObservation(BaseModel):
    page_title: str = ""
    text_anchors: list[str] = Field(default_factory=list)
    ui_elements: list[str] = Field(default_factory=list)
    error_messages: list[str] = Field(default_factory=list)
    user_visible_summary: str = ""
