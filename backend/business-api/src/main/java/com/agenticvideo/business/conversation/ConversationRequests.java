package com.agenticvideo.business.conversation;

import com.agenticvideo.business.plan.EditPlans;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/** Interpreted requests are linked records; the accepted task input remains immutable. */
@Service
public class ConversationRequests {
    private final JdbcTemplate database;
    private final ObjectMapper json;
    private final ConversationBriefs briefs;
    private final EditPlans plans;
    private final ConversationReplies replies;
    public ConversationRequests(JdbcTemplate database,ObjectMapper json,ConversationBriefs briefs,EditPlans plans,ConversationReplies replies){this.database=database;this.json=json;this.briefs=briefs;this.plans=plans;this.replies=replies;}

    public void validate(JsonNode input,JsonNode request){
        if(!input.path("context").path("naturalLanguage").asBoolean(false))throw new ResponseStatusException(BAD_REQUEST,"本轮不是自然语言需求");
        if(!java.util.Set.of("GENERATE_SPEECH","GENERATE_MONTAGE","DISCUSS","QUERY","CLARIFY","CANCEL").contains(request.path("intent").asText())||!request.path("newRequirement").isBoolean())throw new ResponseStatusException(BAD_REQUEST,"需求意图不受支持");
        if(!request.path("fields").isObject())throw new ResponseStatusException(BAD_REQUEST,"需求条件须为对象");
        if(!request.path("fields").isEmpty())briefs.validate(input,request.path("fields"));
    }

    /** Runs only inside the authoritative tool transaction. */
    public JsonNode admit(Map<String,Object> task,JsonNode input,JsonNode request){
        validate(input,request);
        var previous=database.queryForList("SELECT request_json,input_json FROM conversation_execution_requests WHERE task_id=?",task.get("id"));
        if(!previous.isEmpty()){
            if(!parse(previous.get(0).get("request_json")).equals(request))throw new ResponseStatusException(CONFLICT,"本轮解释已接纳，不能改写执行请求");
            return parse(previous.get(0).get("input_json"));
        }
        UUID conversation=UUID.fromString(input.path("conversationId").asText());
        replies.admit(task,input,request);
        database.queryForMap("SELECT id FROM conversations WHERE id=? FOR UPDATE",conversation);
        UUID requirement;
        var prior=database.queryForList("SELECT requirement_id FROM conversation_execution_requests r JOIN conversation_turns t ON t.task_id=r.task_id WHERE t.conversation_id=? ORDER BY r.created_at DESC LIMIT 1",conversation);
        boolean fresh=request.path("newRequirement").asBoolean()||input.path("startsNewRequirement").asBoolean()||prior.isEmpty();
        requirement=fresh?UUID.randomUUID():(UUID)prior.get(0).get("requirement_id");
        if(fresh)briefs.start(conversation,(UUID)task.get("id"),UUID.fromString(input.path("messageId").asText()));
        ObjectNode fields=request.path("fields").deepCopy();
        JsonNode current=(JsonNode)briefs.snapshot(conversation).get("fields");
        if(java.util.Set.of("GENERATE_SPEECH","GENERATE_MONTAGE").contains(request.path("intent").asText())){
            defaults(fields,current,"duration",Map.of("minMs",30000,"maxMs",45000));
            defaults(fields,current,"count",1);defaults(fields,current,"profile","LANDSCAPE_720");
        }
        if(!fields.isEmpty())briefs.append(task,input,fields);
        ObjectNode fixed=input.deepCopy();fixed.put("requirementId",requirement.toString());
        if(request.has("questionAction"))fixed.set("questionAction",request.path("questionAction"));
        fixed.put("intent",request.path("intent").asText());fixed.set("brief",json.valueToTree(briefs.snapshot(conversation)));
        if(fields.has("assetIds")){
            JsonNode scope=fields.path("assetIds").path("value");fixed.set("assetIds",scope);
            database.update("INSERT INTO conversation_message_scopes(message_id,asset_ids_json) VALUES (?,?::jsonb) ON CONFLICT(message_id) DO UPDATE SET asset_ids_json=excluded.asset_ids_json",UUID.fromString(input.path("messageId").asText()),scope.toString());
            database.update("UPDATE conversations SET asset_ids_json=?::jsonb WHERE id=?",scope.toString(),conversation);
        }
        if(java.util.Set.of("GENERATE_SPEECH","GENERATE_MONTAGE").contains(request.path("intent").asText())){
            ObjectNode context=json.createObjectNode();context.put("briefRevision",((Number)briefs.snapshot(conversation).get("revision")).longValue());context.set("editing",json.createObjectNode());
            if("GENERATE_MONTAGE".equals(request.path("intent").asText()))((ObjectNode)context.path("editing")).put("kind","MONTAGE");
            if("ANSWER".equals(request.path("questionAction").asText())){
                JsonNode original=input.path("pendingQuestion").path("originalRequest").path("editing");
                ObjectNode target=(ObjectNode)context.path("editing");
                for(String identity:java.util.List.of("planId","baseRevisionId"))if(original.has(identity))target.set(identity,original.path(identity));
            }
            JsonNode applied=briefs.apply(conversation,context,input.path("assetIds"));
            fixed.set("assetIds",applied.path("assetIds"));
            // Persist the fixed request before entering editing; the surrounding transaction is atomic.
            database.update("INSERT INTO conversation_execution_requests(task_id,requirement_id,request_json,input_json) VALUES (?,?,?::jsonb,?::jsonb)",task.get("id"),requirement,request.toString(),fixed.toString());
            fixed.set("editing",plans.allocate((UUID)task.get("project_id"),(UUID)task.get("id"),applied.path("editing"),fixed.path("assetIds")));
            plans.bindAttempt((UUID)task.get("id"),(UUID)task.get("current_attempt_id"));
            database.update("UPDATE conversation_execution_requests SET input_json=?::jsonb WHERE task_id=?",fixed.toString(),task.get("id"));
        }else database.update("INSERT INTO conversation_execution_requests(task_id,requirement_id,request_json,input_json) VALUES (?,?,?::jsonb,?::jsonb)",task.get("id"),requirement,request.toString(),fixed.toString());
        return fixed;
    }
    private void defaults(ObjectNode changes,JsonNode current,String field,Object value){if(!changes.has(field)&&!current.has(field))changes.set(field,json.valueToTree(Map.of("value",value,"basis","DEFAULT")));}
    public JsonNode execution(UUID task,JsonNode accepted){
        var rows=database.queryForList("SELECT input_json FROM conversation_execution_requests WHERE task_id=?",task);
        return rows.isEmpty()?accepted:parse(rows.get(0).get("input_json"));
    }
    private JsonNode parse(Object value){try{return json.readTree(value.toString());}catch(java.io.IOException invalid){throw new IllegalStateException(invalid);}}
}
