-- M2 production identity is independent of legacy AGENT_TURN tasks.
ALTER TABLE question_scopes ADD CONSTRAINT question_scopes_project_identity UNIQUE(id,project_id);
CREATE TABLE edit_productions (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    scope_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(id,project_id,scope_id),
    FOREIGN KEY(scope_id,project_id) REFERENCES question_scopes(id,project_id)
);
CREATE TABLE edit_draft_validations (
    id UUID PRIMARY KEY,
    production_id UUID NOT NULL,
    project_id UUID NOT NULL,
    scope_id UUID NOT NULL,
    call_id TEXT NOT NULL,
    draft_hash CHAR(64) NOT NULL CHECK (draft_hash ~ '^[0-9a-f]{64}$'),
    requirements_hash CHAR(64) NOT NULL CHECK (requirements_hash ~ '^[0-9a-f]{64}$'),
    rule_version VARCHAR(64) NOT NULL,
    draft_json JSONB NOT NULL,
    requirements_json JSONB NOT NULL,
    sources_json JSONB NOT NULL,
    result_json JSONB NOT NULL,
    normalized_content_json JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(id,production_id,project_id),
    UNIQUE(scope_id,call_id),
    FOREIGN KEY(production_id,project_id,scope_id) REFERENCES edit_productions(id,project_id,scope_id),
    FOREIGN KEY(scope_id,call_id) REFERENCES question_tool_receipts(scope_id,call_id)
);
ALTER TABLE plan_revisions ALTER COLUMN source_task_id DROP NOT NULL;
ALTER TABLE plan_revisions ADD COLUMN source_production_id UUID UNIQUE;
ALTER TABLE plan_revisions ADD COLUMN source_validation_id UUID;
-- Includes output_key -> outputId; committed with the version, never a separate acknowledgement.
ALTER TABLE plan_revisions ADD COLUMN production_save_result_json JSONB;
ALTER TABLE plan_revisions ADD CONSTRAINT plan_revision_production_validation
    FOREIGN KEY(source_validation_id,source_production_id,project_id)
    REFERENCES edit_draft_validations(id,production_id,project_id);
ALTER TABLE plan_revisions ADD CONSTRAINT plan_revision_source CHECK (
    (source_task_id IS NOT NULL AND source_production_id IS NULL AND source_validation_id IS NULL AND production_save_result_json IS NULL)
    OR
    (source_task_id IS NULL AND source_production_id IS NOT NULL AND source_validation_id IS NOT NULL AND production_save_result_json IS NOT NULL)
);
