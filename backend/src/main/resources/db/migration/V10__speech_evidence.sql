CREATE TABLE asset_analysis_revisions (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    asset_id UUID NOT NULL REFERENCES assets(id),
    source_sha256 CHAR(64) NOT NULL,
    profile_hash TEXT NOT NULL,
    audio_json JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(asset_id,source_sha256,profile_hash)
);
CREATE TABLE asset_analysis_heads (
    asset_id UUID PRIMARY KEY REFERENCES assets(id),
    analysis_id UUID NOT NULL REFERENCES asset_analysis_revisions(id)
);
INSERT INTO asset_analysis_revisions (id,project_id,asset_id,source_sha256,profile_hash)
SELECT gen_random_uuid(),project_id,id,sha256,'speech-v1' FROM assets WHERE validation_status='VALID';
INSERT INTO asset_analysis_heads SELECT asset_id,id FROM asset_analysis_revisions;
ALTER TABLE analysis_stages ADD COLUMN analysis_id UUID REFERENCES asset_analysis_revisions(id);
UPDATE analysis_stages s SET analysis_id=h.analysis_id FROM asset_analysis_heads h WHERE s.asset_id=h.asset_id;
ALTER TABLE analysis_stages ALTER COLUMN analysis_id SET NOT NULL;
ALTER TABLE analysis_stages DROP CONSTRAINT analysis_stages_pkey;
ALTER TABLE analysis_stages ADD PRIMARY KEY(analysis_id,stage_key);
CREATE TABLE model_calls (
    id UUID PRIMARY KEY,
    analysis_id UUID NOT NULL UNIQUE REFERENCES asset_analysis_revisions(id),
    task_id UUID NOT NULL REFERENCES tasks(id),
    owner_attempt_id UUID REFERENCES task_attempts(id),
    model TEXT NOT NULL,
    parameters_json JSONB NOT NULL,
    status VARCHAR(32) NOT NULL,
    provider_task_id UUID UNIQUE,
    request_id TEXT,
    result_json JSONB,
    usage_json JSONB,
    submitted_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE evidence_segments (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    asset_id UUID NOT NULL REFERENCES assets(id),
    analysis_id UUID NOT NULL REFERENCES asset_analysis_revisions(id),
    source_kind VARCHAR(32) NOT NULL,
    ordinal INTEGER NOT NULL,
    text TEXT NOT NULL,
    start_ms BIGINT NOT NULL,
    end_ms BIGINT NOT NULL,
    audio_start_ms BIGINT NOT NULL,
    audio_end_ms BIGINT NOT NULL,
    observation_json JSONB NOT NULL,
    model_call_id UUID NOT NULL REFERENCES model_calls(id),
    CHECK(start_ms>=0 AND end_ms>start_ms),
    UNIQUE(analysis_id,source_kind,ordinal)
);
CREATE TABLE retrieval_units (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    asset_id UUID NOT NULL REFERENCES assets(id),
    analysis_id UUID NOT NULL REFERENCES asset_analysis_revisions(id),
    source_kind VARCHAR(32) NOT NULL,
    text TEXT NOT NULL,
    start_ms BIGINT NOT NULL,
    end_ms BIGINT NOT NULL,
    rules_hash TEXT NOT NULL
);
CREATE TABLE unit_evidence_refs (
    unit_id UUID NOT NULL REFERENCES retrieval_units(id),
    evidence_id UUID NOT NULL REFERENCES evidence_segments(id),
    ordinal INTEGER NOT NULL,
    PRIMARY KEY(unit_id,ordinal),
    UNIQUE(unit_id,evidence_id)
);
CREATE TABLE asset_summaries (
    analysis_id UUID PRIMARY KEY REFERENCES asset_analysis_revisions(id),
    summary_json JSONB NOT NULL,
    evidence_refs_json JSONB NOT NULL
);
