CREATE TABLE project_event_counters (
    project_id UUID PRIMARY KEY REFERENCES projects(id),
    event_seq BIGINT NOT NULL DEFAULT 0,
    retained_after BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE public_events (
    project_id UUID NOT NULL REFERENCES projects(id),
    event_seq BIGINT NOT NULL,
    event_id UUID NOT NULL,
    type VARCHAR(64) NOT NULL,
    body JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(project_id,event_seq)
);

-- Serialize allocation at transaction end, after business locks have been acquired.
-- A sequence allocated before commit would allow a later transaction to hide a gap.
CREATE FUNCTION append_public_event(project UUID, kind TEXT, details JSONB) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE next_seq BIGINT; identity UUID := gen_random_uuid();
BEGIN
    INSERT INTO project_event_counters(project_id) VALUES(project) ON CONFLICT DO NOTHING;
    UPDATE project_event_counters SET event_seq=event_seq+1 WHERE project_id=project RETURNING event_seq INTO next_seq;
    INSERT INTO public_events(project_id,event_seq,event_id,type,body) VALUES(project,next_seq,identity,kind,
        jsonb_build_object('schemaVersion','1.0','projectId',project,'eventSeq',next_seq::text,'eventId',identity,'occurredAt',clock_timestamp()) || details);
END $$;

CREATE FUNCTION publish_conversation_message() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE project UUID; turn UUID; task UUID;
BEGIN
    SELECT project_id INTO project FROM conversations WHERE id=NEW.conversation_id;
    SELECT id,task_id INTO turn,task FROM conversation_turns WHERE message_id=NEW.id OR task_id=NEW.source_task_id LIMIT 1;
    PERFORM append_public_event(project,CASE WHEN NEW.role='USER' THEN 'turn.accepted' ELSE 'assistant.completed' END,
        jsonb_build_object('conversationId',NEW.conversation_id,'turnId',turn,'taskId',task,
            'payload',jsonb_build_object('messageId',NEW.id,'messageSeq',NEW.message_seq::text,'role',NEW.role,'content',NEW.content,'references',NEW.references_json)));
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER public_message AFTER INSERT ON conversation_messages DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION publish_conversation_message();

CREATE FUNCTION publish_task_state() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE conversation UUID; turn UUID; kind TEXT;
BEGIN
    IF TG_OP='UPDATE' AND NEW.status=OLD.status AND NEW.current_attempt_id IS NOT DISTINCT FROM OLD.current_attempt_id THEN RETURN NULL; END IF;
    SELECT conversation_id,id INTO conversation,turn FROM conversation_turns WHERE task_id=NEW.id;
    kind := CASE NEW.status WHEN 'RUNNING' THEN 'run.started' WHEN 'SUCCEEDED' THEN 'run.finished'
        WHEN 'FAILED' THEN 'run.failed' WHEN 'CANCELLED' THEN 'run.cancelled' ELSE 'activity.updated' END;
    PERFORM append_public_event(NEW.project_id,kind,jsonb_build_object('taskId',NEW.id,'conversationId',conversation,'turnId',turn,'attemptId',NEW.current_attempt_id,
        'payload',jsonb_build_object('status',NEW.status,'taskType',NEW.type)));
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER public_task AFTER INSERT OR UPDATE ON tasks DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION publish_task_state();

CREATE TABLE assistant_drafts (
    message_id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES tasks(id),
    attempt_id UUID NOT NULL REFERENCES task_attempts(id),
    content TEXT NOT NULL DEFAULT '',
    content_version INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'INCOMPLETE' CHECK(status IN ('INCOMPLETE','COMPLETE')),
    UNIQUE(task_id,attempt_id)
);
CREATE TABLE public_event_reports (
    task_id UUID NOT NULL REFERENCES tasks(id),
    attempt_id UUID NOT NULL REFERENCES task_attempts(id),
    producer_seq BIGINT NOT NULL CHECK(producer_seq>0),
    request_json JSONB NOT NULL,
    receipt_json JSONB NOT NULL,
    PRIMARY KEY(task_id,attempt_id,producer_seq)
);
