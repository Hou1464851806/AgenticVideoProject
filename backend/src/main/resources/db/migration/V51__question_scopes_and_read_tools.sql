CREATE TABLE question_scopes (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    assets_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Deliberately retain identities if an underlying dependency is removed, so reads fail explicitly.
CREATE TABLE question_scope_evidence (
    scope_id UUID NOT NULL REFERENCES question_scopes(id),
    evidence_id UUID NOT NULL,
    PRIMARY KEY(scope_id,evidence_id)
);
CREATE TABLE question_scope_units (
    scope_id UUID NOT NULL REFERENCES question_scopes(id),
    unit_id UUID NOT NULL,
    PRIMARY KEY(scope_id,unit_id)
);
CREATE TABLE question_tool_receipts (
    scope_id UUID NOT NULL REFERENCES question_scopes(id),
    call_id TEXT NOT NULL,
    request_json JSONB NOT NULL,
    result_json JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(scope_id,call_id)
);
CREATE TABLE question_searches (
    id UUID PRIMARY KEY,
    scope_id UUID NOT NULL REFERENCES question_scopes(id),
    conditions_hash CHAR(64) NOT NULL,
    conditions_json JSONB NOT NULL,
    result_json JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(scope_id,conditions_hash)
);
ALTER TABLE query_model_calls ADD COLUMN question_search_id UUID REFERENCES question_searches(id);
ALTER TABLE query_model_calls ADD COLUMN response_json JSONB;
CREATE INDEX query_model_calls_question_search ON query_model_calls(question_search_id);
