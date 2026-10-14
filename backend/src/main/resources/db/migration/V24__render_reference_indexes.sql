CREATE INDEX render_requests_task_idx ON render_requests(task_id);
CREATE INDEX render_requests_artifact_idx ON render_requests(artifact_id);
CREATE INDEX artifacts_revision_idx ON artifacts(revision_id);
