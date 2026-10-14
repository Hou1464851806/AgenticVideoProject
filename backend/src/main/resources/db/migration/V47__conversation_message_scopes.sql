CREATE TABLE conversation_message_scopes (
    message_id UUID PRIMARY KEY REFERENCES conversation_messages(id),
    asset_ids_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
