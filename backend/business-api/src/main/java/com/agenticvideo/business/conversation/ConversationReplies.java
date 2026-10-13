package com.agenticvideo.business.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/** Owns question locking and resolution for both explicit and natural-language replies. */
@Service
public class ConversationReplies {
    private final JdbcTemplate database;
    private final ObjectMapper json;
    private final com.agenticvideo.business.plan.EditPlans plans;
    public ConversationReplies(JdbcTemplate database,ObjectMapper json,com.agenticvideo.business.plan.EditPlans plans){this.database=database;this.json=json;this.plans=plans;}
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
    public void admit(Map<String,Object> task,JsonNode input,JsonNode request){
        if(!input.has("pendingQuestion")){if(request.has("questionAction"))throw new ResponseStatusException(BAD_REQUEST,"本轮没有关联的待回答问题");return;}
        String action=request.path("questionAction").asText();
        if(!java.util.Set.of("ANSWER","AMBIGUOUS","CANCEL","NEW").contains(action)||request.path("newRequirement").asBoolean()!= "NEW".equals(action))throw new ResponseStatusException(BAD_REQUEST,"须明确本消息与待回答问题的关系");
        if(("AMBIGUOUS".equals(action)&&!"DISCUSS".equals(request.path("intent").asText()))||("CANCEL".equals(action)&&!"CANCEL".equals(request.path("intent").asText()))||(java.util.Set.of("AMBIGUOUS","CANCEL").contains(action)&&!request.path("fields").isEmpty()))throw new ResponseStatusException(BAD_REQUEST,"取消或含糊回复不能更改条件或制作方案");
        JsonNode pending=input.path("pendingQuestion");UUID source=UUID.fromString(pending.path("taskId").asText()),conversation=UUID.fromString(input.path("conversationId").asText()),question=UUID.fromString(pending.path("questionId").asText());
        var waiting=lock(conversation,question);
        requireOpen(waiting);
        if(!source.equals(waiting.source().get("id"))||!task.get("project_id").equals(waiting.source().get("project_id")))throw new ResponseStatusException(NOT_FOUND,"原问题不属于本项目");
        if("ANSWER".equals(action)&&java.util.Set.of("GENERATE_SPEECH","GENERATE_MONTAGE").contains(request.path("intent").asText())){
            for(var field:pending.path("fields"))if(!request.path("fields").has(field.asText()))throw new ResponseStatusException(BAD_REQUEST,"待澄清条件尚未补齐，不能默认后继续制作");
        }
        if("AMBIGUOUS".equals(action))return;
        if("CANCEL".equals(action)||"NEW".equals(action)){
            end(waiting,"NEW".equals(action)?"EXPLICIT_NEW_REQUIREMENT":"EXPLICIT_CANCEL");
            return;
        }
        JsonNode scope=pending.path("originalRequest").path("assetIds"),current=parse(waiting.session().get("asset_ids_json"));
        for(var asset:scope){
            boolean selected=false;for(var allowed:current)if(allowed.equals(asset))selected=true;
            if(!selected||database.queryForObject("SELECT count(*) FROM assets WHERE id=? AND project_id=? AND validation_status='VALID'",Integer.class,UUID.fromString(asset.asText()),task.get("project_id"))!=1)throw new ResponseStatusException(CONFLICT,"原问题素材已变化，请明确开始新需求");
        }
        answered(waiting,(UUID)task.get("id"),UUID.fromString(input.path("messageId").asText()));
    }
    record Waiting(Map<String,Object> source,Map<String,Object> session,Map<String,Object> question){}
    Waiting lock(UUID conversation,UUID question){
        var identities=database.queryForList("SELECT task_id FROM conversation_questions WHERE id=? AND conversation_id=?",question,conversation);
        if(identities.isEmpty())throw new ResponseStatusException(NOT_FOUND,"澄清问题不存在或不属于本会话");
        var source=database.queryForMap("SELECT * FROM tasks WHERE id=? FOR UPDATE",identities.get(0).get("task_id"));
        var session=database.queryForMap("SELECT * FROM conversations WHERE id=? FOR UPDATE",conversation);
        var original=database.queryForMap("SELECT * FROM conversation_questions WHERE id=? AND conversation_id=? FOR UPDATE",question,conversation);
        return new Waiting(source,session,original);
    }
    void requireOpen(Waiting waiting){
        if(!"OPEN".equals(waiting.question().get("status"))||!"WAITING_INPUT".equals(waiting.source().get("status")))throw new ResponseStatusException(CONFLICT,"原问题已结束，不能恢复旧等待");
    }
    void answered(Waiting waiting,UUID next,UUID message){
        database.update("UPDATE conversation_questions SET status='ANSWERED',answer_message_id=? WHERE id=?",message,waiting.question().get("id"));
        database.update("UPDATE conversation_turns SET resumes_task_id=? WHERE task_id=?",waiting.source().get("id"),next);
        database.update("UPDATE tasks SET status='SUCCEEDED',result_json=jsonb_build_object('continuedTaskId',?::text,'questionId',?::text) WHERE id=?",next,waiting.question().get("id"),waiting.source().get("id"));
    }
    void end(Waiting waiting,String reason){
        database.update("UPDATE conversation_questions SET status='CANCELLED' WHERE id=?",waiting.question().get("id"));
        database.update("UPDATE tasks SET status='CANCELLED',result_json=jsonb_build_object('reason',?::text) WHERE id=?",reason,waiting.source().get("id"));
        plans.release((UUID)waiting.source().get("id"));
    }
    /** Caller holds the task lock, including ordinary task cancellation. */
    void dismiss(UUID task){
        var rows=database.queryForList("SELECT conversation_id FROM conversation_questions WHERE task_id=?",task);
        if(rows.isEmpty())return;
        database.queryForMap("SELECT id FROM conversations WHERE id=? FOR UPDATE",rows.get(0).get("conversation_id"));
        database.update("UPDATE conversation_questions SET status='CANCELLED' WHERE task_id=? AND status='OPEN'",task);
    }
    private JsonNode parse(Object value){try{return json.readTree(value.toString());}catch(java.io.IOException invalid){throw new IllegalStateException(invalid);}}
}
