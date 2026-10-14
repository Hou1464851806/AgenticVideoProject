package com.agenticvideo.business.conversation;

import com.agenticvideo.business.task.TaskController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Explicit public activities only: provider messages and tool arguments stay private. */
@Service
public class PublicActivities {
    private final JdbcTemplate database;
    private final ObjectMapper json;
    public PublicActivities(JdbcTemplate database, ObjectMapper json) { this.database=database; this.json=json; }

    public Object record(Map<String,Object> task, TaskController.PublicEvent request) {
        JsonNode payload=request.payload();
        String encoded=encode(Map.of("type",request.type(),"payload",payload));
        var previous=database.queryForList("SELECT request_json=?::jsonb AS same,receipt_json FROM public_event_reports WHERE task_id=? AND attempt_id=? AND producer_seq=?",encoded,task.get("id"),request.attemptId(),request.producerSeq());
        if(!previous.isEmpty()) {
            if(!Boolean.TRUE.equals(previous.get(0).get("same")))throw conflict("活动编号已用于不同内容");
            return parse(previous.get(0).get("receipt_json").toString());
        }
        var turn=database.queryForMap("SELECT id,conversation_id FROM conversation_turns WHERE task_id=?",task.get("id"));
        var publicPayload=json.createObjectNode();
        if("assistant.delta".equals(request.type())) {
            fields(payload,Set.of("messageId","baseContentVersion","offset","appendText"));
            if(!payload.path("appendText").isTextual() || payload.path("appendText").asText().isEmpty()
                    || length(payload.path("appendText").asText())>2048
                    || !payload.path("baseContentVersion").isIntegralNumber() || !payload.path("baseContentVersion").canConvertToInt()
                    || !payload.path("offset").isIntegralNumber() || !payload.path("offset").canConvertToInt())throw invalid();
            UUID message;
            try {message=UUID.fromString(payload.path("messageId").asText());}catch(IllegalArgumentException invalid){throw invalid();}
            database.update("INSERT INTO assistant_drafts(message_id,task_id,attempt_id) VALUES (?,?,?) ON CONFLICT(task_id,attempt_id) DO NOTHING",message,task.get("id"),request.attemptId());
            var draft=database.queryForMap("SELECT * FROM assistant_drafts WHERE task_id=? AND attempt_id=? FOR UPDATE",task.get("id"),request.attemptId());
            String content=draft.get("content").toString(),appended=payload.path("appendText").asText();
            int version=((Number)draft.get("content_version")).intValue();
            if(!message.equals(draft.get("message_id")) || version!=payload.path("baseContentVersion").asInt()
                    || length(content)!=payload.path("offset").asInt())throw conflict("草稿版本或文字位置不连续");
            if(length(content+appended)>8000)throw invalid();
            database.update("UPDATE assistant_drafts SET content=?,content_version=content_version+1 WHERE message_id=?",content+appended,message);
            publicPayload.setAll((com.fasterxml.jackson.databind.node.ObjectNode)payload);
            publicPayload.put("contentVersion",version+1);
        }else if("activity.updated".equals(request.type())) {
            fields(payload,Set.of("phase"));
            if(!Set.of("SEARCHING","READING_EVIDENCE","MODELING","SELF_CHECKING","VALIDATING").contains(payload.path("phase").asText()))throw invalid();
            publicPayload.put("phase",payload.path("phase").asText());
            database.update("UPDATE tasks SET public_phase=? WHERE id=?",payload.path("phase").asText(),task.get("id"));
        }else throw invalid();
        var details=Map.of("taskId",task.get("id"),"attemptId",request.attemptId(),"conversationId",turn.get("conversation_id"),"turnId",turn.get("id"),"payload",publicPayload);
        database.queryForObject("SELECT append_public_event(?,?,?::jsonb)",Object.class,task.get("project_id"),request.type(),encode(details));
        var receipt=Map.of("accepted",true,"producerSeq",request.producerSeq());
        database.update("INSERT INTO public_event_reports(task_id,attempt_id,producer_seq,request_json,receipt_json) VALUES (?,?,?,?::jsonb,?::jsonb)",task.get("id"),request.attemptId(),request.producerSeq(),encoded,encode(receipt));
        return receipt;
    }
    private int length(String value){return value.codePointCount(0,value.length());}
    private void fields(JsonNode payload,Set<String> allowed){
        if(!payload.isObject() || payload.size()!=allowed.size())throw invalid();
        payload.fieldNames().forEachRemaining(name->{if(!allowed.contains(name))throw invalid();});
    }
    private ResponseStatusException invalid(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"公开活动类型或字段无效");}
    private ResponseStatusException conflict(String message){return new ResponseStatusException(HttpStatus.CONFLICT,message);}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(java.io.IOException invalid){throw new IllegalStateException(invalid);}}
    private Object parse(String value){try{return json.readTree(value);}catch(java.io.IOException invalid){throw new IllegalStateException(invalid);}}
}
