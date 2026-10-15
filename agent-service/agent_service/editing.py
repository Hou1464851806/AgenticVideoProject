"""M2 wire values. No graph, execution, evidence lookup or persistence dependencies."""
import json
from typing import Annotated, Any, ClassVar, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

MAX_INTEGER = 9223372036854775807
Identifier = Annotated[str, Field(min_length=1, max_length=200)]
OutputKey = Annotated[str, Field(min_length=1, max_length=64)]
Title = Annotated[str, Field(min_length=1, max_length=120)]
Milliseconds = Annotated[int, Field(ge=0, le=MAX_INTEGER)]
Duration = Annotated[int, Field(ge=1, le=MAX_INTEGER)]
Hash = Annotated[str, Field(pattern=r'^[0-9a-f]{64}$')]


class Contract(BaseModel):
    model_config = ConfigDict(extra='forbid', strict=True)
    byte_limit: ClassVar[int] = 65536

    @model_validator(mode='after')
    def bounded_payload(self):
        payload = json.dumps(self.model_dump(), ensure_ascii=False, sort_keys=True, separators=(',', ':'))
        if len(payload.encode('utf-8')) > self.byte_limit:
            raise ValueError('M2 payload exceeds byte limit')
        return self


class OutputCondition(Contract):
    output_key: OutputKey
    duration_min_ms: Duration | None = None
    duration_max_ms: Duration | None = None

    @model_validator(mode='after')
    def ordered_duration(self):
        if self.duration_min_ms is not None and self.duration_max_ms is not None and self.duration_min_ms > self.duration_max_ms:
            raise ValueError('duration_min_ms exceeds duration_max_ms')
        return self


class ProductionRequirements(Contract):
    topic: Annotated[str, Field(max_length=500)]
    asset_ids: list[Identifier] = Field(min_length=1, max_length=100)
    outputs: list[OutputCondition] = Field(min_length=1, max_length=10)
    profile: Literal['LANDSCAPE_720', 'PORTRAIT_720']
    subtitles_enabled: bool
    require_complete_subtitles: bool
    forbidden_content: list[Annotated[str, Field(min_length=1, max_length=256)]] = Field(max_length=100)

    @model_validator(mode='after')
    def unique_keys(self):
        if len({item.output_key for item in self.outputs}) != len(self.outputs):
            raise ValueError('duplicate output_key')
        return self


class Clip(Contract):
    asset_id: Identifier
    source_in_ms: Milliseconds
    source_out_ms: Duration
    evidence_ids: list[Identifier] = Field(min_length=1, max_length=50)
    reason: Annotated[str, Field(min_length=1, max_length=500)]


class Output(Contract):
    output_key: OutputKey
    title: Title
    clips: list[Clip] = Field(min_length=1, max_length=100)


class Draft(Contract):
    title: Title
    outputs: list[Output] = Field(min_length=1, max_length=10)

    @model_validator(mode='after')
    def unique_keys(self):
        if len({item.output_key for item in self.outputs}) != len(self.outputs):
            raise ValueError('duplicate output_key')
        return self


class Validation(Contract):
    draft: Draft
    requirements: ProductionRequirements
    draft_hash: Hash
    requirements_hash: Hash

    @model_validator(mode='after')
    def matching_keys(self):
        if {o.output_key for o in self.draft.outputs} != {o.output_key for o in self.requirements.outputs}:
            raise ValueError('draft and requirements output_keys differ')
        return self


class ValidationIssue(Contract):
    code: str
    field: str
    message: str
    output_key: OutputKey | None = None
    actual: Any = None
    expected: Any = None
    hint: str | None = None


class ValidationResult(Contract):
    byte_limit: ClassVar[int] = 32768
    validation_id: Identifier
    valid: bool
    draft_hash: Hash
    requirements_hash: Hash
    durations_ms: list[Milliseconds] = Field(max_length=10)
    issues: list[ValidationIssue]
    issues_truncated: bool
    warnings: list[dict[str, Any]]


class SavedOutput(Contract):
    output_key: OutputKey
    output_id: Identifier
    duration_ms: Duration


class SavedResult(Contract):
    byte_limit: ClassVar[int] = 32768
    status: Literal['SAVED']
    plan_id: Identifier
    revision_id: Identifier
    revision_number: Annotated[int, Field(ge=1, le=MAX_INTEGER)]
    outputs: list[SavedOutput] = Field(min_length=1, max_length=10)


class ProductionBinding(Contract):
    """Trusted context, carried outside model-owned draft/tool arguments."""
    project_id: Identifier
    requirement_id: Identifier
    scope_id: Identifier


class SaveRequest(ProductionBinding):
    validation_id: Identifier
    draft_hash: Hash
    requirements_hash: Hash
    requirements_sources: dict[Annotated[str, Field(min_length=1)], dict] = Field(min_length=1, max_length=1000)
