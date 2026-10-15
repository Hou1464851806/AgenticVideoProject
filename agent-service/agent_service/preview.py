"""M4 preview wire values, independent of Agent state and model tools."""
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

Identifier = Annotated[str, Field(pattern=r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')]
OutputKey = Annotated[str, Field(min_length=1, max_length=64)]
TaskStatus = Literal['PENDING', 'RUNNING', 'CANCEL_REQUESTED', 'SUCCEEDED', 'FAILED', 'CANCELLED']


class Contract(BaseModel):
    model_config = ConfigDict(extra='forbid', strict=True)


class PreviewRequest(Contract):
    output_key: OutputKey | None = None

    @model_validator(mode='before')
    @classmethod
    def selection_is_optional_but_not_null(cls, value):
        if isinstance(value, dict) and 'output_key' in value and value['output_key'] is None:
            raise ValueError('output_key must be omitted or a nonempty string')
        return value


class Artifact(Contract):
    artifact_id: Identifier
    state: Literal['AVAILABLE', 'UNAVAILABLE', 'REMOVED']
    duration_ms: Annotated[int, Field(gt=0)]


class Playback(Contract):
    url: str
    expires_in_seconds: Annotated[int, Field(gt=0)]


class PreviewError(Contract):
    code: str
    message: str


class PreviewResult(Contract):
    request_id: Identifier
    project_id: Identifier
    revision_id: Identifier
    output_id: Identifier
    task_id: Identifier
    task_status: TaskStatus
    created_at: str
    artifact: Artifact | None
    playback: Playback | None
    retry_allowed: bool
    error: PreviewError | None


class PreviewListItem(Contract):
    request_id: Identifier
    output_key: OutputKey
    output_id: Identifier
    task_id: Identifier
    task_status: TaskStatus
    created_at: str


class PreviewList(Contract):
    requirement_id: Identifier
    revision_id: Identifier
    items: list[PreviewListItem]
