package com.agenticvideo.business.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ConversationTurns {
    private final JdbcTemplate database;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final com.agenticvideo.business.plan.EditPlans plans;
    private final com.agenticvideo.business.analysis.SpeechTimings timings;
    private final com.agenticvideo.business.render.ProductionCommands production;

    public ConversationTurns(JdbcTemplate database, ObjectMapper json, PlatformTransactionManager manager,
            com.agenticvideo.business.plan.EditPlans plans, com.agenticvideo.business.analysis.SpeechTimings timings, com.agenticvideo.business.render.ProductionCommands production) {
        this.database = database;
        this.json = json;
        this.transactions = new TransactionTemplate(manager);
        this.plans = plans;
        this.timings = timings;
        this.production = production;
    }

    public Object accept(UUID sessionId, String key, ConversationController.Turn request) {
        if (key.isBlank() || key.length() > 120) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "发送幂等键不符合要求");
        boolean replayExists=database.queryForObject("SELECT count(*) FROM conversation_turns WHERE conversation_id=? AND request_key=?",Integer.class,sessionId,key)>0;
        var preparation=replayExists?null:production.prepare(sessionId,request.content());
        return transactions.execute(tx -> {
            var session = database.queryForMap("SELECT * FROM conversations WHERE id=? FOR UPDATE", sessionId);
            String payload = encode(Map.of("content", request.content(),
                    "references", request.references() == null ? List.of() : request.references(),
                    "context", request.context() == null ? Map.of() : request.context()));
            var previous = database.queryForList("SELECT *,request_json=?::jsonb AS same_request FROM conversation_turns WHERE conversation_id=? AND request_key=?", payload, sessionId, key);
            if (!previous.isEmpty()) {
                var turn = previous.get(0);
                if (!Boolean.TRUE.equals(turn.get("same_request"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "同一发送幂等键不能用于不同需求");
                return identities((UUID)turn.get("id"), (UUID)turn.get("message_id"), (UUID)turn.get("task_id"));
            }
            if (database.queryForObject("SELECT count(*) FROM conversation_turns r JOIN tasks t ON t.id=r.task_id WHERE r.conversation_id=? AND t.status IN ('PENDING','RUNNING','CANCEL_REQUESTED','WAITING_INPUT')", Integer.class, sessionId) > 0)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "制作会话正在处理需求；可继续编辑草稿，完成或停止后再发送");
            validateReferences(session, request);
            UUID turn = UUID.randomUUID(), message = UUID.randomUUID(), task = UUID.randomUUID(), event = UUID.randomUUID();
            long sequence = database.queryForObject("SELECT coalesce(max(message_seq),0)+1 FROM conversation_messages WHERE conversation_id=?", Long.class, sessionId);
            database.update("INSERT INTO conversation_messages(id,conversation_id,message_seq,role,content,references_json) VALUES (?,?,?,'USER',?,?::jsonb)",
                    message, sessionId, sequence, request.content(), encode(request.references() == null ? List.of() : request.references()));
            var command=production.route(session,message,request.content(),preparation);
            if(command!=null){
                database.update("INSERT INTO tasks(id,project_id,type,status,input_json) VALUES (?,?,'PRODUCTION_COMMAND','SUCCEEDED',?::jsonb)",task,session.get("project_id"),encode(Map.of("messageId",message,"conversationId",sessionId)));
                database.update("INSERT INTO conversation_turns(id,conversation_id,message_id,task_id,request_key,request_json) VALUES (?,?,?,?,?,?::jsonb)",turn,sessionId,message,task,key,payload);
                database.update("INSERT INTO conversation_messages(id,conversation_id,message_seq,role,content,references_json,source_task_id) VALUES (?,?,?,'ASSISTANT',?,?::jsonb,?)",UUID.randomUUID(),sessionId,sequence+1,command.content(),encode(command.references()),task);
                return identities(turn,message,task);
            }
            var fixedInput = new java.util.LinkedHashMap<String,Object>(Map.of("conversationId", sessionId, "turnId", turn, "messageId", message,
                    "content", request.content(), "assetIds", request.context() != null && request.context().has("assetIds")
                            ? request.context().path("assetIds") : decode(session.get("asset_ids_json")),
                    "history", database.queryForList("SELECT role,content FROM (SELECT role,content,message_seq FROM conversation_messages WHERE conversation_id=? ORDER BY message_seq DESC LIMIT 12) h ORDER BY message_seq", sessionId),
                    "references", request.references() == null ? List.of() : request.references(),
                    "context", request.context() == null ? Map.of() : request.context()));
            if (request.context() != null && request.context().has("editing"))
                fixedInput.put("editing", plans.allocate((UUID)session.get("project_id"), task, request.context().path("editing")));
            String input = encode(fixedInput);
            database.update("INSERT INTO tasks(id,project_id,type,input_json) VALUES (?,?,'AGENT_TURN',?::jsonb)", task, session.get("project_id"), input);
            database.update("INSERT INTO conversation_turns(id,conversation_id,message_id,task_id,request_key,request_json) VALUES (?,?,?,?,?,?::jsonb)", turn, sessionId, message, task, key, payload);
            database.update("INSERT INTO outbox_events(id,task_id,type,payload_json) VALUES (?,?,'AGENT_TURN',jsonb_build_object('schemaVersion','1.0','eventId',?::text,'taskId',?::text,'taskType','AGENT_TURN','projectId',?::text,'occurredAt',now()))", event, task, event, task, session.get("project_id"));
            return identities(turn, message, task);
        });
    }

    public Object view(UUID sessionId, ConversationController.PlanView request) {
        return transactions.execute(tx -> {
            var session = database.queryForMap("SELECT project_id FROM conversations WHERE id=? FOR UPDATE", sessionId);
            if (database.queryForObject("SELECT count(*) FROM plan_revisions WHERE id=? AND plan_id=? AND project_id=?", Integer.class,
                    request.revisionId(), request.planId(), session.get("project_id")) != 1)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "方案版本不存在或不属于本项目");
            if (database.update("UPDATE conversations SET view_plan_id=?,view_revision_id=?,view_row_version=view_row_version+1 WHERE id=? AND view_row_version=?",
                    request.planId(), request.revisionId(), sessionId, request.expectedRowVersion()) != 1)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "查看版本已变化，请重新读取会话");
            return database.queryForMap("SELECT view_plan_id AS \"planId\",view_revision_id AS \"revisionId\",view_row_version AS \"rowVersion\" FROM conversations WHERE id=?", sessionId);
        });
    }

    private void validateReferences(Map<String, Object> session, ConversationController.Turn request) {
        var scope = (com.fasterxml.jackson.databind.JsonNode)decode(session.get("asset_ids_json"));
        java.util.Set<UUID> allowed = new java.util.HashSet<>();
        for (var asset : scope) allowed.add(UUID.fromString(asset.asText()));
        if (request.context() != null) {
            if (!request.context().isObject()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "查询上下文必须是对象");
            request.context().fieldNames().forEachRemaining(field -> {
                if (!java.util.Set.of("assetIds", "editing").contains(field)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "查询上下文字段不受支持");
            });
            if (request.context().has("assetIds")) {
                var selected = request.context().path("assetIds");
                if (!selected.isArray() || selected.size() > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "素材范围不符合要求");
                for (var asset : selected) requireAsset(identifier(asset.asText()), session.get("project_id"), allowed);
                allowed.clear();
                for (var asset : selected) allowed.add(identifier(asset.asText()));
            }
        }
        for (var reference : request.references() == null ? List.<com.fasterxml.jackson.databind.JsonNode>of() : request.references()) {
            String type = reference.path("type").asText();
            if (!java.util.Set.of("ASSET", "EVIDENCE").contains(type)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "查询引用类型不受支持");
            UUID id = identifier(reference.path("id").asText());
            if ("ASSET".equals(type)) requireAsset(id, session.get("project_id"), allowed);
            else {
                var evidence = database.queryForList("SELECT e.asset_id FROM evidence_segments e JOIN asset_analysis_heads h ON h.analysis_id=e.analysis_id WHERE e.id=? AND e.project_id=?", id, session.get("project_id"));
                if (evidence.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "证据不存在或无权访问");
                requireAsset((UUID)evidence.get(0).get("asset_id"), session.get("project_id"), allowed);
            }
        }
    }

    /** Called inside the authoritative task completion transaction. */
    public Object publish(Map<String, Object> task, com.fasterxml.jackson.databind.JsonNode input,
            com.fasterxml.jackson.databind.JsonNode result) {
        UUID sessionId = identifier(input.path("conversationId").asText());
        database.queryForMap("SELECT id FROM conversations WHERE id=? FOR UPDATE", sessionId);
        String content = result.path("content").asText();
        var citations = result.path("evidenceIds");
        if (content.isBlank() || content.length() > 16000 || !citations.isArray() || citations.size() > 50)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "公开答复不符合格式要求");
        java.util.Set<UUID> scope = new java.util.HashSet<>();
        for (var asset : input.path("assetIds")) scope.add(identifier(asset.asText()));
        var references = new java.util.ArrayList<Object>();
        for (var citation : citations) {
            UUID id = identifier(citation.asText());
            boolean observed = Boolean.TRUE.equals(database.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM conversation_tool_receipts receipt,
                        LATERAL jsonb_array_elements(
                            coalesce(receipt.result_json->'hits','[]'::jsonb) ||
                            coalesce(receipt.result_json->'observations','[]'::jsonb)) observation
                        WHERE receipt.task_id=?
                        AND receipt.request_json->>'name' IN ('search_evidence','read_evidence_context')
                        AND observation->>'evidenceId'=?
                    )
                    """, Boolean.class, task.get("id"), id.toString()));
            if (!observed) throw new ResponseStatusException(HttpStatus.CONFLICT, "答复引用未经本轮业务工具核查");
            var rows = database.queryForList("SELECT id,asset_id,text,start_ms,end_ms,source_kind FROM evidence_segments WHERE id=? AND project_id=?", id, task.get("project_id"));
            if (rows.isEmpty() || !scope.contains((UUID)rows.get(0).get("asset_id")))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "答复引用不在固定素材范围内");
            var row = rows.get(0);
            references.add(Map.of("type", "EVIDENCE", "id", id, "assetId", row.get("asset_id"),
                    "text", row.get("text"), "startMs", row.get("start_ms"), "endMs", row.get("end_ms"), "sourceKind", row.get("source_kind")));
        }
        UUID message = UUID.randomUUID();
        if (result.has("planRevision")) {
            var revision = result.path("planRevision");
            references.add(Map.of("type", "PLAN_REVISION", "id", revision.path("revisionId").asText(),
                    "planId", revision.path("planId").asText(), "revisionNumber", revision.path("revisionNumber").asLong()));
        }
        long sequence = database.queryForObject("SELECT coalesce(max(message_seq),0)+1 FROM conversation_messages WHERE conversation_id=?", Long.class, sessionId);
        database.update("INSERT INTO conversation_messages(id,conversation_id,message_seq,role,content,references_json,source_task_id) VALUES (?,?,?,'ASSISTANT',?,?::jsonb,?)",
                message, sessionId, sequence, content, encode(references), task.get("id"));
        return Map.of("status", "SUCCEEDED", "messageId", message, "conversationId", sessionId);
    }

    private void requireAsset(UUID id, Object project, java.util.Set<UUID> scope) {
        if (!scope.contains(id) || database.queryForObject("SELECT count(*) FROM assets WHERE id=? AND project_id=? AND validation_status='VALID'", Integer.class, id, project) != 1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "素材不存在或不在制作会话范围内");
    }

    private UUID identifier(String value) {
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException failure) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "引用标识不符合要求"); }
    }

    public Object tool(Map<String, Object> task, com.fasterxml.jackson.databind.JsonNode input,
            com.agenticvideo.business.task.TaskController.Tool request) {
        String name = request.name();
        var arguments = request.arguments();
        var fields = switch (name) {
            case "list_assets" -> java.util.Set.<String>of();
            case "search_evidence" -> java.util.Set.of("query");
            case "read_evidence_context" -> java.util.Set.of("evidenceId");
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工具不受支持");
        };
        if (!arguments.isObject()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工具参数必须是对象");
        arguments.fieldNames().forEachRemaining(field -> {
            if (!fields.contains(field)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工具不能覆盖业务权限范围");
        });
        String payload = encode(Map.of("name", name, "arguments", arguments));
        var previous = database.queryForList("SELECT result_json,request_json=?::jsonb AS same_request FROM conversation_tool_receipts WHERE task_id=? AND producer_key=?", payload, task.get("id"), request.producerKey());
        if (!previous.isEmpty()) {
            if (!Boolean.TRUE.equals(previous.get(0).get("same_request"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "工具幂等键内容不同");
            return decode(previous.get(0).get("result_json"));
        }
        if (database.queryForObject("SELECT count(*) FROM conversation_tool_receipts WHERE task_id=?", Integer.class, task.get("id")) >= 12)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "本轮工具调用预算已耗尽");
        String scope = input.path("assetIds").toString();
        Object result;
        if ("list_assets".equals(name)) {
            var assets = database.queryForList("""
                    SELECT a.id AS "assetId",a.filename,a.duration_ms AS "durationMs",s.status AS "speechStatus"
                    FROM assets a LEFT JOIN asset_analysis_heads h ON h.asset_id=a.id
                    LEFT JOIN analysis_stages s ON s.analysis_id=h.analysis_id AND s.stage_key='ASR'
                    WHERE a.project_id=? AND a.validation_status='VALID'
                    AND a.id IN (SELECT value::uuid FROM jsonb_array_elements_text(?::jsonb)) ORDER BY a.id
                    """, task.get("project_id"), scope);
            // SQL nulls stay in the result rather than exposing private media metadata.
            result = Map.of("assets", assets);
        } else if ("search_evidence".equals(name)) {
            if (!arguments.path("query").isTextual() || arguments.path("query").asText().length() > 256)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "检索词必须是最多 256 字符的文本");
            var rows = database.queryForList("""
                    SELECT e.id AS "evidenceId",e.asset_id AS "assetId",a.filename,e.text,
                        e.start_ms AS "startMs",e.end_ms AS "endMs",e.source_kind AS "sourceKind"
                    FROM evidence_segments e JOIN assets a ON a.id=e.asset_id AND a.project_id=e.project_id
                    JOIN asset_analysis_heads h ON h.asset_id=a.id AND h.analysis_id=e.analysis_id
                    JOIN asset_analysis_revisions r ON r.id=h.analysis_id AND r.source_sha256=a.sha256
                    WHERE e.project_id=? AND a.validation_status='VALID' AND e.source_kind='SPEECH'
                    AND e.asset_id IN (SELECT value::uuid FROM jsonb_array_elements_text(?::jsonb))
                    AND position(? in e.text)>0 ORDER BY a.id,e.start_ms,e.id LIMIT 21
                    """, task.get("project_id"), scope, arguments.path("query").asText());
            result = Map.of("hits", rows.size() > 20 ? rows.subList(0,20) : rows,
                    "truncated", rows.size() > 20, "channels", List.of("LITERAL"), "semanticAvailable", false,
                    "coverage", "仅检索固定素材范围内已发布的语音原文；未命中不能证明原视频不存在相关内容");
        } else {
            UUID evidence = identifier(arguments.path("evidenceId").asText());
            var targets = database.queryForList("""
                    SELECT e.* FROM evidence_segments e JOIN asset_analysis_heads h ON h.analysis_id=e.analysis_id
                    WHERE e.id=? AND e.project_id=? AND e.asset_id IN (SELECT value::uuid FROM jsonb_array_elements_text(?::jsonb))
                    """, evidence, task.get("project_id"), scope);
            if (targets.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "证据不在固定素材范围内");
            var target = targets.get(0);
            var observations = database.queryForList("""
                    SELECT id AS "evidenceId",asset_id AS "assetId",text,start_ms AS "startMs",end_ms AS "endMs",source_kind AS "sourceKind",
                        start_ms,end_ms,audio_start_ms,observation_json
                    FROM evidence_segments WHERE project_id=? AND asset_id=? AND analysis_id=? AND source_kind=?
                    AND ordinal BETWEEN ? AND ? ORDER BY ordinal LIMIT 11
                    """, task.get("project_id"), target.get("asset_id"), target.get("analysis_id"), target.get("source_kind"),
                    ((Number)target.get("ordinal")).intValue()-5, ((Number)target.get("ordinal")).intValue()+5);
            for (var observation : observations) {
                observation.put("words", timings.words(observation));
                for (String field : List.of("start_ms","end_ms","audio_start_ms","observation_json")) observation.remove(field);
            }
            result = input.has("editing") ? Map.of("observations", observations, "candidateWindow", plans.candidateWindow(input,target,observations))
                    : Map.of("observations", observations);
        }
        database.update("INSERT INTO conversation_tool_receipts(task_id,attempt_id,producer_key,request_json,result_json) VALUES (?,?,?,?::jsonb,?::jsonb)",
                task.get("id"), request.attemptId(), request.producerKey(), payload, encode(result));
        return result;
    }

    public Object provider(Map<String, Object> task, UUID attempt, String action, com.fasterxml.jackson.databind.JsonNode data) {
        int number = data.path("callNumber").asInt();
        if (number < 1 || number > 6) throw new ResponseStatusException(HttpStatus.CONFLICT, "本轮模型调用预算已耗尽");
        var rows = database.queryForList("SELECT * FROM conversation_model_calls WHERE task_id=? AND call_number=? FOR UPDATE", task.get("id"), number);
        if ("PREPARE".equals(action)) {
            if (!data.path("request").isObject() || data.path("request").toString().length() > 100000)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模型请求不符合预算要求");
            String payload = data.path("request").toString();
            if (!rows.isEmpty()) {
                var call = rows.get(0);
                Boolean same = database.queryForObject("SELECT request_json=?::jsonb FROM conversation_model_calls WHERE id=?", Boolean.class, payload, call.get("id"));
                if (!Boolean.TRUE.equals(same)) throw new ResponseStatusException(HttpStatus.CONFLICT, "模型调用输入与已保存请求不一致");
                if ("SUCCEEDED".equals(call.get("status"))) return Map.of("canSubmit", false, "status", "SUCCEEDED", "response", decode(call.get("response_json")));
                return Map.of("canSubmit", false, "status", "UNCERTAIN");
            }
            database.update("INSERT INTO conversation_model_calls(id,task_id,attempt_id,call_number,request_json,status) VALUES (?,?,?,?,?::jsonb,'SUBMITTING')",
                    UUID.randomUUID(), task.get("id"), attempt, number, payload);
            return Map.of("canSubmit", true, "status", "SUBMITTING");
        }
        if (rows.isEmpty() || !attempt.equals(rows.get(0).get("attempt_id"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "模型调用没有当前提交资格");
        var call = rows.get(0);
        if ("UNCERTAIN".equals(action)) {
            database.update("UPDATE conversation_model_calls SET status='UNCERTAIN' WHERE id=? AND status='SUBMITTING'", call.get("id"));
            return Map.of("status", "UNCERTAIN");
        }
        if (!"SUCCEEDED".equals(action) || !data.path("response").isObject() || data.path("response").toString().length() > 100000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模型回执格式不符合要求");
        String response = data.path("response").toString();
        if ("SUCCEEDED".equals(call.get("status"))) {
            if (!Boolean.TRUE.equals(database.queryForObject("SELECT response_json=?::jsonb FROM conversation_model_calls WHERE id=?", Boolean.class, response, call.get("id"))))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "模型回执内容不同");
        } else {
            if (!"SUBMITTING".equals(call.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "模型调用状态不允许发布");
            database.update("UPDATE conversation_model_calls SET status='SUCCEEDED',response_json=?::jsonb,usage_json=?::jsonb WHERE id=?",
                    response, data.path("response").path("usage").toString(), call.get("id"));
        }
        return Map.of("status", "SUCCEEDED");
    }

    private Object decode(Object value) {
        try { return json.readTree(value.toString()); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }

    private Map<String, Object> identities(UUID turn, UUID message, UUID task) {
        var result = new java.util.LinkedHashMap<String,Object>(Map.of("turnId", turn, "messageId", message, "taskId", task));
        var input = (com.fasterxml.jackson.databind.JsonNode)decode(database.queryForObject("SELECT input_json FROM tasks WHERE id=?", String.class, task));
        if (input.has("editing")) result.put("planId", input.path("editing").path("planId").asText());
        return result;
    }
}
