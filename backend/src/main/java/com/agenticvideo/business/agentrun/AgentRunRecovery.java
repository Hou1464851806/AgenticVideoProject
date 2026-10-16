package com.agenticvideo.business.agentrun;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Lease expiry revokes an attempt, never resets a Python execution or its paid budget. */
@Component
public class AgentRunRecovery {
    private final JdbcTemplate db;
    private final AgentRuns runs;
    private final TransactionTemplate tx;
    public AgentRunRecovery(JdbcTemplate db,AgentRuns runs,PlatformTransactionManager manager){this.db=db;this.runs=runs;tx=new TransactionTemplate(manager);}
    @Scheduled(fixedDelay=2000,initialDelay=2000)
    public void recover() {
        for(var row:db.queryForList("""
                SELECT t.id FROM tasks t JOIN conversation_execution_requests r ON r.task_id=t.id
                JOIN task_attempts a ON a.id=t.current_attempt_id
                WHERE r.schema_version='1.0' AND t.status IN ('RUNNING','CANCEL_REQUESTED')
                AND a.lease_expires_at<now() ORDER BY t.created_at LIMIT 20
                """)) {
            UUID task=(UUID)row.get("id");
            tx.executeWithoutResult(status->{
                var run=runs.locked(task);
                if(!java.util.Set.of("RUNNING","CANCEL_REQUESTED").contains(run.get("status")))return;
                var attempt=db.queryForMap("SELECT *,lease_expires_at<clock_timestamp() AS expired FROM task_attempts WHERE id=? FOR UPDATE",run.get("current_attempt_id"));
                if(!Boolean.TRUE.equals(attempt.get("expired")))return;
                boolean cancel="CANCEL_REQUESTED".equals(run.get("status"));
                db.update("UPDATE task_attempts SET status=? WHERE id=?",cancel?"CANCELLED":"EXPIRED",attempt.get("id"));
                db.update("UPDATE tasks SET status=?,current_attempt_id=CASE WHEN ? THEN current_attempt_id ELSE NULL END WHERE id=?",cancel?"CANCELLED":"PENDING",cancel,task);
                if(!cancel) {
                    UUID event=UUID.randomUUID();
                    db.update("INSERT INTO outbox_events(id,task_id,type,payload_json) VALUES (?,?,'AGENT_TURN',jsonb_build_object('schemaVersion','1.0','eventId',?::text,'taskId',?::text,'taskType','AGENT_TURN'))",event,task,event,task);
                }
            });
        }
    }
}
