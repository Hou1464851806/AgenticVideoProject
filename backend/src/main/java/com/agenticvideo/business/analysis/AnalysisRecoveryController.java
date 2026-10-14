package com.agenticvideo.business.analysis;

import com.agenticvideo.business.project.ProjectAccess;
import com.agenticvideo.business.search.SearchIndexes;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;

/** Recovery changes one failed stage while preserving the current evidence revision. */
@RestController
class AnalysisRecoveryController {
    private final com.agenticvideo.business.storage.StorageCapacity capacity;
    private final JdbcTemplate db;private final ProjectAccess access;private final VisualAnalysis vision;private final SearchIndexes indexes;private final TransactionTemplate transactions;
    AnalysisRecoveryController(com.agenticvideo.business.storage.StorageCapacity capacity,JdbcTemplate db,ProjectAccess access,VisualAnalysis vision,SearchIndexes indexes,PlatformTransactionManager manager){this.capacity=capacity;this.db=db;this.access=access;this.vision=vision;this.indexes=indexes;this.transactions=new TransactionTemplate(manager);}
    @PostMapping("/api/v1/assets/{asset}/analysis-stages/{stage}/retry")
    ResponseEntity<?> retry(@PathVariable UUID asset,@PathVariable String stage,Authentication account){
        var projects=db.queryForList("SELECT project_id FROM assets WHERE id=?",asset);
        if(projects.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"素材不存在或无权访问");access.requireOwned((UUID)projects.get(0).get("project_id"),account);
        var outcome=transactions.execute(tx->{
            if("MEDIA".equals(stage))return retryMedia(asset);
            var rows=db.queryForList("SELECT s.* FROM analysis_stages s JOIN asset_analysis_heads h ON h.analysis_id=s.analysis_id JOIN assets a ON a.id=h.asset_id WHERE h.asset_id=? AND s.stage_key=? FOR UPDATE OF a,s",asset,stage);
            if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"该阶段没有可恢复记录");
            var state=rows.get(0);String status=state.get("status").toString();
            if(Set.of("PENDING","RUNNING").contains(status)&&state.get("task_id")!=null)return Map.of("taskId",state.get("task_id"));
            if(!Set.of("FAILED","UNVERIFIED","CANCELLED").contains(status))throw new ResponseStatusException(HttpStatus.CONFLICT,"只能恢复失败、未验证或已取消的阶段");
            if("VISION".equals(stage))return vision.enqueue(asset);
            if(Set.of("EMBEDDING","INDEX").contains(stage))return indexes.enqueue(asset);
            if("ASR".equals(stage)) {
                UUID task=(UUID)state.get("task_id");
                if(task==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"语音阶段没有可恢复任务");
                var previous=db.queryForMap("SELECT * FROM tasks WHERE id=? FOR UPDATE",task);
                if(!Set.of("FAILED","CANCELLED").contains(previous.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"原语音执行尚未停止");
                var calls=db.queryForList("SELECT *,result_json->>'status'='FAILED' AS confirmed_failure FROM model_calls WHERE task_id=? ORDER BY retry_number DESC LIMIT 1",task);
                if(!calls.isEmpty()&&Set.of("SUBMITTING","UNCERTAIN").contains(calls.get(0).get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"原模型调用结果未知或提供方已终止，请先核查原调用；不会重复付费提交");
                if(!calls.isEmpty()&&"FAILED".equals(calls.get(0).get("status"))) {
                    var call=calls.get(0);
                    if(!Boolean.TRUE.equals(call.get("confirmed_failure")))throw new ResponseStatusException(HttpStatus.CONFLICT,"提供方终态未知，不能重新付费提交");
                    db.update("INSERT INTO model_calls(id,analysis_id,task_id,model,parameters_json,status,retry_number) VALUES (?,?,?,?,?::jsonb,'READY',?)",UUID.randomUUID(),state.get("analysis_id"),task,call.get("model"),call.get("parameters_json").toString(),((Number)call.get("retry_number")).intValue()+1);
                }
                db.update("UPDATE tasks SET status='PENDING',current_attempt_id=NULL,result_json=NULL WHERE id=?",task);
                db.update("UPDATE analysis_stages SET status='PENDING',error_json=NULL,updated_at=now() WHERE analysis_id=? AND stage_key='ASR'",state.get("analysis_id"));
                UUID event=UUID.randomUUID();
                db.update("INSERT INTO outbox_events(id,task_id,type,payload_json) VALUES (?,?,'CONTENT_ANALYZE',jsonb_build_object('schemaVersion','1.0','eventId',?::text,'taskId',?::text,'taskType','CONTENT_ANALYZE','projectId',?::text,'occurredAt',now()))",event,task,event,task,previous.get("project_id"));
                return Map.of("taskId",task);
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT,"该阶段暂不能安全自动恢复");
        });
        return ResponseEntity.accepted().body(Map.of("data",outcome,"requestId",UUID.randomUUID()));
    }
    private Object retryMedia(UUID asset) {
        var source=db.queryForMap("SELECT *,error_json->>'code' AS failure_code FROM assets WHERE id=? FOR UPDATE",asset);
        var rows=db.queryForList("SELECT * FROM tasks WHERE type='MEDIA_PREPARE' AND input_json->>'assetId'=? ORDER BY created_at DESC LIMIT 1 FOR UPDATE",asset.toString());
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"没有可恢复的媒体验证任务");
        var task=rows.get(0);String status=task.get("status").toString();
        if(Set.of("PENDING","RUNNING").contains(status))return Map.of("taskId",task.get("id"));
        if(!Set.of("FAILED","CANCELLED").contains(status)||"VALID".equals(source.get("validation_status"))||("FAILED".equals(status)&&!Set.of("MEDIA_EXECUTION_FAILED","EXECUTION_RECOVERY_EXHAUSTED").contains(source.get("failure_code"))))throw new ResponseStatusException(HttpStatus.CONFLICT,"文件已确认无效或该阶段不需重试，请核查原因并重新上传有效视频");
        if(db.queryForObject("SELECT count(*) FROM object_cleanup_jobs WHERE asset_id=?",Integer.class,asset)>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"原文件已进入清理，需重新上传");
        capacity.require(((Number)task.get("disk_budget_bytes")).longValue());
        db.update("UPDATE assets SET validation_status='VALIDATING',error_json=NULL,row_version=row_version+1 WHERE id=?",asset);
        db.update("UPDATE tasks SET status='PENDING',current_attempt_id=NULL,result_json=NULL WHERE id=?",task.get("id"));
        UUID event=UUID.randomUUID();
        db.update("INSERT INTO outbox_events(id,task_id,type,payload_json) VALUES (?,?,'MEDIA_PREPARE',jsonb_build_object('schemaVersion','1.0','eventId',?::text,'taskId',?::text,'taskType','MEDIA_PREPARE','projectId',?::text,'occurredAt',now()))",event,task.get("id"),event,task.get("id"),task.get("project_id"));
        return Map.of("taskId",task.get("id"));
    }

}
