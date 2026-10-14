ALTER TABLE execution_reports ADD COLUMN result_type text;

UPDATE execution_reports r SET result_type = CASE
    WHEN t.type='MEDIA_PREPARE' THEN 'MEDIA_PREPARED'
    WHEN t.type='CONTENT_ANALYZE' THEN 'SPEECH_ANALYZED'
    WHEN t.type='AGENT_TURN' AND EXISTS (
        SELECT 1 FROM plan_revisions v WHERE v.source_task_id=t.id
    ) THEN 'EDIT_PROPOSAL'
    WHEN t.type='AGENT_TURN' THEN 'CONVERSATION_ANSWERED'
END
FROM tasks t WHERE r.task_id=t.id AND r.report_kind='COMPLETE';
