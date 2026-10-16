"""Browser Agent transport values, independent of graph, HTTP and worker scheduling.

Java owns camelCase wire identities; Python uses snake_case internally. Attempt/lease
credentials are transport metadata and are deliberately outside the fixed input hash.
"""
import json
from typing import Annotated, Any, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator
from pydantic.alias_generators import to_camel

Id = Annotated[str, Field(pattern=r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')]
Text = Annotated[str, Field(min_length=1, max_length=16000)]
Hash = Annotated[str, Field(pattern=r'^[0-9a-f]{64}$')]
Generation = Annotated[int, Field(ge=0, le=9223372036854775807)]


class WireValue(BaseModel):
    model_config = ConfigDict(extra='forbid', strict=True, alias_generator=to_camel)

    @model_validator(mode='before')
    @classmethod
    def canonical_json(cls, value):
        # Shared fingerprint keys use ASCII names; values retain Unicode text.
        pending = [value]
        while pending:
            item = pending.pop()
            if isinstance(item, float) or (type(item) is int and not -9223372036854775808 <= item <= 9223372036854775807):
                raise ValueError('Agent JSON numbers must be signed 64-bit integers')
            if isinstance(item, dict):
                if any(not key.isascii() for key in item):
                    raise ValueError('Agent JSON field names must be ASCII')
                pending.extend(item.values())
            elif isinstance(item, list):
                pending.extend(item)
        return value

    @model_validator(mode='after')
    def bounded(self):
        if len(json.dumps(self.model_dump(by_alias=True), ensure_ascii=False, separators=(',', ':')).encode()) > 1048576:
            raise ValueError('Agent payload exceeds byte limit')
        return self


class Identity(WireValue):
    project_id: Id
    conversation_id: Id
    message_id: Id
    turn_id: Id
    task_id: Id
    thread_id: Id
    execution_id: Id


class HistoryMessage(WireValue):
    message_id: Id
    role: Literal['USER', 'ASSISTANT']
    content: Text
    references: list[dict[str, Any]] = Field(max_length=100)


class DraftBinding(WireValue):
    message_id: Id
    validation_id: Id
    edit_generation: Generation
    draft_hash: Hash
    requirements_hash: Hash


class Production(WireValue):
    production_id: Id
    scope_id: Id
    thread_id: Id
    edit_generation: Generation
    requirements: dict[str, Any]
    requirements_sources: dict[str, dict[str, Any]]
    current_draft: DraftBinding | None
    frozen: bool


class PendingQuestion(WireValue):
    question_id: Id
    task_id: Id
    production_id: Id | None
    thread_id: Id
    execution_id: Id
    wait_id: Annotated[str, Field(min_length=1, max_length=200)]
    edit_generation: Generation | None
    prompt: Text

    @model_validator(mode='after')
    def production_generation(self):
        if (self.production_id is None) != (self.edit_generation is None):
            raise ValueError('productionId and editGeneration must be paired')
        return self


class RunInput(WireValue):
    schema_version: Literal['1.0']
    identity: Identity
    content: Text
    history: list[HistoryMessage] = Field(max_length=100)
    references: list[dict[str, Any]] = Field(max_length=100)
    current_production: Production | None
    pending_questions: list[PendingQuestion] = Field(max_length=100)


class ParsedInput(WireValue):
    mode: Literal['QUERY', 'DISCUSS', 'DRAFT']
    relation: Literal['INDEPENDENT', 'CONTINUE_PRODUCTION', 'NEW_PRODUCTION', 'ANSWER_QUESTION', 'CLARIFY']
    question_id: Id | None
    conditions_patch: dict[str, Any]

    @model_validator(mode='after')
    def relationship(self):
        if (self.relation == 'ANSWER_QUESTION') != (self.question_id is not None):
            raise ValueError('questionId is required only for ANSWER_QUESTION')
        if self.mode != 'DRAFT' and (self.conditions_patch or self.relation in ('CONTINUE_PRODUCTION', 'NEW_PRODUCTION')):
            raise ValueError('only DRAFT may change production conditions')
        return self


class ScopeReceipt(WireValue):
    scope_id: Id
    call_id: Annotated[str, Field(min_length=1, max_length=200)]
    evidence_ids: list[Id] = Field(max_length=100)


class Failure(WireValue):
    code: Annotated[str, Field(min_length=1, max_length=64)]
    message: Text
    recoverable: bool


class RunResult(WireValue):
    schema_version: Literal['1.0']
    identity: Identity
    status: Literal['ANSWER', 'WAITING_INPUT', 'DRAFT_READY', 'INCOMPLETE', 'FAILED', 'CANCELLED']
    mode: Literal['QUERY', 'DISCUSS', 'DRAFT']
    content: Text
    scope_receipts: list[ScopeReceipt] = Field(max_length=100)
    production_id: Id | None
    edit_generation: Generation | None
    draft: DraftBinding | None
    question: PendingQuestion | None
    failure: Failure | None

    @model_validator(mode='after')
    def result_shape(self):
        if (self.production_id is None) != (self.edit_generation is None):
            raise ValueError('productionId and editGeneration must be paired')
        if (self.status == 'DRAFT_READY') != (self.draft is not None):
            raise ValueError('draft is required only for DRAFT_READY')
        if self.draft is not None and (self.mode != 'DRAFT' or self.production_id is None or self.draft.edit_generation != self.edit_generation):
            raise ValueError('draft must match production generation and DRAFT mode')
        if (self.status == 'WAITING_INPUT') != (self.question is not None):
            raise ValueError('question is required only for WAITING_INPUT')
        if (self.status in ('INCOMPLETE', 'FAILED', 'CANCELLED')) != (self.failure is not None):
            raise ValueError('failure is required only for unsuccessful results')
        if self.question is not None and (self.question.task_id != self.identity.task_id or self.question.thread_id != self.identity.thread_id or self.question.execution_id != self.identity.execution_id or self.question.production_id != self.production_id or self.question.edit_generation != self.edit_generation):
            raise ValueError('question must match result execution and production')
        return self
