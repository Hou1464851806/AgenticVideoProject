ALTER TABLE conversation_messages ADD COLUMN source_task_id UUID UNIQUE REFERENCES tasks(id);
