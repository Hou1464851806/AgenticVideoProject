CREATE TABLE project_usage_thresholds (
    project_id UUID PRIMARY KEY REFERENCES projects(id),
    thresholds_json JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
