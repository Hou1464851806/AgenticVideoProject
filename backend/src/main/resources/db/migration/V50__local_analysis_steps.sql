-- Failure limits count failed steps, not successful asynchronous ASR queries.
ALTER TABLE tasks ADD COLUMN local_failure_count INTEGER NOT NULL DEFAULT 0;
-- Preserve provider responses before interpreting their content; parse failures never erase charges.
ALTER TABLE model_calls ADD COLUMN response_json JSONB;
ALTER TABLE vision_model_calls ADD COLUMN response_json JSONB;
ALTER TABLE embedding_batches ADD COLUMN response_json JSONB;
