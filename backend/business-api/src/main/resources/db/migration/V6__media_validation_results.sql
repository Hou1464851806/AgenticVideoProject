ALTER TABLE assets ADD COLUMN duration_ms BIGINT;
ALTER TABLE assets ADD COLUMN media_info_json JSONB;
ALTER TABLE assets ADD COLUMN time_mapping_json JSONB;
ALTER TABLE assets ADD COLUMN error_json JSONB;
CREATE TABLE execution_reports (
    task_id UUID NOT NULL REFERENCES tasks(id),
    attempt_id UUID NOT NULL REFERENCES task_attempts(id),
    report_kind VARCHAR(32) NOT NULL,
    producer_key VARCHAR(120) NOT NULL,
    payload_json JSONB NOT NULL,
    outcome_json JSONB NOT NULL,
    PRIMARY KEY(attempt_id, report_kind, producer_key)
);
