ALTER TABLE conversation_turns ADD COLUMN retry_of_task_id UUID UNIQUE REFERENCES tasks(id);
