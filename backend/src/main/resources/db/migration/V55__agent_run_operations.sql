ALTER TABLE conversation_execution_requests ADD CONSTRAINT agent_run_scope_identity UNIQUE(task_id,scope_id);

-- Attribute evidence/validation receipts to the trusted execution, not a model-supplied scope.
CREATE TABLE agent_run_tool_calls (
    task_id UUID NOT NULL REFERENCES conversation_execution_requests(task_id),
    scope_id UUID NOT NULL,
    call_id TEXT NOT NULL,
    PRIMARY KEY(task_id,call_id),
    FOREIGN KEY(task_id,scope_id) REFERENCES conversation_execution_requests(task_id,scope_id),
    FOREIGN KEY(scope_id,call_id) REFERENCES question_tool_receipts(scope_id,call_id)
);
CREATE TABLE conversation_draft_acceptances (
    conversation_id UUID NOT NULL REFERENCES conversations(id),
    project_id UUID NOT NULL,
    request_key VARCHAR(120) NOT NULL,
    actor_user_id UUID NOT NULL REFERENCES users(id),
    request_json JSONB NOT NULL,
    response_json JSONB NOT NULL,
    source_draft_message_id UUID NOT NULL REFERENCES conversation_edit_drafts(message_id),
    revision_id UUID NOT NULL REFERENCES plan_revisions(id),
    confirmation_id UUID NOT NULL REFERENCES plan_confirmations(id),
    PRIMARY KEY(conversation_id,request_key),
    FOREIGN KEY(conversation_id,project_id) REFERENCES conversations(id,project_id),
    FOREIGN KEY(source_draft_message_id,conversation_id) REFERENCES conversation_messages(id,conversation_id),
    FOREIGN KEY(project_id,confirmation_id,revision_id) REFERENCES plan_confirmations(project_id,id,revision_id)
);
