CREATE TABLE visual_inspections (
 id UUID PRIMARY KEY,
 project_id UUID NOT NULL REFERENCES projects(id),
 asset_id UUID NOT NULL REFERENCES assets(id),
 analysis_id UUID NOT NULL REFERENCES asset_analysis_revisions(id),
 request_key TEXT NOT NULL,
 query TEXT NOT NULL,
 source_kind TEXT NOT NULL CHECK(source_kind IN ('VISUAL','VISIBLE_TEXT')),
 start_ms BIGINT NOT NULL,
 end_ms BIGINT NOT NULL CHECK(end_ms>start_ms),
 sampling_kind TEXT NOT NULL,
 status TEXT NOT NULL DEFAULT 'PENDING',
 current_task_id UUID REFERENCES tasks(id),
 budget_json JSONB NOT NULL,
 frames_used INTEGER NOT NULL DEFAULT 0,
 calls_used INTEGER NOT NULL DEFAULT 0,
 steps_used INTEGER NOT NULL DEFAULT 0,
 input_bytes_used BIGINT NOT NULL DEFAULT 0,
 deadline_at TIMESTAMPTZ,
 error_json JSONB,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(project_id,request_key)
);
ALTER TABLE vision_frames ADD COLUMN inspection_id UUID REFERENCES visual_inspections(id);
CREATE INDEX vision_frames_inspection ON vision_frames(inspection_id);
