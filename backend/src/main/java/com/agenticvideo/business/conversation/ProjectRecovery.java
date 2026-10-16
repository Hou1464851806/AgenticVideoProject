package com.agenticvideo.business.conversation;

import com.agenticvideo.business.project.ProjectAccess;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** A single database snapshot supplies both business state and its replay boundary. */
@Service
public class ProjectRecovery {
    @org.springframework.beans.factory.annotation.Autowired
    private com.agenticvideo.business.agentrun.AgentRuns agentRuns;
    @org.springframework.beans.factory.annotation.Autowired
    private com.agenticvideo.business.render.DraftPreviews draftPreviews;
    private final ConversationQuestions questions;
    private final ConversationBriefs briefs;
    private final JdbcTemplate database;
    private final ProjectAccess access;
    private final AssistantDrafts drafts;
    private final ConversationRetries retries;
    private final ObjectMapper json;
    private final TransactionTemplate snapshots;

    public ProjectRecovery(ConversationQuestions questions,ConversationBriefs briefs,JdbcTemplate database, ProjectAccess access, ObjectMapper json,
            PlatformTransactionManager manager, AssistantDrafts drafts,ConversationRetries retries) {
        this.questions=questions;
        this.briefs=briefs;
        this.retries=retries;
        this.drafts=drafts;
        this.database = database; this.access = access; this.json = json;
        snapshots = new TransactionTemplate(manager);
        snapshots.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshots.setReadOnly(true);
    }

    public Object conversation(UUID id, Authentication account) {
        return snapshots.execute(tx -> {
            var project = database.queryForObject("SELECT project_id FROM conversations WHERE id=?", UUID.class, id);
            var snapshot = (Map<String,Object>)snapshot(project, account, id);
            var conversations = (java.util.List<Map<String,Object>>)snapshot.get("conversations");
            if(conversations.isEmpty())throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"制作会话不存在或无权访问");
            var result = new LinkedHashMap<String,Object>(conversations.get(0)); result.put("cursor", snapshot.get("cursor")); return result;
        });
    }

    public Object snapshot(UUID project, Authentication account) { return snapshot(project,account,null); }
    private Object snapshot(UUID project, Authentication account, UUID session) {
        return snapshots.execute(tx -> {
            access.requireOwned(project, account);
            Long sequence = database.queryForObject("SELECT coalesce((SELECT event_seq FROM project_event_counters WHERE project_id=?),0)", Long.class, project);
            var data = new LinkedHashMap<String,Object>();
            data.put("cursor", project + ":" + sequence);
            data.put("conversations", database.query("SELECT id,title,asset_ids_json FROM conversations WHERE project_id=? AND (?::uuid IS NULL OR id=?) ORDER BY created_at DESC,id DESC LIMIT 100",
                    (row, index) -> {
                        UUID id = row.getObject("id", UUID.class);
                        var conversation = new LinkedHashMap<String,Object>();
                        conversation.put("id", id); conversation.put("title", row.getString("title"));
                        conversation.put("assetIds", parse(row.getString("asset_ids_json")));
                        conversation.put("brief",briefs.snapshot(id));
                        conversation.put("agent",agentRuns.snapshot(id));
                        conversation.put("previews",draftPreviews.list(id));
                        conversation.put("questions",questions.list(id));
                        conversation.put("items", database.query("SELECT * FROM (SELECT id,message_seq,role,content,references_json FROM conversation_messages WHERE conversation_id=? ORDER BY message_seq DESC LIMIT 100) m ORDER BY message_seq",
                                (message, n) -> Map.of("id", message.getObject("id", UUID.class), "messageSeq", message.getLong("message_seq"), "role", message.getString("role"), "content", message.getString("content"), "references", parse(message.getString("references_json"))), id));
                        var tasks = database.queryForList("SELECT t.id AS \"taskId\",t.status,t.public_phase AS phase,t.current_attempt_id AS \"attemptId\" FROM conversation_turns r JOIN tasks t ON t.id=r.task_id WHERE r.conversation_id=? ORDER BY r.created_at DESC LIMIT 1", id);
                        for(var task:tasks) {
                            task.put("externalEffects",retries.effects((UUID)task.get("taskId")));
                            var agentFailure=database.queryForList("SELECT result_json->'failure' AS failure FROM conversation_execution_requests WHERE task_id=? AND schema_version='1.0' AND result_json->'failure' IS NOT NULL AND result_json->'failure'<>'null'::jsonb",task.get("taskId"));
                            if(!agentFailure.isEmpty())task.put("failure",parse(agentFailure.get(0).get("failure").toString()));
                            if("FAILED".equals(task.get("status"))) {
                                var failure=database.queryForList("SELECT result_json FROM tasks WHERE id=? AND result_json->>'code'='MODEL_RECORD_TOO_LARGE'",task.get("taskId"));
                                if(!failure.isEmpty()) {var error=(JsonNode)parse(failure.get(0).get("result_json").toString());task.put("failure",Map.of("code","MODEL_RECORD_TOO_LARGE","message",error.path("message").asText()));}
                            }
                        }
                        conversation.put("latestTask", tasks.isEmpty() ? null : tasks.get(0));
                        conversation.put("view", database.queryForMap("SELECT view_plan_id AS \"planId\",view_revision_id AS \"revisionId\",view_row_version AS \"rowVersion\" FROM conversations WHERE id=?",id));
                        var draftPage=drafts.page(id,null);
                        conversation.put("drafts",draftPage.get("items"));
                        conversation.put("draftsTruncated",draftPage.get("nextCursor")!=null);
                        conversation.put("draftsNextCursor",draftPage.get("nextCursor"));
                        return conversation;
                    }, project,session,session));
            return data;
        });
    }

    private Object parse(String value) {
        try { return json.readTree(value); }
        catch (java.io.IOException invalid) { throw new IllegalStateException(invalid); }
    }
}
