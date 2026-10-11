ALTER TABLE artifacts DROP CONSTRAINT artifact_state;
ALTER TABLE artifacts ADD CONSTRAINT artifact_state CHECK(state IN ('STAGED','AVAILABLE','UNAVAILABLE','DELETE_PENDING','REMOVED'));
CREATE TABLE artifact_deletion_requests (
    artifact_id UUID PRIMARY KEY REFERENCES artifacts(id),
    actor_user_id UUID NOT NULL REFERENCES users(id),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    removed_at TIMESTAMPTZ
);
CREATE INDEX artifact_deletion_actor_idx ON artifact_deletion_requests(actor_user_id);
