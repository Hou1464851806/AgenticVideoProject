CREATE TABLE edit_plans (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    title VARCHAR(120) NOT NULL,
    next_revision_number BIGINT NOT NULL DEFAULT 1,
    editing_task_id UUID REFERENCES tasks(id) DEFERRABLE INITIALLY DEFERRED,
    editing_attempt_id UUID REFERENCES task_attempts(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE plan_revisions (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    plan_id UUID NOT NULL REFERENCES edit_plans(id),
    revision_number BIGINT NOT NULL,
    parent_revision_id UUID REFERENCES plan_revisions(id),
    source_task_id UUID NOT NULL UNIQUE REFERENCES tasks(id),
    content_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(plan_id,revision_number)
);
