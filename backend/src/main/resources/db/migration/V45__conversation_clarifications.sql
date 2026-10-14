CREATE TABLE conversation_questions (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversations(id),
    task_id UUID NOT NULL UNIQUE REFERENCES tasks(id),
    turn_id UUID NOT NULL REFERENCES conversation_turns(id),
    prompt TEXT NOT NULL,
    reason TEXT NOT NULL,
    fields_json JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','ANSWERED','CANCELLED')),
    answer_key VARCHAR(120),
    answer_json JSONB,
    answer_receipt JSONB,
    answer_message_id UUID REFERENCES conversation_messages(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE conversation_turns ADD COLUMN resumes_task_id UUID REFERENCES tasks(id);
