CREATE TABLE vision_frames (
    id UUID PRIMARY KEY,
    analysis_id UUID NOT NULL REFERENCES asset_analysis_revisions(id),
    ordinal INTEGER NOT NULL,
    observed_at_ms BIGINT NOT NULL CHECK(observed_at_ms>=0),
    source_pts TEXT NOT NULL,
    object_key TEXT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK(size_bytes BETWEEN 1 AND 1048576),
    UNIQUE(analysis_id,ordinal)
);
CREATE TABLE vision_model_calls (
    id UUID PRIMARY KEY,
    analysis_id UUID NOT NULL REFERENCES asset_analysis_revisions(id),
    task_id UUID NOT NULL REFERENCES tasks(id),
    owner_attempt_id UUID REFERENCES task_attempts(id),
    frame_sha256 CHAR(64) NOT NULL,
    model TEXT NOT NULL,
    parameters_json JSONB NOT NULL,
    status TEXT NOT NULL,
    request_id TEXT,
    result_json JSONB,
    usage_json JSONB,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(analysis_id,frame_sha256,model)
);
ALTER TABLE asset_analysis_revisions ADD COLUMN vision_profile_json JSONB;
ALTER TABLE evidence_segments ALTER COLUMN audio_start_ms DROP NOT NULL;
ALTER TABLE evidence_segments ALTER COLUMN audio_end_ms DROP NOT NULL;
ALTER TABLE evidence_segments ALTER COLUMN model_call_id DROP NOT NULL;
ALTER TABLE evidence_segments ADD COLUMN frame_id UUID REFERENCES vision_frames(id);
ALTER TABLE evidence_segments ADD COLUMN vision_call_id UUID REFERENCES vision_model_calls(id);
ALTER TABLE evidence_segments DROP CONSTRAINT evidence_segments_check;
ALTER TABLE evidence_segments ADD CONSTRAINT evidence_source_time CHECK (
  source_kind='SPEECH' AND start_ms>=0 AND end_ms>start_ms AND model_call_id IS NOT NULL AND frame_id IS NULL
  OR source_kind IN ('VISUAL','VISIBLE_TEXT') AND start_ms>=0 AND end_ms=start_ms AND frame_id IS NOT NULL AND vision_call_id IS NOT NULL AND model_call_id IS NULL
);
