ALTER TABLE tasks ADD CONSTRAINT tasks_project_identity UNIQUE(project_id,id);
ALTER TABLE plan_revisions ADD CONSTRAINT revisions_project_identity UNIQUE(project_id,id);
ALTER TABLE task_attempts ADD CONSTRAINT attempts_task_identity UNIQUE(task_id,id);
ALTER TABLE render_requests ADD CONSTRAINT render_requests_project_identity UNIQUE(project_id,id);
ALTER TABLE render_requests ADD CONSTRAINT render_requests_output_identity UNIQUE(id,revision_id,output_id);
ALTER TABLE artifacts ADD CONSTRAINT artifacts_project_identity UNIQUE(project_id,id);
ALTER TABLE render_requests ADD CONSTRAINT render_request_revision_project FOREIGN KEY(project_id,revision_id) REFERENCES plan_revisions(project_id,id);
ALTER TABLE render_requests ADD CONSTRAINT render_request_task_project FOREIGN KEY(project_id,task_id) REFERENCES tasks(project_id,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE render_requests ADD CONSTRAINT render_request_artifact_project FOREIGN KEY(project_id,artifact_id) REFERENCES artifacts(project_id,id);
ALTER TABLE artifacts ADD CONSTRAINT artifact_request_project FOREIGN KEY(project_id,request_id) REFERENCES render_requests(project_id,id);
ALTER TABLE artifacts ADD CONSTRAINT artifact_revision_project FOREIGN KEY(project_id,revision_id) REFERENCES plan_revisions(project_id,id);
ALTER TABLE artifacts ADD CONSTRAINT artifact_task_project FOREIGN KEY(project_id,task_id) REFERENCES tasks(project_id,id);
ALTER TABLE artifacts ADD CONSTRAINT artifact_task_attempt FOREIGN KEY(task_id,attempt_id) REFERENCES task_attempts(task_id,id);
ALTER TABLE artifacts ADD CONSTRAINT artifact_request_output FOREIGN KEY(request_id,revision_id,output_id) REFERENCES render_requests(id,revision_id,output_id);
ALTER TABLE render_requests ADD CONSTRAINT render_request_kind CHECK(kind IN ('PREVIEW','EXPORT'));
ALTER TABLE artifacts ADD CONSTRAINT artifact_kind CHECK(kind IN ('PREVIEW','EXPORT'));
ALTER TABLE artifacts ADD CONSTRAINT artifact_state CHECK(state IN ('STAGED','AVAILABLE','UNAVAILABLE','REMOVED'));
CREATE INDEX render_requests_project_revision_idx ON render_requests(project_id,revision_id);
CREATE INDEX render_requests_project_task_idx ON render_requests(project_id,task_id);
CREATE INDEX render_requests_project_artifact_idx ON render_requests(project_id,artifact_id);
CREATE INDEX artifacts_project_request_idx ON artifacts(project_id,request_id);
CREATE INDEX artifacts_project_revision_idx ON artifacts(project_id,revision_id);
CREATE INDEX artifacts_project_task_idx ON artifacts(project_id,task_id);
CREATE INDEX artifacts_task_attempt_idx ON artifacts(task_id,attempt_id);
CREATE INDEX artifacts_request_output_idx ON artifacts(request_id,revision_id,output_id);
-- Requests and their task association remain immutable; cache reuse is separate from intent replay.
ALTER TABLE render_requests DROP CONSTRAINT render_requests_cache_key_key;
CREATE INDEX render_requests_cache_idx ON render_requests(cache_key,created_at DESC);
CREATE TABLE preview_intents (
    project_id UUID NOT NULL REFERENCES projects(id),
    idempotency_key VARCHAR(200) NOT NULL,
    revision_id UUID NOT NULL,
    request_json JSONB NOT NULL,
    render_request_id UUID NOT NULL,
    response_json JSONB NOT NULL,
    PRIMARY KEY(project_id,idempotency_key),
    FOREIGN KEY(project_id,revision_id) REFERENCES plan_revisions(project_id,id),
    FOREIGN KEY(project_id,render_request_id) REFERENCES render_requests(project_id,id),
    CHECK(length(idempotency_key)>0)
);
CREATE INDEX preview_intents_revision_idx ON preview_intents(project_id,revision_id);
CREATE INDEX preview_intents_request_idx ON preview_intents(project_id,render_request_id);
