-- Retry tasks keep the first core input/checkpoint while obtaining their own publication lease.
ALTER TABLE conversation_execution_requests ADD COLUMN core_task_id UUID,
    ADD CONSTRAINT agent_retry_conversation FOREIGN KEY(core_task_id,conversation_id)
        REFERENCES conversation_execution_requests(task_id,conversation_id),
    ADD CONSTRAINT agent_retry_core CHECK(core_task_id IS NULL OR core_task_id<>task_id);
-- Message publication belongs to the retry; a reused validation retains its original execution source.
DO $$ DECLARE fk text; BEGIN
    SELECT conname INTO STRICT fk FROM pg_constraint
    WHERE conrelid='conversation_edit_drafts'::regclass AND confrelid='conversation_messages'::regclass;
    EXECUTE format('ALTER TABLE conversation_edit_drafts DROP CONSTRAINT %I',fk);
END $$;
ALTER TABLE conversation_edit_drafts
    ADD CONSTRAINT draft_message_conversation FOREIGN KEY(message_id,conversation_id)
        REFERENCES conversation_messages(id,conversation_id),
    ADD CONSTRAINT draft_source_conversation FOREIGN KEY(source_task_id,conversation_id)
        REFERENCES conversation_execution_requests(task_id,conversation_id);
