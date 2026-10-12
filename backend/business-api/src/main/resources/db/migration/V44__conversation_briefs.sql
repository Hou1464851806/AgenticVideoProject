CREATE TABLE conversation_brief_revisions (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversations(id),
    source_task_id UUID NOT NULL REFERENCES tasks(id),
    source_message_id UUID NOT NULL REFERENCES conversation_messages(id),
    revision_number BIGINT NOT NULL,
    fields_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(conversation_id,revision_number)
);
