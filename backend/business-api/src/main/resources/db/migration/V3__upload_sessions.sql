CREATE TABLE upload_sessions (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    actor_user_id UUID NOT NULL REFERENCES users(id),
    idempotency_key VARCHAR(120) NOT NULL,
    filename VARCHAR(255) NOT NULL,
    declared_bytes BIGINT NOT NULL CHECK (declared_bytes > 0),
    declared_sha256 CHAR(64) NOT NULL,
    part_size_bytes BIGINT NOT NULL,
    expected_parts INTEGER NOT NULL CHECK (expected_parts BETWEEN 1 AND 10000),
    object_key VARCHAR(255) NOT NULL UNIQUE,
    s3_upload_id TEXT,
    writer_token UUID NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'INITIALIZING',
    operation_token UUID,
    operation_expires_at TIMESTAMPTZ,
    last_activity_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT now() + interval '24 hours',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (actor_user_id, idempotency_key)
);
CREATE INDEX upload_sessions_project ON upload_sessions(project_id, id);
CREATE TABLE storage_reservations (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    upload_id UUID NOT NULL UNIQUE REFERENCES upload_sessions(id),
    category VARCHAR(32) NOT NULL DEFAULT 'ORIGINAL',
    bytes BIGINT NOT NULL CHECK (bytes > 0),
    asset_slots INTEGER NOT NULL DEFAULT 1,
    state VARCHAR(32) NOT NULL DEFAULT 'RESERVED'
);
