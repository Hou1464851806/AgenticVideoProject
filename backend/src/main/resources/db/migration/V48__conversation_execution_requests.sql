CREATE TABLE conversation_execution_requests (
    task_id UUID PRIMARY KEY REFERENCES tasks(id),
    requirement_id UUID NOT NULL,
    request_json JSONB NOT NULL,
    input_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
