package com.agenticvideo.business.task;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Recovers abandoned local generations through Outbox/MQ; never executes a stage here. */
@Component
@ConditionalOnProperty(name="tasks.dispatch-enabled",havingValue="true")
class TaskRecovery {
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final LocalTaskRunner runtime;
    TaskRecovery(JdbcTemplate db,PlatformTransactionManager manager,LocalTaskRunner runtime) {
        this.db=db; transactions=new TransactionTemplate(manager); this.runtime=runtime;
    }
    @Scheduled(fixedDelay=2000,initialDelay=2000)
    public void recover() {
        for(var candidate:db.queryForList("SELECT t.id FROM tasks t JOIN task_attempts a ON a.id=t.current_attempt_id WHERE a.local_execution AND t.type IN ('MEDIA_PREPARE','VISION_PREPARE','PREVIEW_RENDER','EXPORT_RENDER','CONTENT_ANALYZE','VISION_ANALYZE','INDEX_BUILD') AND t.status IN ('RUNNING','CANCEL_REQUESTED') ORDER BY t.created_at LIMIT 20")) {
            UUID id=(UUID)candidate.get("id");
            if(runtime.active(id))continue;
            transactions.executeWithoutResult(tx->{
                var rows=db.queryForList("SELECT t.*,a.attempt_number,a.local_execution FROM tasks t JOIN task_attempts a ON a.id=t.current_attempt_id WHERE t.id=? FOR UPDATE OF t,a",id);
                if(rows.isEmpty() || runtime.active(id))return;
                var task=rows.get(0);
                if(!Boolean.TRUE.equals(task.get("local_execution")) || !java.util.Set.of("RUNNING","CANCEL_REQUESTED").contains(task.get("status")))return;
                UUID attempt=(UUID)task.get("current_attempt_id");
                boolean cancel="CANCEL_REQUESTED".equals(task.get("status"));
                boolean analysis=java.util.Set.of("CONTENT_ANALYZE","VISION_ANALYZE","INDEX_BUILD").contains(task.get("type"));
                boolean exhausted=analysis?((Number)task.get("local_failure_count")).intValue()>=2:((Number)task.get("attempt_number")).intValue()>=3;
                if(analysis) {
                    db.update("UPDATE model_calls SET status='UNCERTAIN' WHERE task_id=? AND status='SUBMITTING' AND response_json IS NULL",id);
                    db.update("UPDATE vision_model_calls SET status='UNCERTAIN' WHERE task_id=? AND status='SUBMITTING' AND response_json IS NULL",id);
                    db.update("UPDATE embedding_batches SET status='UNCERTAIN' WHERE owner_attempt_id=? AND status='SUBMITTING' AND response_json IS NULL",attempt);
                    db.update("UPDATE tasks SET local_failure_count=local_failure_count+1 WHERE id=?",id);
                }
                String state=cancel?"CANCELLED":exhausted?"FAILED":"PENDING";
                db.update("UPDATE task_attempts SET status=? WHERE id=?",cancel?"CANCELLED":"EXPIRED",attempt);
                db.update("UPDATE tasks SET status=?,current_attempt_id=CASE WHEN ?='PENDING' THEN NULL ELSE current_attempt_id END,result_json=CASE WHEN ?='FAILED' THEN '{\"code\":\"EXECUTION_RECOVERY_EXHAUSTED\",\"message\":\"执行恢复次数已耗尽\"}'::jsonb ELSE result_json END WHERE id=?",state,state,state,id);
                if("PENDING".equals(state)) {
                    UUID event=UUID.randomUUID();
                    db.update("INSERT INTO outbox_events(id,task_id,type,payload_json) VALUES (?,?,?,jsonb_build_object('schemaVersion','1.0','eventId',?::text,'taskId',?::text,'taskType',?::text))",event,id,task.get("type"),event,id,task.get("type"));
                }else {
                    db.update("UPDATE analysis_stages SET status=?,updated_at=now() WHERE task_id=? AND status<>'SUCCEEDED'",state,id);
                    if("MEDIA_PREPARE".equals(task.get("type")) && "FAILED".equals(state))
                        db.update("UPDATE assets SET validation_status='INVALID',error_json='{\"code\":\"EXECUTION_RECOVERY_EXHAUSTED\",\"message\":\"执行恢复次数已耗尽\"}'::jsonb WHERE id=(SELECT (input_json->>'assetId')::uuid FROM tasks WHERE id=?) AND validation_status='VALIDATING'",id);
                    db.update("UPDATE edit_plans SET editing_task_id=NULL,editing_attempt_id=NULL WHERE editing_task_id=?",id);
                }
            });
        }
    }
}
