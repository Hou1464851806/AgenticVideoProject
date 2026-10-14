CREATE TABLE query_model_calls (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    model TEXT NOT NULL,
    input_bytes BIGINT NOT NULL CHECK(input_bytes>=0),
    status TEXT NOT NULL CHECK(status IN ('SUBMITTING','SUCCEEDED','UNCERTAIN')),
    request_id TEXT,
    usage_json JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX query_model_calls_project ON query_model_calls(project_id,created_at);
