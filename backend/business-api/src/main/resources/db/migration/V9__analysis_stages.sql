CREATE TABLE analysis_stages (
    asset_id UUID NOT NULL REFERENCES assets(id),
    stage_key VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    task_id UUID REFERENCES tasks(id),
    error_json JSONB,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (asset_id, stage_key)
);
