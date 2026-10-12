package com.agenticvideo.business.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** A user retry has a fresh execution identity and the original immutable user message. */
@Service
public class ConversationRetries {
    private final JdbcTemplate database;
    private final ObjectMapper json;
    private final ConversationTurns turns;
    private final com.agenticvideo.business.plan.EditPlans plans;
    private final TransactionTemplate transactions;
    public ConversationRetries(JdbcTemplate database,ObjectMapper json,ConversationTurns turns,
            com.agenticvideo.business.plan.EditPlans plans,PlatformTransactionManager manager) {
        this.database=database;this.json=json;this.turns=turns;this.plans=plans;transactions=new TransactionTemplate(manager);
    }
    public Object retry(UUID conversation,UUID source,String key,boolean acknowledgeUnknownCost) {
        if(key.isBlank()||key.length()>120)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"重试幂等键不符合要求");
        return transactions.execute(tx->{
            var session=database.queryForMap("SELECT * FROM conversations WHERE id=? FOR UPDATE",conversation);
            var rows=database.queryForList("SELECT r.*,t.status,t.type,t.input_json FROM conversation_turns r JOIN tasks t ON t.id=r.task_id WHERE r.conversation_id=? AND r.task_id=?",conversation,source);
            if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"会话任务不存在");
            var original=rows.get(0);
            if("SUCCEEDED".equals(original.get("status")))return identities(original);
            var previous=database.queryForList("SELECT * FROM conversation_turns WHERE conversation_id=? AND request_key=?",conversation,key);
            if(!previous.isEmpty()) {
                if(!source.equals(previous.get(0).get("retry_of_task_id")))throw new ResponseStatusException(HttpStatus.CONFLICT,"该幂等键已用于其他需求");
                return identities(previous.get(0));
            }
            if(!"FAILED".equals(original.get("status"))||!"AGENT_TURN".equals(original.get("type")))throw new ResponseStatusException(HttpStatus.CONFLICT,"只能重试已失败的对话任务");
            var committed=database.queryForList("""
                    WITH RECURSIVE successors AS (
                        SELECT task_id FROM conversation_turns WHERE conversation_id=? AND task_id=?
                        UNION ALL SELECT r.task_id FROM conversation_turns r JOIN successors s ON r.retry_of_task_id=s.task_id WHERE r.conversation_id=?
                    ) SELECT r.* FROM conversation_turns r JOIN successors s ON s.task_id=r.task_id
                      JOIN tasks t ON t.id=r.task_id WHERE t.status='SUCCEEDED' LIMIT 1
                    """,conversation,source,conversation);
            if(!committed.isEmpty())return identities(committed.get(0));
            var children=database.queryForList("SELECT r.*,t.status FROM conversation_turns r JOIN tasks t ON t.id=r.task_id WHERE r.retry_of_task_id=?",source);
            if(!children.isEmpty()) {
                if("SUCCEEDED".equals(children.get(0).get("status")))return identities(children.get(0));
                throw new ResponseStatusException(HttpStatus.CONFLICT,"原需求已有重试任务，请查看该任务；失败后从该失败任务重试");
            }
            if(database.queryForObject("SELECT count(*) FROM conversation_turns r JOIN tasks t ON t.id=r.task_id WHERE r.conversation_id=? AND t.status IN ('PENDING','RUNNING','CANCEL_REQUESTED','WAITING_INPUT')",Integer.class,conversation)>0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"会话正在处理需求，请完成或停止后重试");
            if(unknownCost(source)&&!acknowledgeUnknownCost)throw new ResponseStatusException(HttpStatus.CONFLICT,"原模型调用回执未知，提供方不支持查询或停止；费用可能已产生。请明确接受未知费用后再重试");
            ObjectNode input=(ObjectNode)parse(original.get("input_json"));
            turns.validateRetry(session,input);
            UUID task=UUID.randomUUID(),turn=UUID.randomUUID(),event=UUID.randomUUID();
            input.put("turnId",turn.toString());
            if(input.has("editing"))input.set("editing",plans.allocate((UUID)session.get("project_id"),task,input.path("editing"),input.path("assetIds")));
            database.update("INSERT INTO tasks(id,project_id,type,input_json) VALUES (?,?,'AGENT_TURN',?::jsonb)",task,session.get("project_id"),input.toString());
            var originalRequest=parse(original.get("request_json"));
            ObjectNode receipt=json.createObjectNode();receipt.set("originalRequest",originalRequest.has("originalRequest")?originalRequest.path("originalRequest"):originalRequest);receipt.put("acknowledgeUnknownCost",acknowledgeUnknownCost);
            database.update("INSERT INTO conversation_turns(id,conversation_id,message_id,task_id,request_key,request_json,retry_of_task_id) VALUES (?,?,?,?,?,?::jsonb,?)",turn,conversation,original.get("message_id"),task,key,receipt.toString(),source);
            database.update("INSERT INTO outbox_events(id,task_id,type,payload_json) VALUES (?,?,'AGENT_TURN',jsonb_build_object('schemaVersion','1.0','eventId',?::text,'taskId',?::text,'taskType','AGENT_TURN','projectId',?::text,'occurredAt',now()))",event,task,event,task,session.get("project_id"));
            return identities(database.queryForMap("SELECT * FROM conversation_turns WHERE id=?",turn));
        });
    }
    private Object identities(Map<String,Object> turn) {
        var result=new java.util.LinkedHashMap<String,Object>();
        result.put("turnId",turn.get("id"));result.put("messageId",turn.get("message_id"));result.put("taskId",turn.get("task_id"));
        if(turn.get("retry_of_task_id")!=null)result.put("retryOfTaskId",turn.get("retry_of_task_id"));
        var input=parse(database.queryForObject("SELECT input_json FROM tasks WHERE id=?",String.class,turn.get("task_id")));
        if(input.has("editing"))result.put("planId",input.path("editing").path("planId").asText());
        return result;
    }
    public Map<String,Object> effects(UUID task) {
        return Map.of("stopSupported",false,"unknownCost",unknownCost(task),"message","停止会撤销本地发布资格；DeepSeek 与查询 Embedding 同步调用不支持查询或远端停止，已发生或未知费用不能保证撤销。");
    }
    private boolean unknownCost(UUID task) {return database.queryForObject("SELECT EXISTS(SELECT 1 FROM conversation_model_calls WHERE task_id=? AND status IN ('SUBMITTING','UNCERTAIN') UNION ALL SELECT 1 FROM query_model_calls WHERE task_id=? AND status IN ('SUBMITTING','UNCERTAIN'))",Boolean.class,task,task);}
    private JsonNode parse(Object value) {try{return json.readTree(value.toString());}catch(java.io.IOException invalid){throw new IllegalStateException(invalid);}}
}
