"""Transport-independent tool contracts; no graph or provider imports."""
import json
from typing import Any, Literal
from ..editing import Validation

from pydantic import BaseModel, ConfigDict, Field, model_validator


class Contract(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Page(Contract):
    cursor: int = Field(default=0, ge=0)


class Overview(Page):
    asset_id: str


class Search(Page):
    query: str = Field(max_length=256)
    source_kinds: list[Literal["SPEECH", "VISUAL", "VISIBLE_TEXT"]] = Field(default_factory=lambda: ["SPEECH"])
    asset_ids: list[str] | None = None


class Range(Overview):
    start_ms: int = Field(ge=0)
    end_ms: int = Field(gt=0)

    @model_validator(mode="after")
    def ordered(self):
        if self.end_ms <= self.start_ms:
            raise ValueError("end_ms must exceed start_ms")
        return self


class Inspection(Range):
    goal: str = Field(min_length=1, max_length=500)


class ToolRequest(Contract):
    call_id: str
    tool_name: str
    arguments: dict[str, Any]
    request_hash: str


class ToolResult(Contract):
    call_id: str
    tool_name: str
    request_hash: str
    status: Literal["READY", "PENDING", "ERROR"]
    data: dict[str, Any] = Field(default_factory=dict)
    coverage: dict[str, Any] = Field(default_factory=dict)
    records: list[dict[str, Any]] = Field(default_factory=list)
    dependency: dict | None = None
    error: dict | None = None
    mock: bool = True

    def bounded(self, max_bytes):
        """Replace oversized payloads with an explicit error, preserving call identity."""
        payload = json.dumps(self.model_dump(), ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        if len(payload.encode("utf-8")) <= max_bytes:
            return self
        return ToolResult(call_id=self.call_id, tool_name=self.tool_name, request_hash=self.request_hash,
                          status="ERROR", mock=self.mock, error={"code": "RESULT_TOO_LARGE", "category": "MODEL_REPAIRABLE"})

    @model_validator(mode="after")
    def status_payload(self):
        if self.status == "PENDING" and not self.dependency:
            raise ValueError("PENDING requires dependency")
        if self.status == "ERROR" and not self.error:
            raise ValueError("ERROR requires structured error")
        return self


PARAMETERS = {"list_assets": Page, "get_video_overview": Overview, "search_evidence": Search,
              "read_video_range": Range, "inspect_video_range": Inspection, "validate_edit_draft": Validation}
