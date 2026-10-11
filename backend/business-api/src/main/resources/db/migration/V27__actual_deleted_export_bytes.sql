ALTER TABLE artifact_deletion_requests ADD COLUMN observed_bytes BIGINT CHECK(observed_bytes>=0);
