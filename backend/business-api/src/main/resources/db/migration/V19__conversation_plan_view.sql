ALTER TABLE conversations ADD COLUMN view_plan_id UUID REFERENCES edit_plans(id);
ALTER TABLE conversations ADD COLUMN view_revision_id UUID REFERENCES plan_revisions(id);
ALTER TABLE conversations ADD COLUMN view_row_version BIGINT NOT NULL DEFAULT 0;
