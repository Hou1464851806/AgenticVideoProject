CREATE TABLE conversation_model_calls (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES tasks(id),
    attempt_id UUID NOT NULL REFERENCES task_attempts(id),
    call_number INTEGER NOT NULL CHECK(call_number BETWEEN 1 AND 6),
    request_json JSONB NOT NULL,
    status VARCHAR(32) NOT NULL,
    response_json JSONB,
    usage_json JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(task_id,call_number)
);
