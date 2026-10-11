CREATE TABLE conversation_tool_receipts (
    task_id UUID NOT NULL REFERENCES tasks(id),
    attempt_id UUID NOT NULL REFERENCES task_attempts(id),
    producer_key VARCHAR(120) NOT NULL,
    request_json JSONB NOT NULL,
    result_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(task_id,producer_key)
);
