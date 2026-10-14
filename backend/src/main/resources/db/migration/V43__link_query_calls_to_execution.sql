ALTER TABLE query_model_calls ADD COLUMN task_id UUID REFERENCES tasks(id);
CREATE INDEX query_model_calls_task ON query_model_calls(task_id) WHERE task_id IS NOT NULL;
