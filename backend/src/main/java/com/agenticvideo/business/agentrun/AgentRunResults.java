package com.agenticvideo.business.agentrun;

import com.agenticvideo.business.task.*;
import com.agenticvideo.business.tools.QuestionScopes;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.agenticvideo.business.agentrun.AgentRunContracts.*;
import static com.agenticvideo.business.agentrun.AgentRuns.conflict;

/** Publishes only projections backed by this execution's scope receipts or validation. */
@Service
public class AgentRunResults {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final AgentRuns runs;
    private final TaskExecution tasks;
    private final QuestionScopes scopes;
    public AgentRunResults(JdbcTemplate db,ObjectMapper json,AgentRuns runs,TaskExecution tasks,QuestionScopes scopes){this.db=db;this.json=json;this.runs=runs;this.tasks=tasks;this.scopes=scopes;}
    public void requireRun(UUID task){if(db.queryForObject("SELECT count(*) FROM conversation_execution_requests WHERE task_id=? AND schema_version='1.0'",Integer.class,task)!=1)throw conflict("不是新版 Agent 运行");}
    @Transactional public Object claim(UUID task,TaskController.Claim request) {
        requireRun(task);var run=runs.locked(task);
        var claimed=json.valueToTree(tasks.claim(task,request));
        ((com.fasterxml.jackson.databind.node.ObjectNode)claimed).put("inputHash",run.get("input_hash").toString());
        if(claimed.path("claimed").asBoolean()) {
            ((com.fasterxml.jackson.databind.node.ObjectNode)claimed).set("input",runs.parse(run.get("input_json")));
            ((com.fasterxml.jackson.databind.node.ObjectNode)claimed).set("binding",run.get("parsed_json")==null?json.nullNode():runs.parse(run.get("request_json")));
            if(run.get("core_task_id")!=null) {
                var core=db.queryForMap("SELECT input_json,request_json,parsed_json FROM conversation_execution_requests WHERE task_id=?",run.get("core_task_id"));
                var execution=json.createObjectNode();execution.set("input",runs.parse(core.get("input_json")));
                execution.set("binding",core.get("parsed_json")==null?json.nullNode():runs.parse(core.get("request_json")));
                ((com.fasterxml.jackson.databind.node.ObjectNode)claimed).set("execution",execution);
            }
        }
        return claimed;
    }
    @Transactional public Object publish(UUID task,UUID attempt,String token,JsonNode body) {
        var result=read(body,RunResult.class);var run=runs.locked(task);
        runs.requireAttempt(run,attempt,token,run.get("result_json")==null);
        if(run.get("result_json")!=null) {
            if(!runs.parse(run.get("result_json")).equals(body))throw conflict("同一运行不能发布不同结果");
            return outcome(run,result);
        }
        runs.requireRunning(run);runs.requireCurrent(run);
        if(!runs.identity(run).equals(result.identity()))throw conflict("结果与执行绑定不匹配");
        boolean failed=Set.of(Status.FAILED,Status.INCOMPLETE,Status.CANCELLED).contains(result.status());
        if(run.get("parsed_json")==null) {
            if(!failed||!result.scopeReceipts().isEmpty())throw conflict("尚未完成理解，只可记录失败原因");
        } else {
            var parsed=read(runs.parse(run.get("parsed_json")),ParsedInput.class);
            if(result.mode()!=parsed.mode()||result.status()==Status.ANSWER&&result.mode()==Mode.DRAFT||result.status()==Status.DRAFT_READY&&parsed.relation()==Relation.CLARIFY)throw conflict("结果类型与理解不匹配");
        }
        if(!Objects.equals(result.productionId(),run.get("production_id")==null?null:run.get("production_id").toString())||!Objects.equals(result.editGeneration(),run.get("edit_generation")))throw conflict("结果制作或编辑代次不匹配");
        var refs=json.createArrayNode();
        if(run.get("scope_id")!=null&&(!failed||!result.scopeReceipts().isEmpty()))scopes.requireDependencies(scopes.get((UUID)run.get("scope_id")));
        for(var receipt:result.scopeReceipts()) {
            if(!receipt.scopeId().equals(run.get("scope_id").toString()))throw conflict("结果范围不匹配");
            var rows=db.queryForList("SELECT r.result_json FROM question_tool_receipts r JOIN agent_run_tool_calls c ON c.scope_id=r.scope_id AND c.call_id=r.call_id WHERE c.task_id=? AND c.scope_id=? AND c.call_id=?",task,UUID.fromString(receipt.scopeId()),receipt.callId());
            if(rows.isEmpty()||rows.get(0).get("result_json")==null)throw conflict("工具回执不属于本执行");
            var tool=runs.parse(rows.get(0).get("result_json"));
            if(!"READY".equals(tool.path("status").asText())||tool.path("mock").asBoolean())throw conflict("工具回执尚未成功");
            for(String id:receipt.evidenceIds()) {
                boolean observed=false;for(var record:tool.path("records"))if(id.equals(record.path("id").asText()))observed=true;
                if(!observed||db.queryForObject("SELECT count(*) FROM question_scope_evidence q JOIN evidence_segments e ON e.id=q.evidence_id WHERE q.scope_id=? AND e.id=? AND e.project_id=?",Integer.class,run.get("scope_id"),UUID.fromString(id),run.get("project_id"))!=1)throw conflict("证据不在已读范围回执中");
                refs.addObject().put("type","EVIDENCE").put("id",id).put("scopeId",receipt.scopeId());
            }
        }
        UUID message=result.draft()==null?UUID.randomUUID():UUID.fromString(result.draft().messageId());
        if(result.draft()!=null) {
            var d=result.draft();
            var rows=db.queryForList("SELECT * FROM edit_draft_validations WHERE id=? AND production_id=? AND source_task_id IN (SELECT task_id FROM conversation_execution_requests WHERE task_id=? OR coalesce(core_task_id,task_id)=?) AND edit_generation=? AND draft_hash=? AND requirements_hash=?",UUID.fromString(d.validationId()),run.get("production_id"),task,run.get("core_task_id")==null?task:run.get("core_task_id"),run.get("edit_generation"),d.draftHash(),d.requirementsHash());
            if(rows.isEmpty()||rows.get(0).get("normalized_content_json")==null||!runs.parse(rows.get(0).get("result_json")).path("valid").asBoolean())throw conflict("草稿必须绑定当前执行的有效持久校验");
            var latest=db.queryForMap("SELECT id FROM edit_draft_validations WHERE production_id=? ORDER BY created_at DESC,id DESC LIMIT 1",run.get("production_id"));
            if(!UUID.fromString(d.validationId()).equals(latest.get("id")))throw conflict("该校验已经过期");
            refs.addObject().put("type","EDIT_DRAFT").put("id",d.messageId()).put("productionId",result.productionId()).put("validationId",d.validationId());
        }
        if(result.question()!=null) {
            var q=result.question();refs.addObject().put("type","QUESTION").put("id",q.questionId());
            db.update("INSERT INTO conversation_questions(id,conversation_id,task_id,turn_id,prompt,reason,fields_json,production_id,thread_id,execution_id,wait_id,edit_generation) VALUES (?,?,?,?,?,'Agent clarification','[]',?,?,?,?,?)",UUID.fromString(q.questionId()),run.get("conversation_id"),task,run.get("turn_id"),q.prompt(),run.get("production_id"),run.get("thread_id"),run.get("execution_id"),q.waitId(),run.get("edit_generation"));
        }
        runs.append(message,(UUID)run.get("conversation_id"),"ASSISTANT",result.content(),refs,task);
        if(result.draft()!=null) {
            var d=result.draft();
            db.update("INSERT INTO conversation_edit_drafts(message_id,conversation_id,project_id,production_id,validation_id,source_task_id,draft_hash,requirements_hash,edit_generation) VALUES (?,?,?,?,?,?,?,?,?)",message,run.get("conversation_id"),run.get("project_id"),run.get("production_id"),UUID.fromString(d.validationId()),db.queryForObject("SELECT source_task_id FROM edit_draft_validations WHERE id=?",UUID.class,UUID.fromString(d.validationId())),d.draftHash(),d.requirementsHash(),d.editGeneration());
            db.update("UPDATE edit_productions SET current_draft_message_id=? WHERE id=?",message,run.get("production_id"));
        }
        String state=switch(result.status()){case ANSWER,DRAFT_READY -> "SUCCEEDED";case WAITING_INPUT -> "WAITING_INPUT";case CANCELLED -> "CANCELLED";case INCOMPLETE,FAILED -> "FAILED";};
        db.update("UPDATE tasks SET status=?,result_json=?::jsonb WHERE id=?",state,body.toString(),task);
        db.update("UPDATE task_attempts SET status=? WHERE id=?",state.equals("WAITING_INPUT")?"SUCCEEDED":state,attempt);
        db.update("UPDATE conversation_execution_requests SET result_json=?::jsonb WHERE task_id=?",body.toString(),task);
        db.update("INSERT INTO execution_reports(task_id,attempt_id,report_kind,producer_key,payload_json,outcome_json) VALUES (?,?,'AGENT_RESULT','result',?::jsonb,?::jsonb)",task,attempt,body.toString(),json.valueToTree(outcome(run,result)).toString());
        return outcome(run,result);
    }
    @Transactional public Object receipts(UUID task,UUID attempt,String token) {
        var run=runs.locked(task);runs.requireAttempt(run,attempt,token,true);runs.requireRunning(run);runs.requireCurrent(run);
        return db.query("SELECT c.scope_id,c.call_id,r.result_json FROM agent_run_tool_calls c JOIN question_tool_receipts r ON r.scope_id=c.scope_id AND r.call_id=c.call_id WHERE c.task_id=? ORDER BY c.call_id",(row,n)->{
            var ids=new ArrayList<String>();var tool=runs.parse(row.getString("result_json"));
            for(var record:tool.path("records"))ids.add(record.path("id").asText());
            return new ScopeReceipt(row.getString("scope_id"),row.getString("call_id"),ids);
        },task);
    }
    private Object outcome(Map<String,Object> run,RunResult result){return Map.of("taskId",run.get("task_id"),"status",result.status());}
}
