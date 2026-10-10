CREATE TABLE upload_parts (
    upload_id UUID NOT NULL REFERENCES upload_sessions(id),
    part_number INTEGER NOT NULL,
    etag TEXT NOT NULL,
    size_bytes BIGINT NOT NULL,
    verified_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (upload_id, part_number)
);
CREATE TABLE assets (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    upload_id UUID NOT NULL UNIQUE REFERENCES upload_sessions(id),
    object_key TEXT NOT NULL UNIQUE,
    filename VARCHAR(255) NOT NULL,
    byte_size BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    validation_status VARCHAR(32) NOT NULL DEFAULT 'VALIDATING',
    row_version BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE tasks (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    input_json JSONB NOT NULL,
    current_attempt_id UUID,
    result_json JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES tasks(id),
    type VARCHAR(32) NOT NULL,
    payload_json JSONB NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    publisher_claim_id UUID,
    lease_expires_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ
);
ALTER TABLE upload_sessions ADD COLUMN asset_id UUID REFERENCES assets(id);
ALTER TABLE upload_sessions ADD COLUMN validation_task_id UUID REFERENCES tasks(id);
