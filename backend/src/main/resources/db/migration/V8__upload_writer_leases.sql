ALTER TABLE upload_sessions ADD COLUMN writer_owner UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE upload_sessions ADD COLUMN writer_lease_expires_at TIMESTAMPTZ NOT NULL DEFAULT now() + interval '330 seconds';
