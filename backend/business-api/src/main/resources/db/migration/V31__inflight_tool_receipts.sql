ALTER TABLE conversation_tool_receipts ADD COLUMN status TEXT NOT NULL DEFAULT 'SUCCEEDED';
ALTER TABLE conversation_tool_receipts ALTER COLUMN result_json DROP NOT NULL;
