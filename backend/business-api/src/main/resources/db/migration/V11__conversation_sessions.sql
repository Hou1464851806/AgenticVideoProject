CREATE TABLE conversations (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    title VARCHAR(120) NOT NULL,
    asset_ids_json JSONB NOT NULL DEFAULT '[]',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX conversations_project ON conversations(project_id,id);
CREATE TABLE conversation_messages (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversations(id),
    message_seq BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL CHECK(role IN ('USER','ASSISTANT')),
    content TEXT NOT NULL,
    references_json JSONB NOT NULL DEFAULT '[]',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(conversation_id,message_seq)
);
