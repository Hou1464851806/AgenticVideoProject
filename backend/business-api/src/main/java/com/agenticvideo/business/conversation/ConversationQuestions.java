package com.agenticvideo.business.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@Service
public class ConversationQuestions {
    private final JdbcTemplate database;
    private final ObjectMapper json;
    private final ConversationBriefs briefs;
    private final ConversationReplies replies;
    private final ConversationTurns turns;
    private final com.agenticvideo.business.plan.EditPlans plans;
    private final TransactionTemplate transactions;
    public ConversationQuestions(ConversationReplies replies,ConversationBriefs briefs,JdbcTemplate database,ObjectMapper json,ConversationTurns turns,com.agenticvideo.business.plan.EditPlans plans,PlatformTransactionManager manager){this.replies=replies;this.briefs=briefs;this.database=database;this.json=json;this.turns=turns;this.plans=plans;this.transactions=new TransactionTemplate(manager);}
    /** Runs inside the task completion transaction, after current attempt validation. */
    public Object publish(Map<String,Object> task,JsonNode input,JsonNode question){
        String prompt=question.path("prompt").asText(),reason=question.path("reason").asText();JsonNode fields=question.path("fields");
        if(prompt.isBlank()||prompt.length()>2000||reason.isBlank()||reason.length()>500||!fields.isArray()||fields.isEmpty()||fields.size()>9)throw new ResponseStatusException(BAD_REQUEST,"澄清问题必须明确缺失或冲突条件");
        for(var field:fields)if(!java.util.Set.of("topic","count","duration","assetIds","forbiddenContent","forbiddenAssetIds","profile","orderedAssetIds","target").contains(field.asText()))throw new ResponseStatusException(BAD_REQUEST,"澄清条件无效");
        UUID conversation=UUID.fromString(input.path("conversationId").asText()),id=UUID.randomUUID();
        database.queryForMap("SELECT id FROM conversations WHERE id=? FOR UPDATE",conversation);
        if(input.path("context").path("naturalLanguage").asBoolean(false))briefs.defer(conversation,(UUID)task.get("id"),UUID.fromString(input.path("messageId").asText()),fields);
        database.update("INSERT INTO conversation_questions(id,conversation_id,task_id,turn_id,prompt,reason,fields_json) VALUES (?,?,?,?,?,?,?::jsonb)",id,conversation,task.get("id"),UUID.fromString(input.path("turnId").asText()),prompt,reason,fields.toString());
        return Map.of("questionId",id,"taskId",task.get("id"),"turnId",input.path("turnId").asText(),"status","WAITING_INPUT");
    }
    public Object list(UUID conversation){return database.query("SELECT q.id,q.task_id,q.turn_id,q.prompt,q.reason,q.fields_json,q.status,q.answer_message_id,t.input_json->'editing' AS editing_json FROM conversation_questions q JOIN tasks t ON t.id=q.task_id WHERE q.conversation_id=? ORDER BY q.created_at DESC,q.id DESC LIMIT 20",(row,n)->{var item=new java.util.LinkedHashMap<String,Object>();item.put("id",row.getObject("id",UUID.class));item.put("taskId",row.getObject("task_id",UUID.class));item.put("turnId",row.getObject("turn_id",UUID.class));item.put("prompt",row.getString("prompt"));item.put("reason",row.getString("reason"));item.put("fields",decode(row.getString("fields_json")));item.put("status",row.getString("status"));item.put("editing",row.getString("editing_json")==null?null:decode(row.getString("editing_json")));item.put("answerMessageId",row.getObject("answer_message_id",UUID.class));return item;},conversation);}
    public Object answer(UUID conversation,UUID question,String key,ConversationController.QuestionAnswer request){
        if(key.isBlank()||key.length()>120)throw new ResponseStatusException(BAD_REQUEST,"回答幂等键不符合要求");
        return transactions.execute(tx->{
            var waiting=replies.lock(conversation,question);
            var source=waiting.source();var session=waiting.session();var original=waiting.question();String payload=encode(request);
            if(original.get("answer_key")!=null){
                boolean same=Boolean.TRUE.equals(database.queryForObject("SELECT answer_json=?::jsonb AND answer_key=? FROM conversation_questions WHERE id=?",Boolean.class,payload,key,question));
                if(same)return decode(original.get("answer_receipt").toString());
                throw new ResponseStatusException(CONFLICT,"该问题已被回答，不能作为另一轮的回答");
            }
            replies.requireOpen(waiting);
            if(database.queryForObject("SELECT count(*) FROM conversation_turns WHERE conversation_id=? AND request_key=?",Integer.class,conversation,key)>0)throw new ResponseStatusException(CONFLICT,"回答幂等键已用于其他需求");
            ObjectNode input=(ObjectNode)turns.executionInput((UUID)source.get("id"),decode(source.get("input_json").toString())).deepCopy();turns.validateRetry(session,input);
            UUID turn=UUID.randomUUID(),message=UUID.randomUUID(),task=UUID.randomUUID(),event=UUID.randomUUID();
            long sequence=database.queryForObject("SELECT coalesce(max(message_seq),0)+1 FROM conversation_messages WHERE conversation_id=?",Long.class,conversation);
            database.update("INSERT INTO conversation_messages(id,conversation_id,message_seq,role,content,references_json) VALUES (?,?,?,'USER',?,?::jsonb)",message,conversation,sequence,request.content(),encode(List.of(Map.of("type","CLARIFICATION","id",question,"turnId",original.get("turn_id")))));
            input.put("turnId",turn.toString());input.put("messageId",message.toString());input.put("content",input.path("content").asText()+"\n关联澄清回答："+request.content());
            input.set("brief",json.valueToTree(briefs.snapshot(conversation)));
            input.set("clarification",json.valueToTree(Map.of("questionId",question,"originalTurnId",original.get("turn_id"),"resumesTaskId",source.get("id"),"answer",request.content())));
            input.set("history",json.valueToTree(database.queryForList("SELECT role,content FROM (SELECT role,content,message_seq FROM conversation_messages WHERE conversation_id=? ORDER BY message_seq DESC LIMIT 12) h ORDER BY message_seq",conversation)));
            if(input.has("editing")){
                JsonNode editing=request.editing()==null?input.path("editing"):request.editing();
                input.set("editing",plans.allocate((UUID)session.get("project_id"),task,editing,input.path("assetIds")));
            }else if(request.editing()!=null)throw new ResponseStatusException(BAD_REQUEST,"原需求没有方案基准，不能在回答中覆盖制作任务类型");
            database.update("INSERT INTO tasks(id,project_id,type,input_json) VALUES (?,?,'AGENT_TURN',?::jsonb)",task,session.get("project_id"),input.toString());
            database.update("INSERT INTO conversation_turns(id,conversation_id,message_id,task_id,request_key,request_json,resumes_task_id) VALUES (?,?,?,?,?,?::jsonb,?)",turn,conversation,message,task,key,payload,source.get("id"));
            database.update("INSERT INTO outbox_events(id,task_id,type,payload_json) VALUES (?,?,'AGENT_TURN',jsonb_build_object('schemaVersion','1.0','eventId',?::text,'taskId',?::text,'taskType','AGENT_TURN','projectId',?::text,'occurredAt',now()))",event,task,event,task,session.get("project_id"));
            var receipt=new java.util.LinkedHashMap<String,Object>();receipt.put("taskId",task);receipt.put("turnId",turn);receipt.put("messageId",message);receipt.put("resumesTaskId",source.get("id"));receipt.put("questionId",question);if(input.has("editing"))receipt.put("planId",input.path("editing").path("planId").asText());
            database.update("UPDATE conversation_questions SET answer_key=?,answer_json=?::jsonb,answer_receipt=?::jsonb WHERE id=?",key,payload,encode(receipt),question);
            replies.answered(waiting,task,message);
            return receipt;
        });
    }
    public Object startNew(UUID conversation,UUID question,String key,ConversationController.Turn request){
        return transactions.execute(tx->{
            var waiting=replies.lock(conversation,question);
            if(database.queryForObject("SELECT count(*) FROM conversation_turns WHERE conversation_id=? AND request_key=?",Integer.class,conversation,key)>0)return turns.accept(conversation,key,request,true);
            replies.requireOpen(waiting);
            replies.end(waiting,"EXPLICIT_NEW_REQUIREMENT");
            return turns.accept(conversation,key,request,true);
        });
    }
    /** The caller holds the task lock; take conversation then question locks in the same order as answer. */
    public void dismiss(UUID task){replies.dismiss(task);}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(java.io.IOException invalid){throw new IllegalStateException(invalid);}}
    private JsonNode decode(String value){try{return json.readTree(value);}catch(java.io.IOException invalid){throw new IllegalStateException(invalid);}}
}
