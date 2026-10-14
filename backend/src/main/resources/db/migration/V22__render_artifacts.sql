CREATE TABLE artifacts (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    request_id UUID NOT NULL REFERENCES render_requests(id),
    revision_id UUID NOT NULL REFERENCES plan_revisions(id),
    output_id UUID NOT NULL,
    task_id UUID NOT NULL REFERENCES tasks(id),
    attempt_id UUID NOT NULL UNIQUE REFERENCES task_attempts(id),
    kind VARCHAR(16) NOT NULL,
    object_key TEXT NOT NULL UNIQUE,
    state VARCHAR(24) NOT NULL DEFAULT 'STAGED',
    byte_size BIGINT,
    sha256 CHAR(64),
    duration_ms BIGINT,
    media_info_json JSONB,
    available_at TIMESTAMPTZ,
    last_accessed_at TIMESTAMPTZ,
    CHECK(state <> 'AVAILABLE' OR (byte_size IS NOT NULL AND byte_size > 0 AND sha256 IS NOT NULL AND duration_ms IS NOT NULL AND duration_ms > 0 AND media_info_json IS NOT NULL))
);
ALTER TABLE render_requests ADD COLUMN artifact_id UUID REFERENCES artifacts(id);
CREATE INDEX artifacts_request_idx ON artifacts(request_id);
CREATE INDEX artifacts_task_idx ON artifacts(task_id);
