"""Serializable workflow values, separated from live clients and credentials."""
from dataclasses import dataclass
from typing import Any, Literal, TypedDict

from pydantic import BaseModel, ConfigDict, Field

from ..editing import Draft, OutputCondition, ProductionRequirements


class Value(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Requirements(Value):
    mode: Literal["QUERY", "DRAFT", "DISCUSS", "UNSUPPORTED"] = "QUERY"
    topic: str = ""
    asset_ids: list[str] | None = None
    outputs: list[OutputCondition] = Field(default_factory=lambda: [OutputCondition(output_key="out-1")], min_length=1, max_length=10)
    profile: Literal["LANDSCAPE_720", "PORTRAIT_720"] = "LANDSCAPE_720"
    subtitles_enabled: bool = True
    require_complete_subtitles: bool = False
    forbidden_content: list[str] = Field(default_factory=list)
    sources: dict[str, dict] = Field(default_factory=lambda: {
        "outputs": {"kind": "DEFAULT", "value": 1},
        "profile": {"kind": "DEFAULT", "value": "LANDSCAPE_720"},
        "subtitles_enabled": {"kind": "DEFAULT", "value": True},
        "require_complete_subtitles": {"kind": "DEFAULT", "value": False},
        "outputs.out-1.duration_min_ms": {"kind": "DEFAULT", "value": None},
        "outputs.out-1.duration_max_ms": {"kind": "DEFAULT", "value": None}})

    def production(self, asset_ids: list[str]) -> dict:
        values = self.model_dump(exclude={"mode", "sources"})
        values["asset_ids"] = self.asset_ids if self.asset_ids is not None else asset_ids
        return ProductionRequirements.model_validate(values).model_dump()


class Understanding(Value):
    relation: Literal["NEW", "CONTINUE", "AMBIGUOUS"]
    mode: Literal["QUERY", "DRAFT", "DISCUSS", "UNSUPPORTED"]
    topic: str | None = None
    duration_min_ms: int | None = None
    duration_max_ms: int | None = None
    output_count: int | None = Field(default=None, ge=1, le=10)
    output_durations: list[OutputCondition] = Field(default_factory=list)
    profile: Literal["LANDSCAPE_720", "PORTRAIT_720"] | None = None
    subtitles_enabled: bool | None = None
    require_complete_subtitles: bool | None = None
    asset_ids: list[str] | None = None
    forbidden_content: list[str] | None = None
    question: str | None = None
    defaults: list[str] = Field(default_factory=list)


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
    question_scope: dict | None


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
    mock: bool = True


def merge_requirements(current: dict, parsed: Understanding, message_id: str, message: str = "") -> dict:
    values = Requirements.model_validate(current).model_dump()
    sources = values["sources"]
    source = {"kind": "USER", "message_id": message_id, "text": message}
    values["mode"] = parsed.mode
    for key in ("topic", "asset_ids", "forbidden_content", "profile", "subtitles_enabled", "require_complete_subtitles"):
        value = getattr(parsed, key)
        if value is not None:
            values[key] = value
            sources[key] = source
    outputs = values["outputs"]
    if parsed.output_count is not None:
        next_number = max(int(o["output_key"].removeprefix("out-")) for o in outputs) + 1
        outputs = outputs[:parsed.output_count]
        while len(outputs) < parsed.output_count:
            key = f"out-{next_number}"
            outputs.append(OutputCondition(output_key=key).model_dump())
            for field in ("duration_min_ms", "duration_max_ms"):
                sources[f"outputs.{key}.{field}"] = {"kind": "DEFAULT", "value": None}
            next_number += 1
        values["outputs"] = outputs
        sources["outputs"] = source
    for output in outputs:
        for field in ("duration_min_ms", "duration_max_ms"):
            value = getattr(parsed, field)
            if value is not None:
                output[field] = value
                sources[f"outputs.{output['output_key']}.{field}"] = source
    for update in parsed.output_durations:
        matches = [o for o in outputs if o["output_key"] == update.output_key]
        if not matches:
            raise ValueError("output_key is not in current requirements")
        for field in ("duration_min_ms", "duration_max_ms"):
            value = getattr(update, field)
            if value is not None:
                matches[0][field] = value
                sources[f"outputs.{update.output_key}.{field}"] = source
    return Requirements.model_validate(values).model_dump()
