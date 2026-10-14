CREATE TABLE render_requests (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    revision_id UUID NOT NULL REFERENCES plan_revisions(id),
    output_id UUID NOT NULL,
    kind VARCHAR(16) NOT NULL,
    config_json JSONB NOT NULL,
    renderer_version TEXT NOT NULL,
    cache_key CHAR(64) NOT NULL UNIQUE,
    task_id UUID NOT NULL REFERENCES tasks(id) DEFERRABLE INITIALLY DEFERRED,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX render_requests_revision_idx ON render_requests(revision_id);
