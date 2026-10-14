CREATE TABLE plan_confirmations (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    revision_id UUID NOT NULL,
    actor_user_id UUID NOT NULL REFERENCES users(id),
    source_json JSONB NOT NULL,
    confirmed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(revision_id,actor_user_id),
    UNIQUE(project_id,id,revision_id),
    FOREIGN KEY(project_id,revision_id) REFERENCES plan_revisions(project_id,id)
);
CREATE INDEX plan_confirmations_project_revision_idx ON plan_confirmations(project_id,revision_id);
CREATE INDEX plan_confirmations_actor_idx ON plan_confirmations(actor_user_id);
ALTER TABLE render_requests ADD COLUMN confirmation_id UUID;
ALTER TABLE render_requests ADD COLUMN actor_user_id UUID REFERENCES users(id);
ALTER TABLE render_requests ADD COLUMN intent_source_json JSONB;
ALTER TABLE render_requests ADD CONSTRAINT export_confirmation_version FOREIGN KEY(project_id,confirmation_id,revision_id) REFERENCES plan_confirmations(project_id,id,revision_id);
ALTER TABLE render_requests ADD CONSTRAINT export_requires_confirmation_and_intent CHECK(
    (kind='PREVIEW' AND confirmation_id IS NULL) OR
    (kind='EXPORT' AND confirmation_id IS NOT NULL AND actor_user_id IS NOT NULL AND intent_source_json IS NOT NULL));
CREATE INDEX render_requests_confirmation_idx ON render_requests(project_id,confirmation_id,revision_id);
CREATE INDEX render_requests_actor_idx ON render_requests(actor_user_id);
CREATE TABLE production_intents (
    project_id UUID NOT NULL REFERENCES projects(id),
    operation VARCHAR(16) NOT NULL CHECK(operation IN ('CONFIRM','EXPORT')),
    idempotency_key VARCHAR(200) NOT NULL CHECK(length(idempotency_key)>0),
    revision_id UUID NOT NULL,
    request_json JSONB NOT NULL,
    response_json JSONB NOT NULL,
    confirmation_id UUID NOT NULL,
    render_request_id UUID,
    PRIMARY KEY(project_id,operation,idempotency_key),
    FOREIGN KEY(project_id,revision_id) REFERENCES plan_revisions(project_id,id),
    FOREIGN KEY(project_id,confirmation_id,revision_id) REFERENCES plan_confirmations(project_id,id,revision_id),
    FOREIGN KEY(project_id,render_request_id) REFERENCES render_requests(project_id,id),
    CHECK((operation='CONFIRM' AND render_request_id IS NULL) OR (operation='EXPORT' AND render_request_id IS NOT NULL))
);
CREATE INDEX production_intents_revision_idx ON production_intents(project_id,revision_id);
CREATE INDEX production_intents_confirmation_idx ON production_intents(project_id,confirmation_id,revision_id);
CREATE INDEX production_intents_request_idx ON production_intents(project_id,render_request_id);
