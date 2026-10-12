package com.agenticvideo.business.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/** Conditions retain the user message that supplied them; corrections append revisions. */
@Service
public class ConversationBriefs {
    private final JdbcTemplate database;
    private final ObjectMapper json;
    public ConversationBriefs(JdbcTemplate database,ObjectMapper json){this.database=database;this.json=json;}
    public void validate(JsonNode input,JsonNode fields){
        if(!input.path("context").path("requirements").asBoolean(false))throw new ResponseStatusException(BAD_REQUEST,"本轮不是需求简报整理");
        if(!fields.isObject()||fields.isEmpty())throw new ResponseStatusException(BAD_REQUEST,"简报纠正须包含条件");
        fields.fields().forEachRemaining(entry->{
            String name=entry.getKey();JsonNode condition=entry.getValue(),value=condition.path("value");
            if(!Set.of("topic","count","duration","assetIds","forbiddenContent","forbiddenAssetIds","profile").contains(name)
                    || !condition.isObject() || !Set.of("EXPLICIT","DEFAULT").contains(condition.path("basis").asText()))
                throw new ResponseStatusException(BAD_REQUEST,"简报条件或来源类型无效");
            condition.fieldNames().forEachRemaining(key->{if(!Set.of("value","basis").contains(key))throw new ResponseStatusException(BAD_REQUEST,"简报来源由业务系统保存");});
            boolean valid=switch(name){
                case "topic" -> value.isTextual()&&!value.asText().isBlank()&&value.asText().length()<=120;
                case "count" -> value.isIntegralNumber()&&value.canConvertToInt()&&value.asInt()>=1&&value.asInt()<=10;
                case "duration" -> value.isObject()&&value.path("minMs").isIntegralNumber()&&value.path("maxMs").isIntegralNumber()&&value.path("minMs").canConvertToLong()&&value.path("maxMs").canConvertToLong()&&value.path("minMs").asLong()>0&&value.path("maxMs").asLong()>=value.path("minMs").asLong()&&value.path("maxMs").asLong()<=3600000;
                case "profile" -> Set.of("LANDSCAPE_720","PORTRAIT_720").contains(value.asText());
                default -> value.isArray()&&value.size()<=100;
            };
            if(!valid)throw new ResponseStatusException(BAD_REQUEST,"简报条件值不符合要求");
            if(name.equals("assetIds")||name.equals("forbiddenAssetIds")){
                var scope=new java.util.HashSet<String>();input.path("assetIds").forEach(asset->scope.add(asset.asText()));
                var seen=new java.util.HashSet<String>();
                for(var asset:value)if(!asset.isTextual()||!scope.contains(asset.asText())||!seen.add(asset.asText()))throw new ResponseStatusException(BAD_REQUEST,"简报素材必须属于本轮固定范围且不能重复");
            }
            if(name.equals("forbiddenContent"))for(var text:value)if(!text.isTextual()||text.asText().isBlank()||text.asText().length()>256)throw new ResponseStatusException(BAD_REQUEST,"禁用内容必须为明确文本");
        });
    }
    /** Called only in the current task's authoritative tool publication transaction. */
    public Object append(Map<String,Object> task,JsonNode input,JsonNode changes){
        validate(input,changes);
        UUID conversation=UUID.fromString(input.path("conversationId").asText());
        database.queryForMap("SELECT id FROM conversations WHERE id=? FOR UPDATE",conversation);
        var current=snapshot(conversation);ObjectNode fields=((JsonNode)current.get("fields")).deepCopy();
        changes.fields().forEachRemaining(entry->{
            ObjectNode condition=entry.getValue().deepCopy();condition.put("sourceMessageId",input.path("messageId").asText());fields.set(entry.getKey(),condition);
        });
        long revision=((Number)current.get("revision")).longValue()+1;
        database.update("INSERT INTO conversation_brief_revisions(id,conversation_id,source_task_id,source_message_id,revision_number,fields_json) VALUES (?,?,?,?,?,?::jsonb)",UUID.randomUUID(),conversation,task.get("id"),UUID.fromString(input.path("messageId").asText()),revision,fields.toString());
        return snapshot(conversation);
    }
    public JsonNode apply(UUID conversation,JsonNode context,JsonNode fixedScope){
        var current=snapshot(conversation);
        if(!context.path("briefRevision").isIntegralNumber()||context.path("briefRevision").asLong()!=((Number)current.get("revision")).longValue())
            throw new ResponseStatusException(CONFLICT,"简报已变化，请重新检查并应用最新版本");
        JsonNode fields=(JsonNode)current.get("fields");
        if(!context.path("editing").isObject()||!fields.has("topic")||!fields.has("duration"))throw new ResponseStatusException(BAD_REQUEST,"请先明确简报主题与硬时长");
        if(fields.path("count").path("value").asInt(1)!=1)throw new ResponseStatusException(BAD_REQUEST,"当前仅支持一次生成一份方案，请明确数量为一");
        ObjectNode effective=context.deepCopy(),editing=context.path("editing").deepCopy();
        editing.set("topic",fields.path("topic").path("value"));
        editing.set("minDurationMs",fields.path("duration").path("value").path("minMs"));
        editing.set("maxDurationMs",fields.path("duration").path("value").path("maxMs"));
        editing.put("profile",fields.path("profile").path("value").asText("LANDSCAPE_720"));
        editing.set("forbiddenContent",fields.has("forbiddenContent")?fields.path("forbiddenContent").path("value"):json.createArrayNode());
        var forbidden=new java.util.HashSet<String>();fields.path("forbiddenAssetIds").path("value").forEach(asset->forbidden.add(asset.asText()));
        var selected=json.createArrayNode();
        for(var asset:fields.has("assetIds")?fields.path("assetIds").path("value"):fixedScope)if(!forbidden.contains(asset.asText()))selected.add(asset);
        effective.set("assetIds",selected);effective.set("editing",editing);return effective;
    }
    public void start(UUID conversation,UUID task,UUID message){
        long revision=database.queryForObject("SELECT coalesce(max(revision_number),0)+1 FROM conversation_brief_revisions WHERE conversation_id=?",Long.class,conversation);
        database.update("INSERT INTO conversation_brief_revisions(id,conversation_id,source_task_id,source_message_id,revision_number,fields_json) VALUES (?,?,?,?,?,'{}'::jsonb)",UUID.randomUUID(),conversation,task,message,revision);
    }
    public Map<String,Object> snapshot(UUID conversation){
        List<Map<String,Object>> history=database.query("SELECT revision_number,fields_json,source_message_id FROM (SELECT * FROM conversation_brief_revisions WHERE conversation_id=? ORDER BY revision_number DESC LIMIT 20) r ORDER BY revision_number",(row,n)->Map.of("revision",row.getLong("revision_number"),"fields",decode(row.getString("fields_json")),"sourceMessageId",row.getObject("source_message_id",UUID.class)),conversation);
        Map<String,Object> latest=history.isEmpty()?Map.of("revision",0L,"fields",json.createObjectNode()):history.get(history.size()-1);
        return Map.of("revision",latest.get("revision"),"fields",latest.get("fields"),"history",history);
    }
    private JsonNode decode(String value){try{return json.readTree(value);}catch(java.io.IOException invalid){throw new IllegalStateException(invalid);}}
}
