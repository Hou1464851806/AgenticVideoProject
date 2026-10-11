CREATE TABLE search_index_generations (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    asset_id UUID NOT NULL REFERENCES assets(id),
    analysis_id UUID NOT NULL REFERENCES asset_analysis_revisions(id),
    task_id UUID NOT NULL UNIQUE REFERENCES tasks(id),
    model TEXT NOT NULL,
    dimension INTEGER NOT NULL,
    profile_json JSONB NOT NULL,
    units_json JSONB NOT NULL,
    status TEXT NOT NULL DEFAULT 'BUILDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE TABLE search_index_heads (
    asset_id UUID PRIMARY KEY REFERENCES assets(id),
    generation_id UUID NOT NULL REFERENCES search_index_generations(id)
);
CREATE TABLE embedding_batches (
    generation_id UUID NOT NULL REFERENCES search_index_generations(id),
    ordinal INTEGER NOT NULL,
    units_json JSONB NOT NULL,
    status TEXT NOT NULL DEFAULT 'READY',
    owner_attempt_id UUID REFERENCES task_attempts(id),
    vectors_json JSONB,
    request_id TEXT,
    usage_json JSONB,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(generation_id,ordinal)
);
CREATE INDEX search_generations_project ON search_index_generations(project_id,asset_id);
