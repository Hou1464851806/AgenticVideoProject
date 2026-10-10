CREATE TABLE task_attempts (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES tasks(id),
    attempt_number INTEGER NOT NULL,
    executor_id VARCHAR(120) NOT NULL,
    claim_request_id UUID NOT NULL,
    lease_token_hash CHAR(64) NOT NULL,
    lease_expires_at TIMESTAMPTZ NOT NULL,
    heartbeat_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    status VARCHAR(32) NOT NULL DEFAULT 'RUNNING',
    UNIQUE(task_id, attempt_number),
    UNIQUE(executor_id, claim_request_id)
);
ALTER TABLE tasks ADD CONSTRAINT tasks_current_attempt_fk FOREIGN KEY(current_attempt_id) REFERENCES task_attempts(id);
