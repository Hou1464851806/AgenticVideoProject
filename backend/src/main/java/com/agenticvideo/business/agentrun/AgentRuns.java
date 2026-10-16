package com.agenticvideo.business.agentrun;

import com.agenticvideo.business.conversation.ConversationController;
import com.agenticvideo.business.project.ProjectAccess;
import com.agenticvideo.business.tools.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static com.agenticvideo.business.agentrun.AgentRunContracts.*;
import static org.springframework.http.HttpStatus.*;

/** Owns fixed message admission and the trusted execution binding after Python understanding. */
@Service
public class AgentRuns {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final ProjectAccess access;
    private final AgentRunLocks locks;
    private final QuestionScopes scopes;
    public AgentRuns(JdbcTemplate db,ObjectMapper json,ProjectAccess access,AgentRunLocks locks,QuestionScopes scopes) {
        this.db=db;this.json=json;this.access=access;this.locks=locks;this.scopes=scopes;
    }
    public UUID requireOwned(UUID conversation,Authentication account) {
        var rows=db.queryForList("SELECT c.project_id FROM conversations c JOIN projects p ON p.id=c.project_id WHERE c.id=? AND p.owner_user_id=?",conversation,access.currentUserId(account));
        if(rows.isEmpty())throw new ResponseStatusException(NOT_FOUND,"制作会话不存在或无权访问");
        return (UUID)rows.get(0).get("project_id");
    }
    @Transactional public Object accept(UUID conversation,String key,ConversationController.Turn request) {
        key(key);
        if(request.context()!=null&&!request.context().isEmpty())throw new ResponseStatusException(BAD_REQUEST,"新版对话通过原文和引用表达需求");
        var session=locks.conversation(conversation);var payload=json.valueToTree(request);
        var old=db.queryForList("SELECT *,request_json=?::jsonb AS same FROM conversation_turns WHERE conversation_id=? AND request_key=?",payload.toString(),conversation,key);
        if(!old.isEmpty()) {
            if(!Boolean.TRUE.equals(old.get(0).get("same")))throw conflict("同一发送键不能改变消息");
            return receipt(old.get(0));
        }
        if(busy(conversation))throw conflict("会话正在处理消息，请完成或停止后继续");
        List<Map<String,JsonNode>> refs=request.references()==null?List.of():request.references().stream().map(r->{
            if(!r.isObject())throw new ResponseStatusException(BAD_REQUEST,"引用必须是对象");
            @SuppressWarnings("unchecked") Map<String,JsonNode> value=json.convertValue(r,new com.fasterxml.jackson.core.type.TypeReference<Map<String,JsonNode>>() {});return value;
        }).toList();
        validateReferences(session,refs);
        UUID task=UUID.randomUUID(),message=UUID.randomUUID(),turn=UUID.randomUUID();
        var identity=new Identity(session.get("project_id").toString(),conversation.toString(),message.toString(),turn.toString(),task.toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString());
        var history=db.query("SELECT * FROM (SELECT id,role,content,references_json,message_seq FROM conversation_messages WHERE conversation_id=? ORDER BY message_seq DESC LIMIT 12) h ORDER BY message_seq",(r,n)->{
            @SuppressWarnings("unchecked") List<Map<String,JsonNode>> references=json.convertValue(parse(r.getString("references_json")),new com.fasterxml.jackson.core.type.TypeReference<List<Map<String,JsonNode>>>() {});
            return new HistoryMessage(r.getString("id"),Role.valueOf(r.getString("role")),r.getString("content"),references);
        },conversation);
        var fixed=new RunInput("1.0",identity,request.content(),history,refs,currentProduction(conversation),pending(conversation));
        var input=json.valueToTree(fixed);read(input,RunInput.class);
        append(message,conversation,"USER",request.content(),json.valueToTree(refs),null);
        db.update("INSERT INTO tasks(id,project_id,type,input_json) VALUES (?,?,'AGENT_TURN',?::jsonb)",task,session.get("project_id"),input.toString());
        db.update("INSERT INTO conversation_turns(id,conversation_id,message_id,task_id,request_key,request_json) VALUES (?,?,?,?,?,?::jsonb)",turn,conversation,message,task,key,payload.toString());
        db.update("""
                INSERT INTO conversation_execution_requests(task_id,request_json,input_json,schema_version,project_id,conversation_id,
                    turn_id,message_id,input_hash,thread_id,execution_id)
                VALUES (?,'{}',?::jsonb,'1.0',?,?,?,?,?,?,?)
                """,task,input.toString(),session.get("project_id"),conversation,turn,message,ToolJson.hash(input),UUID.fromString(identity.threadId()),UUID.fromString(identity.executionId()));
        UUID event=UUID.randomUUID();
        db.update("INSERT INTO outbox_events(id,task_id,type,payload_json) VALUES (?,?,'AGENT_TURN',jsonb_build_object('schemaVersion','1.0','eventId',?::text,'taskId',?::text,'taskType','AGENT_TURN','projectId',?::text,'occurredAt',now()))",event,task,event,task,session.get("project_id"));
        return Map.of("turnId",turn,"messageId",message,"taskId",task);
    }
    private void validateReferences(Map<String,Object> session,List<Map<String,JsonNode>> refs) {
        var allowed=new HashSet<String>();parse(session.get("asset_ids_json")).forEach(a->allowed.add(a.asText()));
        for(var ref:refs) {
            String type=ref.getOrDefault("type",json.nullNode()).asText();UUID id;
            try{id=UUID.fromString(ref.getOrDefault("id",json.nullNode()).asText());}catch(IllegalArgumentException invalid){throw new ResponseStatusException(BAD_REQUEST,"引用身份无效");}
            boolean valid=switch(type) {
                case "ASSET" -> allowed.contains(id.toString())&&db.queryForObject("SELECT count(*) FROM assets WHERE id=? AND project_id=?",Integer.class,id,session.get("project_id"))==1;
                case "EVIDENCE" -> db.queryForObject("SELECT count(*) FROM evidence_segments WHERE id=? AND project_id=? AND asset_id IN (SELECT value::uuid FROM jsonb_array_elements_text(?::jsonb))",Integer.class,id,session.get("project_id"),session.get("asset_ids_json").toString())==1;
                case "EDIT_DRAFT" -> db.queryForObject("SELECT count(*) FROM conversation_edit_drafts WHERE message_id=? AND conversation_id=?",Integer.class,id,session.get("id"))==1;
                case "PLAN_REVISION" -> db.queryForObject("SELECT count(*) FROM plan_revisions WHERE id=? AND project_id=?",Integer.class,id,session.get("project_id"))==1;
                case "QUESTION" -> db.queryForObject("SELECT count(*) FROM conversation_questions WHERE id=? AND conversation_id=? AND status='OPEN'",Integer.class,id,session.get("id"))==1;
                default -> throw new ResponseStatusException(BAD_REQUEST,"引用类型不受支持");
            };
            if(!valid)throw new ResponseStatusException(NOT_FOUND,"引用不存在或超出会话范围");
        }
    }
    @Transactional public Object bind(UUID task,UUID attempt,String token,JsonNode body) {
        var parsed=read(body,ParsedInput.class);var run=locked(task);requireAttempt(run,attempt,token,run.get("parsed_json")==null);
        if(run.get("parsed_json")!=null) {
            if(!parse(run.get("parsed_json")).equals(body))throw conflict("已持久理解不能改写");
            return parse(run.get("request_json"));
        }
        requireRunning(run);
        var fixed=read(parse(run.get("input_json")),RunInput.class);
        UUID production=null,scope=null,thread=(UUID)run.get("thread_id"),execution=(UUID)run.get("execution_id");Long generation=null;
        Map<String,Object> target=null;PendingQuestion question=null;
        if(parsed.relation()==Relation.ANSWER_QUESTION) {
            question=fixed.pendingQuestions().stream().filter(q->q.questionId().equals(parsed.questionId())).findFirst().orElseThrow(()->conflict("补答问题不在固定输入中"));
            var waiting=db.queryForMap("SELECT * FROM tasks WHERE id=? FOR UPDATE",UUID.fromString(question.taskId()));
            var q=db.queryForMap("SELECT * FROM conversation_questions WHERE id=? FOR UPDATE",UUID.fromString(question.questionId()));
            if(!"OPEN".equals(q.get("status"))||!"WAITING_INPUT".equals(waiting.get("status"))||!question.waitId().equals(q.get("wait_id")))throw conflict("原问题已经失效");
            thread=UUID.fromString(question.threadId());execution=UUID.fromString(question.executionId());
            if(question.productionId()!=null) {
                if(parsed.mode()!=Mode.DRAFT)throw conflict("制作补答须恢复原制作");
                production=UUID.fromString(question.productionId());target=currentTarget(fixed,production,question.editGeneration());generation=question.editGeneration();
            } else if(parsed.mode()==Mode.DRAFT)throw conflict("独立问题不能隐式分配制作");
            scope=db.queryForObject("SELECT scope_id FROM conversation_execution_requests WHERE task_id=?",UUID.class,UUID.fromString(question.taskId()));
        } else if(parsed.mode()==Mode.DRAFT) {
            if(parsed.relation()==Relation.NEW_PRODUCTION) {
                var session=db.queryForMap("SELECT * FROM conversations WHERE id=?",run.get("conversation_id"));
                scope=createScope(session,fixed.references());production=UUID.randomUUID();thread=UUID.randomUUID();generation=1L;
                var conditions=ProductionConditions.defaults(json,scopes.get(scope));
                db.update("INSERT INTO edit_productions(id,project_id,scope_id,conversation_id,thread_id,edit_generation,requirements_json,requirements_sources_json) VALUES (?,?,?,?,?,?,?::jsonb,?::jsonb)",production,run.get("project_id"),scope,run.get("conversation_id"),thread,generation,conditions.requirements(),conditions.sources());
                db.update("UPDATE conversations SET current_production_id=? WHERE id=?",production,run.get("conversation_id"));
                // Waiting executions relinquish their thread when the user explicitly starts another production.
                db.queryForList("SELECT t.id FROM tasks t JOIN conversation_questions q ON q.task_id=t.id WHERE q.conversation_id=? AND q.status='OPEN' ORDER BY t.id FOR UPDATE OF t",run.get("conversation_id"));
                db.queryForList("SELECT id FROM conversation_questions WHERE conversation_id=? AND status='OPEN' ORDER BY id FOR UPDATE",run.get("conversation_id"));
                db.update("UPDATE tasks SET status='CANCELLED' WHERE id IN (SELECT task_id FROM conversation_questions WHERE conversation_id=? AND status='OPEN')",run.get("conversation_id"));
                db.update("UPDATE conversation_questions SET status='CANCELLED' WHERE conversation_id=? AND status='OPEN'",run.get("conversation_id"));
                target=db.queryForMap("SELECT * FROM edit_productions WHERE id=?",production);
            } else if(parsed.relation()==Relation.CONTINUE_PRODUCTION) {
                if(fixed.currentProduction()==null)throw conflict("没有当前制作可修改");
                production=UUID.fromString(fixed.currentProduction().productionId());target=currentTarget(fixed,production,fixed.currentProduction().editGeneration());
                if(db.queryForObject("SELECT count(*) FROM conversation_questions WHERE production_id=? AND status='OPEN'",Integer.class,production)>0)throw conflict("请明确回答原问题或开始新制作");
                generation=((Number)target.get("edit_generation")).longValue()+1;thread=(UUID)target.get("thread_id");scope=(UUID)target.get("scope_id");
                db.update("UPDATE edit_productions SET edit_generation=? WHERE id=?",generation,production);
            } else if(parsed.relation()!=Relation.CLARIFY)throw conflict("制作意图必须明确关联或另起制作");
        }
        if(scope==null)scope=createScope(db.queryForMap("SELECT * FROM conversations WHERE id=?",run.get("conversation_id")),fixed.references());
        if(parsed.mode()==Mode.DRAFT&&parsed.relation()==Relation.CLARIFY&&!parsed.conditionsPatch().isEmpty())throw conflict("对象含糊时不能修改制作条件");
        if(target!=null) {
            var conditions=ProductionConditions.merge(json,parse(target.get("requirements_json")),parse(target.get("requirements_sources_json")),parsed.conditionsPatch(),fixed.identity().messageId(),fixed.content());
            var allowed=new HashSet<String>();scopes.get(scope).assets().forEach(a->allowed.add(a.path("id").asText()));
            for(var asset:parse(conditions.requirements()).path("asset_ids"))if(!allowed.contains(asset.asText()))throw conflict("制作条件超出固定素材范围");
            db.update("UPDATE edit_productions SET requirements_json=?::jsonb,requirements_sources_json=?::jsonb WHERE id=?",conditions.requirements(),conditions.sources(),production);
        }
        db.update("UPDATE conversation_execution_requests SET parsed_json=?::jsonb,production_id=?,scope_id=?,thread_id=?,execution_id=?,edit_generation=? WHERE task_id=?",body.toString(),production,scope,thread,execution,generation,task);
        if(question!=null||parsed.relation()==Relation.CONTINUE_PRODUCTION) {
            // The resumed checkpoint retains observations; carry only receipts from its trusted lineage.
            if(question!=null)db.update("INSERT INTO agent_run_tool_calls(task_id,scope_id,call_id) SELECT ?,scope_id,call_id FROM agent_run_tool_calls WHERE task_id=? ON CONFLICT DO NOTHING",task,UUID.fromString(question.taskId()));
            else db.update("INSERT INTO agent_run_tool_calls(task_id,scope_id,call_id) SELECT DISTINCT ?,c.scope_id,c.call_id FROM agent_run_tool_calls c JOIN conversation_execution_requests r ON r.task_id=c.task_id WHERE r.production_id=? AND r.thread_id=? AND r.scope_id=? ON CONFLICT DO NOTHING",task,production,thread,scope);
        }
        if(question!=null) {
            db.update("UPDATE conversation_turns SET resumes_task_id=? WHERE task_id=?",UUID.fromString(question.taskId()),task);
            db.update("UPDATE conversation_questions SET status='ANSWERED',answer_message_id=?,successor_task_id=? WHERE id=?",run.get("message_id"),task,UUID.fromString(question.questionId()));
            db.update("UPDATE tasks SET status='SUCCEEDED',result_json=jsonb_build_object('status','HANDED_OFF','successorTaskId',?::text) WHERE id=?",task,UUID.fromString(question.taskId()));
        }
        var binding=binding(db.queryForMap("SELECT * FROM conversation_execution_requests WHERE task_id=?",task));
        db.update("UPDATE conversation_execution_requests SET request_json=?::jsonb WHERE task_id=?",json.valueToTree(binding).toString(),task);
        return binding;
    }
    private Map<String,Object> currentTarget(RunInput fixed,UUID production,Long generation) {
        var row=db.queryForMap("SELECT p.*,c.current_production_id FROM edit_productions p JOIN conversations c ON c.id=p.conversation_id WHERE p.id=?",production);
        if(!production.equals(row.get("current_production_id"))||!generation.equals(((Number)row.get("edit_generation")).longValue())||frozen(production))throw conflict("当前制作已变化或封存");
        if(fixed.currentProduction()==null||!production.toString().equals(fixed.currentProduction().productionId()))throw conflict("制作不在固定输入中");
        return row;
    }
    private UUID createScope(Map<String,Object> session,List<Map<String,JsonNode>> references) {
        // A question about a particular draft keeps that draft's evidence snapshot.
        for(var ref:references)if("EDIT_DRAFT".equals(ref.get("type").asText()))return db.queryForObject("SELECT p.scope_id FROM conversation_edit_drafts d JOIN edit_productions p ON p.id=d.production_id WHERE d.message_id=?",UUID.class,UUID.fromString(ref.get("id").asText()));
        var selected=new ArrayList<UUID>();for(var ref:references)if("ASSET".equals(ref.get("type").asText()))selected.add(UUID.fromString(ref.get("id").asText()));
        if(selected.isEmpty())parse(session.get("asset_ids_json")).forEach(a->selected.add(UUID.fromString(a.asText())));
        if(selected.isEmpty()) {
            UUID empty=UUID.randomUUID();db.update("INSERT INTO question_scopes(id,project_id,assets_json) VALUES (?,?,'[]')",empty,session.get("project_id"));return empty;
        }
        return scopes.create((UUID)session.get("project_id"),selected).id();
    }
    public Map<String,Object> locked(UUID task) {
        locks.beforeTask(task);
        var rows=db.queryForList("SELECT r.*,t.status,t.current_attempt_id FROM conversation_execution_requests r JOIN tasks t ON t.id=r.task_id WHERE r.task_id=? AND r.schema_version='1.0' FOR UPDATE OF t",task);
        if(rows.isEmpty())throw new ResponseStatusException(NOT_FOUND,"Agent 运行不存在");return rows.get(0);
    }
    public void requireAttempt(Map<String,Object> run,UUID attempt,String token,boolean live) {
        var rows=db.queryForList("SELECT *,lease_expires_at>clock_timestamp() AS live FROM task_attempts WHERE id=? AND task_id=? FOR UPDATE",attempt,run.get("task_id"));
        if(rows.isEmpty()||!attempt.equals(run.get("current_attempt_id")))throw conflict("执行尝试已失效");
        var row=rows.get(0);
        if(!java.security.MessageDigest.isEqual(tokenHash(token).getBytes(java.nio.charset.StandardCharsets.US_ASCII),row.get("lease_token_hash").toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII))||live&&!Boolean.TRUE.equals(row.get("live")))throw conflict("执行凭据或租约已失效");
    }
    private String tokenHash(String token){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException invalid){throw new IllegalStateException(invalid);}}
    public void requireRunning(Map<String,Object> run){if(!"RUNNING".equals(run.get("status")))throw conflict("运行已失去发布资格");}
    public void requireCurrent(Map<String,Object> run) {
        if(run.get("production_id")==null)return;
        UUID production=(UUID)run.get("production_id");
        if(!Boolean.TRUE.equals(db.queryForObject("SELECT c.current_production_id=p.id AND p.edit_generation=? FROM edit_productions p JOIN conversations c ON c.id=p.conversation_id WHERE p.id=?",Boolean.class,run.get("edit_generation"),production))||frozen(production))throw conflict("制作或编辑代次已失效");
    }
    public Object binding(Map<String,Object> run) {
        var result=new LinkedHashMap<String,Object>();result.put("identity",identity(run));result.put("inputHash",run.get("input_hash"));result.put("parsed",parse(run.get("parsed_json")));
        var scope=scopes.get((UUID)run.get("scope_id"));scopes.requireDependencies(scope);result.put("scope",Map.of("scope_id",scope.id(),"project_id",scope.projectId(),"assets",scope.assets(),"mock",false));
        result.put("production",run.get("production_id")==null?null:production((UUID)run.get("production_id")));
        result.put("editGeneration",run.get("edit_generation"));
        result.put("productionBinding",run.get("production_id")==null?null:Map.of("requirement_id",run.get("production_id"),"project_id",run.get("project_id"),"scope_id",run.get("scope_id")));
        var parsed=read(parse(run.get("parsed_json")),ParsedInput.class);
        var input=read(parse(run.get("input_json")),RunInput.class);
        result.put("question",parsed.questionId()==null?null:input.pendingQuestions().stream().filter(q->q.questionId().equals(parsed.questionId())).findFirst().orElseThrow());return result;
    }
    public Identity identity(Map<String,Object> r){return new Identity(r.get("project_id").toString(),r.get("conversation_id").toString(),r.get("message_id").toString(),r.get("turn_id").toString(),r.get("task_id").toString(),r.get("thread_id").toString(),r.get("execution_id").toString());}
    public Production currentProduction(UUID conversation) {
        UUID id=db.queryForObject("SELECT current_production_id FROM conversations WHERE id=?",UUID.class,conversation);return id==null?null:production(id);
    }
    private Production production(UUID id) {
        var row=db.queryForMap("SELECT * FROM edit_productions WHERE id=?",id);DraftBinding draft=null;
        if(row.get("current_draft_message_id")!=null) {
            var d=db.queryForMap("SELECT * FROM conversation_edit_drafts WHERE message_id=?",row.get("current_draft_message_id"));
            draft=new DraftBinding(d.get("message_id").toString(),d.get("validation_id").toString(),((Number)d.get("edit_generation")).longValue(),d.get("draft_hash").toString(),d.get("requirements_hash").toString());
        }
        @SuppressWarnings("unchecked") Map<String,JsonNode> requirements=json.convertValue(parse(row.get("requirements_json")),new com.fasterxml.jackson.core.type.TypeReference<Map<String,JsonNode>>() {});
        @SuppressWarnings("unchecked") Map<String,Map<String,JsonNode>> sources=json.convertValue(parse(row.get("requirements_sources_json")),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Map<String,JsonNode>>>() {});
        return new Production(id.toString(),row.get("scope_id").toString(),row.get("thread_id").toString(),((Number)row.get("edit_generation")).longValue(),requirements,sources,draft,frozen(id));
    }
    private List<PendingQuestion> pending(UUID conversation){return db.query("SELECT * FROM conversation_questions WHERE conversation_id=? AND status='OPEN' AND execution_id IS NOT NULL ORDER BY created_at LIMIT 100",(r,n)->new PendingQuestion(r.getString("id"),r.getString("task_id"),r.getString("production_id"),r.getString("thread_id"),r.getString("execution_id"),r.getString("wait_id"),r.getObject("edit_generation",Long.class),r.getString("prompt")),conversation);}
    public Object snapshot(UUID conversation) {
        var result=new LinkedHashMap<String,Object>();var current=currentProduction(conversation);result.put("currentProduction",current);
        boolean pending=hasPendingMutation(conversation);
        boolean eligible=current!=null&&!current.frozen()&&current.currentDraft()!=null&&current.editGeneration().equals(current.currentDraft().editGeneration())&&!pending;
        result.put("canAcceptDraft",eligible);result.put("canPreviewDraft",eligible);
        result.put("currentDraft",current==null||current.currentDraft()==null?null:db.queryForMap("SELECT m.id,m.content,v.normalized_content_json AS content_json FROM conversation_messages m JOIN conversation_edit_drafts d ON d.message_id=m.id JOIN edit_draft_validations v ON v.id=d.validation_id WHERE m.id=?",UUID.fromString(current.currentDraft().messageId())));
        if(result.get("currentDraft")!=null) {
            @SuppressWarnings("unchecked") var draft=(Map<String,Object>)result.get("currentDraft");draft.put("normalizedContent",parse(draft.remove("content_json")));
        }
        result.put("pendingQuestions",pending(conversation));return result;
    }
    public boolean hasPendingMutation(UUID conversation){return db.queryForObject("SELECT EXISTS(SELECT 1 FROM conversation_execution_requests r JOIN tasks t ON t.id=r.task_id WHERE r.conversation_id=? AND t.status IN ('PENDING','RUNNING','CANCEL_REQUESTED') AND (r.parsed_json IS NULL OR r.production_id IS NOT NULL))",Boolean.class,conversation);}
    public boolean frozen(UUID production){return db.queryForObject("SELECT EXISTS(SELECT 1 FROM plan_revisions WHERE source_production_id=?)",Boolean.class,production);}
    public boolean busy(UUID conversation){return db.queryForObject("SELECT EXISTS(SELECT 1 FROM tasks t JOIN conversation_turns r ON r.task_id=t.id WHERE r.conversation_id=? AND t.status IN ('PENDING','RUNNING','CANCEL_REQUESTED'))",Boolean.class,conversation);}
    public void append(UUID message,UUID conversation,String role,String content,JsonNode refs,UUID task){db.update("INSERT INTO conversation_messages(id,conversation_id,message_seq,role,content,references_json,source_task_id) SELECT ?,?,coalesce(max(message_seq),0)+1,?,?,?::jsonb,? FROM conversation_messages WHERE conversation_id=?",message,conversation,role,content,refs.toString(),task,conversation);}
    public JsonNode parse(Object value){try{return json.readTree(value.toString());}catch(java.io.IOException invalid){throw new IllegalStateException(invalid);}}
    private Object receipt(Map<String,Object> row){return Map.of("turnId",row.get("id"),"messageId",row.get("message_id"),"taskId",row.get("task_id"));}
    public static void key(String key){if(key.isBlank()||key.length()>120)throw new ResponseStatusException(BAD_REQUEST,"幂等键必须为 1 至 120 字符");}
    public static ResponseStatusException conflict(String message){return new ResponseStatusException(CONFLICT,message);}
}
