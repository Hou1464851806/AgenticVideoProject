CREATE TABLE object_cleanup_jobs (
    id UUID PRIMARY KEY,
    asset_id UUID NOT NULL UNIQUE REFERENCES assets(id),
    object_key TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    claim_id UUID,
    lease_expires_at TIMESTAMPTZ,
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);
