-- Browser lifecycle associations. Legacy CLI productions and retired Agent rows remain valid.
-- Execution state stays in tasks; current/frozen/history are derived from pointers and revisions.
ALTER TABLE conversations ADD CONSTRAINT conversations_project_identity UNIQUE(id,project_id);
ALTER TABLE conversation_messages ADD CONSTRAINT conversation_messages_identity UNIQUE(id,conversation_id);
ALTER TABLE conversation_messages ADD CONSTRAINT conversation_messages_source_identity UNIQUE(id,conversation_id,source_task_id);
ALTER TABLE task_attempts ADD CONSTRAINT task_attempts_task_identity UNIQUE(id,task_id);
ALTER TABLE conversation_turns ADD CONSTRAINT conversation_turns_input_identity UNIQUE(id,conversation_id,message_id,task_id);

ALTER TABLE edit_productions
    ADD COLUMN conversation_id UUID,
    ADD COLUMN thread_id UUID UNIQUE,
    ADD COLUMN edit_generation BIGINT NOT NULL DEFAULT 0 CHECK(edit_generation>=0),
    ADD COLUMN requirements_json JSONB,
    ADD COLUMN requirements_sources_json JSONB,
    ADD COLUMN current_draft_message_id UUID,
    ADD CONSTRAINT edit_productions_conversation FOREIGN KEY(conversation_id,project_id) REFERENCES conversations(id,project_id),
    ADD CONSTRAINT edit_productions_browser_context CHECK (
        (conversation_id IS NULL AND thread_id IS NULL AND requirements_json IS NULL AND requirements_sources_json IS NULL AND current_draft_message_id IS NULL)
        OR (conversation_id IS NOT NULL AND thread_id IS NOT NULL AND requirements_json IS NOT NULL AND requirements_sources_json IS NOT NULL)),
    ADD CONSTRAINT edit_productions_thread_identity UNIQUE(id,thread_id),
    ADD CONSTRAINT edit_productions_conversation_identity UNIQUE(id,conversation_id,project_id);
ALTER TABLE conversations ADD COLUMN current_production_id UUID;
ALTER TABLE conversations ADD CONSTRAINT conversations_current_production
    FOREIGN KEY(current_production_id,id,project_id) REFERENCES edit_productions(id,conversation_id,project_id);

-- One accepted task has one fixed input; attempts and related answer/retry tasks can reuse an execution.
ALTER TABLE conversation_execution_requests ALTER COLUMN requirement_id DROP NOT NULL;
ALTER TABLE conversation_execution_requests
    ADD COLUMN schema_version VARCHAR(16),
    ADD COLUMN project_id UUID,
    ADD COLUMN conversation_id UUID,
    ADD COLUMN turn_id UUID,
    ADD COLUMN message_id UUID,
    ADD COLUMN input_hash CHAR(64),
    ADD COLUMN thread_id UUID,
    ADD COLUMN execution_id UUID,
    ADD COLUMN production_id UUID,
    ADD COLUMN scope_id UUID,
    ADD COLUMN edit_generation BIGINT CHECK(edit_generation>=0),
    ADD COLUMN parsed_json JSONB,
    ADD COLUMN result_json JSONB,
    ADD CONSTRAINT agent_run_task_project FOREIGN KEY(project_id,task_id) REFERENCES tasks(project_id,id),
    ADD CONSTRAINT agent_run_conversation_project FOREIGN KEY(conversation_id,project_id) REFERENCES conversations(id,project_id),
    ADD CONSTRAINT agent_run_turn_input FOREIGN KEY(turn_id,conversation_id,message_id,task_id)
        REFERENCES conversation_turns(id,conversation_id,message_id,task_id),
    ADD CONSTRAINT agent_run_message FOREIGN KEY(message_id,conversation_id) REFERENCES conversation_messages(id,conversation_id),
    ADD CONSTRAINT agent_run_scope FOREIGN KEY(scope_id,project_id) REFERENCES question_scopes(id,project_id),
    ADD CONSTRAINT agent_run_production_thread FOREIGN KEY(production_id,thread_id) REFERENCES edit_productions(id,thread_id),
    ADD CONSTRAINT agent_run_production_scope FOREIGN KEY(production_id,project_id,scope_id) REFERENCES edit_productions(id,project_id,scope_id),
    ADD CONSTRAINT agent_run_production FOREIGN KEY(production_id,conversation_id,project_id)
        REFERENCES edit_productions(id,conversation_id,project_id),
    ADD CONSTRAINT agent_run_context CHECK (
        (schema_version IS NULL AND requirement_id IS NOT NULL)
        OR (schema_version IS NOT NULL AND schema_version='1.0' AND requirement_id IS NULL AND project_id IS NOT NULL AND conversation_id IS NOT NULL
            AND turn_id IS NOT NULL AND message_id IS NOT NULL AND input_hash IS NOT NULL AND input_hash ~ '^[0-9a-f]{64}$'
            AND thread_id IS NOT NULL AND execution_id IS NOT NULL
            AND ((production_id IS NULL AND edit_generation IS NULL) OR (production_id IS NOT NULL AND edit_generation IS NOT NULL AND scope_id IS NOT NULL)))),
    ADD CONSTRAINT agent_run_conversation_identity UNIQUE(task_id,conversation_id),
    ADD CONSTRAINT agent_run_execution_identity UNIQUE(task_id,conversation_id,thread_id,execution_id),
    ADD CONSTRAINT agent_run_production_identity UNIQUE(task_id,production_id,edit_generation);
CREATE INDEX agent_run_execution ON conversation_execution_requests(execution_id) WHERE execution_id IS NOT NULL;

-- Historical validations retain their generation; a later edit never mutates their content.
ALTER TABLE edit_draft_validations
    ADD COLUMN source_task_id UUID,
    ADD COLUMN source_attempt_id UUID,
    ADD COLUMN edit_generation BIGINT CHECK(edit_generation>=0),
    ADD CONSTRAINT draft_validation_attempt FOREIGN KEY(source_attempt_id,source_task_id) REFERENCES task_attempts(id,task_id),
    ADD CONSTRAINT draft_validation_run FOREIGN KEY(source_task_id,production_id,edit_generation)
        REFERENCES conversation_execution_requests(task_id,production_id,edit_generation),
    ADD CONSTRAINT draft_validation_execution CHECK (
        (source_task_id IS NULL AND source_attempt_id IS NULL AND edit_generation IS NULL)
        OR (source_task_id IS NOT NULL AND source_attempt_id IS NOT NULL AND edit_generation IS NOT NULL)),
    ADD CONSTRAINT draft_validation_binding UNIQUE(id,production_id,project_id,draft_hash,requirements_hash,edit_generation,source_task_id);

-- Structured edit draft is a projection of a durable validation, never another mutable plan.
CREATE TABLE conversation_edit_drafts (
    message_id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL,
    project_id UUID NOT NULL,
    production_id UUID NOT NULL,
    validation_id UUID NOT NULL,
    source_task_id UUID NOT NULL,
    draft_hash CHAR(64) NOT NULL,
    requirements_hash CHAR(64) NOT NULL,
    edit_generation BIGINT NOT NULL CHECK(edit_generation>=0),
    FOREIGN KEY(message_id,conversation_id,source_task_id) REFERENCES conversation_messages(id,conversation_id,source_task_id),
    FOREIGN KEY(production_id,conversation_id,project_id) REFERENCES edit_productions(id,conversation_id,project_id),
    FOREIGN KEY(validation_id,production_id,project_id,draft_hash,requirements_hash,edit_generation,source_task_id)
        REFERENCES edit_draft_validations(id,production_id,project_id,draft_hash,requirements_hash,edit_generation,source_task_id),
    UNIQUE(message_id,production_id)
);
ALTER TABLE edit_productions ADD CONSTRAINT production_current_draft
    FOREIGN KEY(current_draft_message_id,id) REFERENCES conversation_edit_drafts(message_id,production_id);

ALTER TABLE conversation_questions
    ADD COLUMN production_id UUID,
    ADD COLUMN thread_id UUID,
    ADD COLUMN execution_id UUID,
    ADD COLUMN wait_id VARCHAR(200),
    ADD COLUMN edit_generation BIGINT CHECK(edit_generation>=0),
    ADD COLUMN successor_task_id UUID,
    ADD CONSTRAINT question_successor_conversation FOREIGN KEY(successor_task_id,conversation_id)
        REFERENCES conversation_execution_requests(task_id,conversation_id),
    ADD CONSTRAINT question_agent_execution FOREIGN KEY(task_id,conversation_id,thread_id,execution_id)
        REFERENCES conversation_execution_requests(task_id,conversation_id,thread_id,execution_id),
    ADD CONSTRAINT question_production_generation FOREIGN KEY(task_id,production_id,edit_generation)
        REFERENCES conversation_execution_requests(task_id,production_id,edit_generation),
    ADD CONSTRAINT question_wait_context CHECK (
        (thread_id IS NULL AND execution_id IS NULL AND wait_id IS NULL AND production_id IS NULL AND edit_generation IS NULL AND successor_task_id IS NULL)
        OR (thread_id IS NOT NULL AND execution_id IS NOT NULL AND wait_id IS NOT NULL AND length(wait_id)>0
            AND ((production_id IS NULL AND edit_generation IS NULL) OR (production_id IS NOT NULL AND edit_generation IS NOT NULL))));
CREATE UNIQUE INDEX question_execution_wait ON conversation_questions(execution_id,wait_id) WHERE execution_id IS NOT NULL;
