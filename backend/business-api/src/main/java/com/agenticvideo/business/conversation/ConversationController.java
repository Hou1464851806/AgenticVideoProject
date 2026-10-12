package com.agenticvideo.business.conversation;

import com.agenticvideo.business.project.ProjectAccess;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class ConversationController {
    private final ConversationQuestions questions;
    private final JdbcTemplate database;
    private final ProjectAccess access;
    private final ObjectMapper json;
    private final ConversationTurns turns;
    private final ProjectRecovery recovery;
    private final AssistantDrafts drafts;
    private final ConversationRetries retries;

    public ConversationController(ConversationQuestions questions,JdbcTemplate database, ProjectAccess access, ObjectMapper json, ConversationTurns turns, ProjectRecovery recovery, AssistantDrafts drafts,ConversationRetries retries) {
        this.questions=questions;
        this.retries=retries;
        this.drafts=drafts;
        this.recovery=recovery;
        this.database = database;
        this.access = access;
        this.json = json;
        this.turns = turns;
    }

    @PostMapping("/conversations/{conversationId}/questions/{questionId}/answers")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Object answer(@PathVariable UUID conversationId,@PathVariable UUID questionId,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody QuestionAnswer request,Authentication account){
        requireOwned(conversationId,account);return response(questions.answer(conversationId,questionId,key,request));
    }
    @PostMapping("/conversations/{conversationId}/questions/{questionId}/new-requirement")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Object startNew(@PathVariable UUID conversationId,@PathVariable UUID questionId,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Turn request,Authentication account){
        requireOwned(conversationId,account);return response(questions.startNew(conversationId,questionId,key,request));
    }
    public record QuestionAnswer(@NotBlank @Size(max=16000) String content,com.fasterxml.jackson.databind.JsonNode editing) {}

    @PostMapping("/projects/{projectId}/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@PathVariable UUID projectId,
            @Valid @RequestBody CreateSession request, Authentication account) throws java.io.IOException {
        access.requireOwned(projectId, account);
        UUID id = UUID.randomUUID();
        List<UUID> scope = request.assetIds() == null ? List.of() : request.assetIds();
        for (UUID asset : scope) {
            if (database.queryForObject("SELECT count(*) FROM assets WHERE id=? AND project_id=? AND validation_status='VALID'", Integer.class, asset, projectId) != 1)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "素材不存在或无权访问");
        }
        database.update("INSERT INTO conversations(id,project_id,title,asset_ids_json) VALUES (?,?,?,?::jsonb)",
                id, projectId, request.title().strip(), json.writeValueAsString(scope));
        return response(Map.of("id", id, "projectId", projectId, "title", request.title().strip(), "assetIds", scope));
    }

    @GetMapping("/projects/{projectId}/conversations")
    public Map<String, Object> list(@PathVariable UUID projectId, Authentication account) {
        access.requireOwned(projectId, account);
        return response(Map.of("items", database.query("SELECT id,title,asset_ids_json FROM conversations WHERE project_id=? ORDER BY created_at,id",
                (row, index) -> Map.of("id", row.getObject("id", UUID.class), "title", row.getString("title"), "assetIds", parse(row.getString("asset_ids_json"))), projectId)));
    }

    @GetMapping("/conversations/{conversationId}/drafts")
    public Object drafts(@PathVariable UUID conversationId,@RequestParam(required=false) UUID before,Authentication account) {
        requireOwned(conversationId,account);return response(drafts.page(conversationId,before));
    }

    @GetMapping("/conversations/{conversationId}/snapshot")
    public Object snapshot(@PathVariable UUID conversationId, Authentication account) {
        requireOwned(conversationId,account);
        return response(recovery.conversation(conversationId,account));
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public Map<String, Object> messages(@PathVariable UUID conversationId, Authentication account) {
        requireOwned(conversationId, account);
        var data = new java.util.LinkedHashMap<String,Object>();
        data.put("items", database.query("SELECT id,message_seq,role,content,references_json FROM conversation_messages WHERE conversation_id=? ORDER BY message_seq",
                (row, index) -> Map.of("id", row.getObject("id", UUID.class), "messageSeq", row.getLong("message_seq"),
                        "role", row.getString("role"), "content", row.getString("content"), "references", parse(row.getString("references_json"))), conversationId));
        var tasks = database.queryForList("SELECT t.id AS \"taskId\",t.status, CASE WHEN t.status='FAILED' AND t.result_json->>'code'='MODEL_RECORD_TOO_LARGE' THEN jsonb_build_object('code',t.result_json->>'code','message',t.result_json->>'message') END AS failure FROM conversation_turns r JOIN tasks t ON t.id=r.task_id WHERE r.conversation_id=? ORDER BY r.created_at DESC LIMIT 1", conversationId);
        for (var task : tasks) if (task.get("failure") != null) task.put("failure", parse(task.get("failure").toString()));
        data.put("latestTask", tasks.isEmpty() ? null : tasks.get(0));
        data.put("view", database.queryForMap("SELECT view_plan_id AS \"planId\",view_revision_id AS \"revisionId\",view_row_version AS \"rowVersion\" FROM conversations WHERE id=?", conversationId));
        return response(data);
    }

    private Object parse(String value) {
        try { return json.readTree(value); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }

    private void requireOwned(UUID conversationId, Authentication account) {
        var rows = database.queryForList("SELECT c.project_id FROM conversations c JOIN projects p ON p.id=c.project_id WHERE c.id=? AND p.owner_user_id=?",
                conversationId, access.currentUserId(account));
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "制作会话不存在或无权访问");
    }

    @PostMapping("/conversations/{conversationId}/tasks/{taskId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Object retry(@PathVariable UUID conversationId,@PathVariable UUID taskId,
            @RequestHeader("Idempotency-Key") String key,@RequestBody Retry request,Authentication account) {
        requireOwned(conversationId,account);
        return response(retries.retry(conversationId,taskId,key,request.acknowledgeUnknownCost()));
    }
    public record Retry(boolean acknowledgeUnknownCost) {}

    @PostMapping("/conversations/{conversationId}/turns")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> accept(@PathVariable UUID conversationId,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Turn request, Authentication account) {
        requireOwned(conversationId, account);
        return response(turns.accept(conversationId, key, request));
    }

    private static Map<String, Object> response(Object data) {
        return Map.of("data", data, "requestId", UUID.randomUUID());
    }

    public record CreateSession(@NotBlank @Size(max = 120) String title, @Size(max = 100) List<UUID> assetIds) {}
    public record Turn(@NotBlank @Size(max = 8000) String content,
            @Size(max = 100) List<com.fasterxml.jackson.databind.JsonNode> references,
            com.fasterxml.jackson.databind.JsonNode context) {}
    @PatchMapping("/conversations/{conversationId}/view")
    public Object view(@PathVariable UUID conversationId, @Valid @RequestBody PlanView request, Authentication account) {
        requireOwned(conversationId, account);
        return response(turns.view(conversationId, request));
    }
    public record PlanView(@jakarta.validation.constraints.NotNull UUID planId,
            @jakarta.validation.constraints.NotNull UUID revisionId,
            @jakarta.validation.constraints.Min(0) long expectedRowVersion) {}
}
