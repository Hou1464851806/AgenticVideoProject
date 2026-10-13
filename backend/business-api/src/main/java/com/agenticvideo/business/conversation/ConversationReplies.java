package com.agenticvideo.business.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/** Binds a natural-language reply to the question observed when its message was accepted. */
@Service
public class ConversationReplies {
    private final JdbcTemplate database;
    private final ObjectMapper json;
    public ConversationReplies(JdbcTemplate database,ObjectMapper json){this.database=database;this.json=json;}
    public JsonNode pending(UUID conversation){
        var rows=database.queryForList("SELECT q.*,coalesce(r.input_json,t.input_json) AS source_input FROM conversation_questions q JOIN tasks t ON t.id=q.task_id LEFT JOIN conversation_execution_requests r ON r.task_id=t.id WHERE q.conversation_id=? AND q.status='OPEN' AND t.status='WAITING_INPUT' ORDER BY q.created_at,q.id",conversation);
        if(rows.isEmpty())return null;
        if(rows.size()!=1)throw new ResponseStatusException(CONFLICT,"存在多个待回答问题，请先明确要处理的问题");
        var row=rows.get(0);JsonNode source=parse(row.get("source_input"));
        var original=json.createObjectNode();
        for(String field:java.util.List.of("content","intent","requirementId","editing","brief","assetIds"))if(source.has(field))original.set(field,source.path(field));
        return json.valueToTree(Map.of("questionId",row.get("id"),"taskId",row.get("task_id"),"prompt",row.get("prompt"),"fields",parse(row.get("fields_json")),"originalRequest",original));
    }
    /** Caller owns the current task lock; use the same source-task/conversation/question order as explicit answers. */
    public void answer(Map<String,Object> task,JsonNode input,JsonNode request){
        if(!input.has("pendingQuestion")){if(request.has("questionAction"))throw new ResponseStatusException(BAD_REQUEST,"本轮没有关联的待回答问题");return;}
        String action=request.path("questionAction").asText();
        if(!java.util.Set.of("ANSWER","AMBIGUOUS","CANCEL","NEW").contains(action)||request.path("newRequirement").asBoolean()!= "NEW".equals(action))throw new ResponseStatusException(BAD_REQUEST,"须明确本消息与待回答问题的关系");
        if(("AMBIGUOUS".equals(action)&&!"DISCUSS".equals(request.path("intent").asText()))||("CANCEL".equals(action)&&!"CANCEL".equals(request.path("intent").asText()))||(java.util.Set.of("AMBIGUOUS","CANCEL").contains(action)&&!request.path("fields").isEmpty()))throw new ResponseStatusException(BAD_REQUEST,"取消或含糊回复不能更改条件或制作方案");
        JsonNode pending=input.path("pendingQuestion");UUID source=UUID.fromString(pending.path("taskId").asText()),conversation=UUID.fromString(input.path("conversationId").asText()),question=UUID.fromString(pending.path("questionId").asText());
        var original=database.queryForMap("SELECT * FROM tasks WHERE id=? FOR UPDATE",source);
        var session=database.queryForMap("SELECT * FROM conversations WHERE id=? FOR UPDATE",conversation);
        var rows=database.queryForList("SELECT * FROM conversation_questions WHERE id=? AND task_id=? AND conversation_id=? FOR UPDATE",question,source,conversation);
        if(rows.isEmpty()||!"OPEN".equals(rows.get(0).get("status"))||!"WAITING_INPUT".equals(original.get("status")))throw new ResponseStatusException(CONFLICT,"原问题已结束，不能恢复旧等待");
        if(!task.get("project_id").equals(original.get("project_id")))throw new ResponseStatusException(NOT_FOUND,"原问题不属于本项目");
        if("ANSWER".equals(action)&&"GENERATE_SPEECH".equals(request.path("intent").asText())){
            for(var field:pending.path("fields"))if(!request.path("fields").has(field.asText()))throw new ResponseStatusException(BAD_REQUEST,"待澄清条件尚未补齐，不能默认后继续制作");
        }
        if("AMBIGUOUS".equals(action))return;
        if("CANCEL".equals(action)||"NEW".equals(action)){
            database.update("UPDATE conversation_questions SET status='CANCELLED' WHERE id=?",question);
            database.update("UPDATE tasks SET status='CANCELLED',result_json=jsonb_build_object('reason','EXPLICIT_CANCEL') WHERE id=?",source);
            return;
        }
        JsonNode scope=pending.path("originalRequest").path("assetIds"),current=parse(session.get("asset_ids_json"));
        for(var asset:scope){
            boolean selected=false;for(var allowed:current)if(allowed.equals(asset))selected=true;
            if(!selected||database.queryForObject("SELECT count(*) FROM assets WHERE id=? AND project_id=? AND validation_status='VALID'",Integer.class,UUID.fromString(asset.asText()),task.get("project_id"))!=1)throw new ResponseStatusException(CONFLICT,"原问题素材已变化，请明确开始新需求");
        }
        database.update("UPDATE conversation_questions SET status='ANSWERED',answer_message_id=? WHERE id=?",UUID.fromString(input.path("messageId").asText()),question);
        database.update("UPDATE conversation_turns SET resumes_task_id=? WHERE task_id=?",source,task.get("id"));
        database.update("UPDATE tasks SET status='SUCCEEDED',result_json=jsonb_build_object('continuedTaskId',?::text,'questionId',?::text) WHERE id=?",task.get("id"),question,source);
    }
    private JsonNode parse(Object value){try{return json.readTree(value.toString());}catch(java.io.IOException invalid){throw new IllegalStateException(invalid);}}
}
