ALTER TABLE model_calls DROP CONSTRAINT model_calls_analysis_id_key;
ALTER TABLE model_calls ADD COLUMN retry_number INTEGER NOT NULL DEFAULT 0;
ALTER TABLE model_calls ADD CONSTRAINT model_calls_analysis_retry_key UNIQUE(analysis_id,retry_number);
