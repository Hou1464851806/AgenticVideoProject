CREATE TABLE edit_validation_receipts (
    task_id UUID NOT NULL REFERENCES tasks(id),
    producer_key VARCHAR(120) NOT NULL,
    proposal_json JSONB NOT NULL,
    result_json JSONB NOT NULL,
    PRIMARY KEY(task_id,producer_key)
);
