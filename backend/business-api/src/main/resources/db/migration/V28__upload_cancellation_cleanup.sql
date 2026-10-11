ALTER TABLE upload_sessions ADD COLUMN last_signed_url_expires_at TIMESTAMPTZ;
ALTER TABLE storage_reservations DROP CONSTRAINT storage_reservations_bytes_check;
ALTER TABLE storage_reservations ADD CONSTRAINT storage_reservations_bytes_check CHECK(bytes>=0);
CREATE TABLE upload_cleanup_jobs (
    upload_id UUID PRIMARY KEY REFERENCES upload_sessions(id),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','CLEANED')),
    not_before TIMESTAMPTZ NOT NULL,
    lease_token UUID,
    lease_expires_at TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    cleaned_at TIMESTAMPTZ
);
CREATE INDEX upload_cleanup_pending ON upload_cleanup_jobs(next_attempt_at) WHERE status='PENDING';
