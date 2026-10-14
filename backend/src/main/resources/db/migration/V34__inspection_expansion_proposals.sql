CREATE TABLE visual_inspection_proposals (
 id UUID PRIMARY KEY,
 inspection_id UUID NOT NULL UNIQUE REFERENCES visual_inspections(id),
 asset_id UUID NOT NULL REFERENCES assets(id),
 start_ms BIGINT NOT NULL,
 end_ms BIGINT NOT NULL CHECK(end_ms>start_ms),
 budget_json JSONB NOT NULL,
 cost_json JSONB NOT NULL,
 status TEXT NOT NULL DEFAULT 'PROPOSED' CHECK(status IN ('PROPOSED','DECLINED','APPROVED')),
 child_inspection_id UUID REFERENCES visual_inspections(id),
 decided_by UUID REFERENCES users(id),
 decided_at TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
