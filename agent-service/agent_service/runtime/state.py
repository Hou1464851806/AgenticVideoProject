"""Serializable workflow values, separated from live clients and credentials."""
from dataclasses import dataclass
from typing import Any, Literal, TypedDict

from pydantic import BaseModel, ConfigDict, Field, model_validator


class Value(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Requirements(Value):
    mode: Literal["QUERY", "DRAFT", "DISCUSS", "UNSUPPORTED"] = "QUERY"
    topic: str = ""
    duration_min_ms: int | None = Field(default=None, ge=1)
    duration_max_ms: int | None = Field(default=None, ge=1)
    output_count: int = Field(default=1, ge=1, le=10)
    asset_ids: list[str] | None = None
    forbidden_content: list[str] = Field(default_factory=list)
    sources: dict[str, str] = Field(default_factory=dict)

    @model_validator(mode="after")
    def duration_range(self):
        if self.duration_min_ms and self.duration_max_ms and self.duration_min_ms > self.duration_max_ms:
            raise ValueError("duration_min_ms exceeds duration_max_ms")
        return self


class Understanding(Value):
    relation: Literal["NEW", "CONTINUE", "AMBIGUOUS"]
    mode: Literal["QUERY", "DRAFT", "DISCUSS", "UNSUPPORTED"]
    topic: str | None = None
    duration_min_ms: int | None = None
    duration_max_ms: int | None = None
    output_count: int | None = None
    asset_ids: list[str] | None = None
    forbidden_content: list[str] | None = None
    question: str | None = None
    defaults: list[str] = Field(default_factory=list)


class Clip(Value):
    asset_id: str
    source_in_ms: int = Field(ge=0)
    source_out_ms: int = Field(gt=0)
    evidence_ids: list[str] = Field(min_length=1)
    reason: str


class Output(Value):
    title: str
    clips: list[Clip] = Field(min_length=1, max_length=100)


class Draft(Value):
    title: str
    outputs: list[Output] = Field(min_length=1, max_length=10)


class Action(Value):
    kind: Literal["TOOL", "ASK", "ANSWER", "DRAFT"]
    tool_name: str | None = None
    arguments: dict[str, Any] = Field(default_factory=dict)
    question: str | None = None
    content: str | None = None
    evidence_ids: list[str] = Field(default_factory=list)
    draft: Draft | None = None


class State(TypedDict, total=False):
    identity: dict
    request: dict
    requirements: dict
    messages: list
    observations: dict
    coverage: dict
    draft: dict | None
    validation: dict | None
    action: dict | None
    pending_tool: dict | None
    feedback: dict | None
    waiting: dict | None
    resume_input: dict | None
    usage: dict
    outcome: dict | None
    model_sequence: int
    understanding: dict | None
    next_node: str
    stop_reason: str | None
    scenario: str


@dataclass
class Context:
    model: Any
    executor: Any
    store: Any
    limits: Any
    tracer: Any
    stop: Any
    progress: Any
    assets: dict


def merge_requirements(current: dict, parsed: Understanding, message_id: str) -> dict:
    values = dict(current)
    sources = dict(values.get("sources", {}))
    values["mode"] = parsed.mode
    for key in ("topic", "duration_min_ms", "duration_max_ms", "output_count", "asset_ids", "forbidden_content"):
        value = getattr(parsed, key)
        if value is not None:
            values[key] = value
            sources[key] = message_id
    values["sources"] = sources
    return Requirements.model_validate(values).model_dump()
