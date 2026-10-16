-- A preview consumes either a frozen version output or a validated chat draft output.
ALTER TABLE conversation_edit_drafts ADD CONSTRAINT draft_preview_identity
    UNIQUE(message_id,conversation_id,project_id,production_id,validation_id,draft_hash,requirements_hash);
ALTER TABLE render_requests
    ALTER COLUMN revision_id DROP NOT NULL,
    ALTER COLUMN output_id DROP NOT NULL,
    ADD COLUMN conversation_id UUID,
    ADD COLUMN source_draft_message_id UUID,
    ADD COLUMN production_id UUID,
    ADD COLUMN validation_id UUID,
    ADD COLUMN draft_hash CHAR(64),
    ADD COLUMN requirements_hash CHAR(64),
    ADD COLUMN output_key TEXT,
    ADD CONSTRAINT render_draft_binding FOREIGN KEY(source_draft_message_id,conversation_id,project_id,production_id,validation_id,draft_hash,requirements_hash)
        REFERENCES conversation_edit_drafts(message_id,conversation_id,project_id,production_id,validation_id,draft_hash,requirements_hash),
    ADD CONSTRAINT render_source CHECK (
        (revision_id IS NOT NULL AND output_id IS NOT NULL AND conversation_id IS NULL AND source_draft_message_id IS NULL AND production_id IS NULL AND validation_id IS NULL AND draft_hash IS NULL AND requirements_hash IS NULL AND output_key IS NULL)
        OR (kind='PREVIEW' AND revision_id IS NULL AND output_id IS NULL AND conversation_id IS NOT NULL AND source_draft_message_id IS NOT NULL AND production_id IS NOT NULL AND validation_id IS NOT NULL AND draft_hash IS NOT NULL AND requirements_hash IS NOT NULL AND output_key IS NOT NULL AND length(output_key)>0)),
    ADD COLUMN source_id UUID GENERATED ALWAYS AS (coalesce(revision_id,source_draft_message_id)) STORED,
    ADD COLUMN source_output TEXT GENERATED ALWAYS AS (coalesce(output_id::text,output_key)) STORED,
    ADD CONSTRAINT render_artifact_source_identity UNIQUE(id,project_id,source_id,source_output,task_id,kind),
    ADD CONSTRAINT render_intent_source_identity UNIQUE(project_id,id,source_id),
    ADD CONSTRAINT render_retry_source_identity UNIQUE(project_id,id,source_id,source_output);
CREATE INDEX render_requests_conversation ON render_requests(conversation_id,created_at);
ALTER TABLE artifacts
    ALTER COLUMN revision_id DROP NOT NULL,
    ALTER COLUMN output_id DROP NOT NULL,
    ADD COLUMN source_draft_message_id UUID,
    ADD COLUMN output_key TEXT,
    ADD CONSTRAINT artifact_source CHECK (
        (revision_id IS NOT NULL AND output_id IS NOT NULL AND source_draft_message_id IS NULL AND output_key IS NULL)
        OR (kind='PREVIEW' AND revision_id IS NULL AND output_id IS NULL AND source_draft_message_id IS NOT NULL AND output_key IS NOT NULL)),
    ADD COLUMN source_id UUID GENERATED ALWAYS AS (coalesce(revision_id,source_draft_message_id)) STORED,
    ADD COLUMN source_output TEXT GENERATED ALWAYS AS (coalesce(output_id::text,output_key)) STORED,
    ADD CONSTRAINT artifact_request_source FOREIGN KEY(request_id,project_id,source_id,source_output,task_id,kind)
        REFERENCES render_requests(id,project_id,source_id,source_output,task_id,kind);
ALTER TABLE artifacts ADD CONSTRAINT artifact_publication_identity UNIQUE(project_id,id,request_id,task_id);
ALTER TABLE render_requests ADD CONSTRAINT render_publication_source FOREIGN KEY(project_id,artifact_id,id,task_id)
    REFERENCES artifacts(project_id,id,request_id,task_id);
ALTER TABLE preview_intents
    ALTER COLUMN revision_id DROP NOT NULL,
    ADD COLUMN source_draft_message_id UUID REFERENCES conversation_edit_drafts(message_id),
    ADD COLUMN source_id UUID GENERATED ALWAYS AS (coalesce(revision_id,source_draft_message_id)) STORED,
    ADD CONSTRAINT preview_intent_source CHECK ((revision_id IS NOT NULL) <> (source_draft_message_id IS NOT NULL)),
    ADD CONSTRAINT preview_intent_request_source FOREIGN KEY(project_id,render_request_id,source_id)
        REFERENCES render_requests(project_id,id,source_id);
-- Explicit retries have durable successor identity independent of opaque client keys.
ALTER TABLE render_requests ADD COLUMN retry_of UUID UNIQUE,
    ADD CONSTRAINT render_retry_source FOREIGN KEY(project_id,retry_of,source_id,source_output)
        REFERENCES render_requests(project_id,id,source_id,source_output);
CREATE OR REPLACE FUNCTION publish_conversation_message() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE project UUID; turn UUID; task UUID;
BEGIN
    SELECT project_id INTO project FROM conversations WHERE id=NEW.conversation_id;
    SELECT id,task_id INTO turn,task FROM conversation_turns WHERE message_id=NEW.id OR task_id=NEW.source_task_id LIMIT 1;
    PERFORM append_public_event(project,CASE WHEN NEW.role='USER' THEN 'turn.accepted' ELSE 'assistant.completed' END,
        jsonb_build_object('conversationId',NEW.conversation_id,'turnId',turn,'taskId',coalesce(task,NEW.source_task_id),
            'payload',jsonb_build_object('messageId',NEW.id,'messageSeq',NEW.message_seq::text,'role',NEW.role,'content',NEW.content,'references',NEW.references_json)));
    RETURN NULL;
END $$;
