package com.agenticvideo.business.agentrun;

import com.agenticvideo.business.plan.ProductionDrafts;
import com.agenticvideo.business.tools.ReadTools;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.agenticvideo.business.agentrun.AgentRuns.conflict;

/** Read calls may perform network search outside locks; validation commits under execution locks. */
@Service
public class AgentRunTools {
    private final JdbcTemplate db;
    private final AgentRuns runs;
    private final ReadTools tools;
    private final TransactionTemplate tx;
    public AgentRunTools(JdbcTemplate db,AgentRuns runs,ReadTools tools,PlatformTransactionManager manager){this.db=db;this.runs=runs;this.tools=tools;tx=new TransactionTemplate(manager);}
    public JsonNode execute(UUID task,UUID attempt,String token,Long generation,JsonNode request) {
        var run=tx.execute(status->{
            var row=runs.locked(task);admit(row,attempt,token,generation);
            if("validate_edit_draft".equals(request.path("tool_name").asText())) {
                if(row.get("production_id")==null)throw conflict("当前运行没有制作校验资格");
            }
            return row;
        });
        UUID scope=(UUID)run.get("scope_id");
        if("validate_edit_draft".equals(request.path("tool_name").asText()))return tx.execute(status->{
            var current=runs.locked(task);admit(current,attempt,token,generation);
            var result=tools.execute(scope,(UUID)run.get("production_id"),request,new ProductionDrafts.Execution(task,attempt,generation));
            if("READY".equals(result.path("status").asText()))remember(task,scope,request);return result;
        });
        var result=tools.execute(scope,request);
        return tx.execute(status->{var current=runs.locked(task);admit(current,attempt,token,generation);if("READY".equals(result.path("status").asText()))remember(task,scope,request);return result;});
    }
    private void admit(Map<String,Object> run,UUID attempt,String token,Long generation){
        runs.requireAttempt(run,attempt,token,true);runs.requireRunning(run);runs.requireCurrent(run);
        if(run.get("parsed_json")==null||!Objects.equals(generation,run.get("edit_generation")))throw conflict("工具调用须匹配已接纳执行代次");
    }
    private void remember(UUID task,UUID scope,JsonNode request){
        db.update("INSERT INTO agent_run_tool_calls(task_id,scope_id,call_id) VALUES (?,?,?) ON CONFLICT(task_id,call_id) DO NOTHING",task,scope,request.path("call_id").asText());
        if(db.queryForObject("SELECT count(*) FROM agent_run_tool_calls WHERE task_id=? AND scope_id=? AND call_id=?",Integer.class,task,scope,request.path("call_id").asText())!=1)throw conflict("工具调用范围已经绑定其他执行");
    }
}
