-- Existing protocol columns remain while historical controllers/tests are retired.
-- Local generations have no worker token, heartbeat, or execution lease.
ALTER TABLE task_attempts ADD COLUMN local_execution BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE task_attempts ALTER COLUMN lease_token_hash TYPE VARCHAR(64);
ALTER TABLE tasks ADD COLUMN next_execution_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE outbox_events ADD COLUMN publish_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE outbox_events ADD COLUMN last_error_code VARCHAR(64);
