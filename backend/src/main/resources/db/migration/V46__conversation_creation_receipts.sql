ALTER TABLE conversations ADD COLUMN creation_key VARCHAR(120);
ALTER TABLE conversations ADD COLUMN creation_request_json JSONB;
CREATE UNIQUE INDEX conversations_creation_key ON conversations(project_id,creation_key) WHERE creation_key IS NOT NULL;
