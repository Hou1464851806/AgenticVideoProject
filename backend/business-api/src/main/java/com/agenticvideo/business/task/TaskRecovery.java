package com.agenticvideo.business.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name="tasks.dispatch-enabled",havingValue="true")
class TaskRecovery {
    private final com.agenticvideo.business.analysis.VisualInspections inspections;
    private final JdbcTemplate database;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final String workerUrl,agentUrl,token;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    TaskRecovery(com.agenticvideo.business.analysis.VisualInspections inspections,JdbcTemplate database,PlatformTransactionManager manager,ObjectMapper json,
            @Value("${worker.control-url:http://media-worker:8081}") String workerUrl,
            @Value("${agent.control-url:http://agent-service:8082}") String agentUrl,
            @Value("${internal.worker-token:}") String token) {
        this.inspections=inspections;this.database=database;transactions=new TransactionTemplate(manager);this.json=json;this.workerUrl=workerUrl;this.agentUrl=agentUrl;this.token=token;
    }
    @Scheduled(fixedDelay=2000,initialDelay=2000)
    public void recover() {
        var expired=database.queryForList("SELECT t.id,t.type,t.current_attempt_id FROM tasks t JOIN task_attempts a ON a.id=t.current_attempt_id WHERE t.status IN ('RUNNING','CANCEL_REQUESTED') AND a.lease_expires_at<now() ORDER BY a.lease_expires_at LIMIT 20");
        for(var candidate:expired) {
            UUID attempt=(UUID)candidate.get("current_attempt_id");
            try {
                String path=(java.util.List.of("CONTENT_ANALYZE","INDEX_BUILD","VISION_ANALYZE","INSPECTION_ANALYZE","AGENT_TURN").contains(candidate.get("type"))?agentUrl:workerUrl)+"/internal/v1/executions/"+attempt;
                var reply=http.send(HttpRequest.newBuilder(URI.create(path)).timeout(Duration.ofSeconds(3)).header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofString());
                if(reply.statusCode()!=200) continue;
                String state=json.readTree(reply.body()).path("state").asText();
                if("RUNNING".equals(state) || "STOPPING".equals(state)) {
                    http.send(HttpRequest.newBuilder(URI.create(path+"/stop")).timeout(Duration.ofSeconds(3)).header("Authorization","Bearer "+token).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.discarding());
                    continue;
                }
                if(!"NOT_FOUND".equals(state)) continue;
                transactions.executeWithoutResult(tx -> {
                    var rows=database.queryForList("SELECT t.*,a.attempt_number,a.lease_expires_at<now() AS expired FROM tasks t JOIN task_attempts a ON a.id=t.current_attempt_id WHERE t.id=? FOR UPDATE OF t,a",candidate.get("id"));
                    if(rows.isEmpty()) return;
                    var task=rows.get(0);
                    if(!attempt.equals(task.get("current_attempt_id")) || !Boolean.TRUE.equals(task.get("expired")) || !java.util.List.of("RUNNING","CANCEL_REQUESTED").contains(task.get("status"))) return;
                    if(inspections.expired(task)){
                        inspections.finished(task,"BUDGET_EXHAUSTED");database.update("UPDATE tasks SET status='CANCELLED' WHERE id=?",task.get("id"));database.update("UPDATE task_attempts SET status='CANCELLED' WHERE id=?",attempt);return;
                    }
                    if("CANCEL_REQUESTED".equals(task.get("status"))) {
                        database.update("UPDATE tasks SET status='CANCELLED' WHERE id=?",task.get("id"));
                        database.update("UPDATE analysis_stages SET status='CANCELLED',updated_at=now() WHERE task_id=? AND stage_key='VISION'",task.get("id"));
                        inspections.finished(task,"CANCELLED");database.update("UPDATE task_attempts SET status='CANCELLED' WHERE id=?",attempt);
                        database.update("UPDATE edit_plans SET editing_task_id=NULL,editing_attempt_id=NULL WHERE editing_task_id=?",task.get("id"));
                        return;
                    }
                    database.update("UPDATE task_attempts SET status='EXPIRED' WHERE id=?",attempt);
                    if(((Number)task.get("attempt_number")).intValue()>=3) {
                        String error="{\"code\":\"EXECUTION_RECOVERY_EXHAUSTED\",\"message\":\"媒体执行中断，恢复次数已耗尽\"}";
                        database.update("UPDATE tasks SET status='FAILED',result_json=?::jsonb WHERE id=?",error,task.get("id"));inspections.finished(task,"FAILED");
                        database.update("UPDATE edit_plans SET editing_task_id=NULL,editing_attempt_id=NULL WHERE editing_task_id=?",task.get("id"));
                        if("MEDIA_PREPARE".equals(task.get("type"))) database.update("UPDATE assets SET validation_status='INVALID',error_json=?::jsonb WHERE id=(SELECT (input_json->>'assetId')::uuid FROM tasks WHERE id=?) AND validation_status='VALIDATING'",error,task.get("id"));
                        else database.update("UPDATE analysis_stages SET status='FAILED',error_json=?::jsonb WHERE task_id=? AND stage_key IN ('ASR','INDEX','VISION')",error,task.get("id"));
                        return;
                    }
                    database.update("UPDATE tasks SET status='PENDING',current_attempt_id=NULL WHERE id=?",task.get("id"));
                    UUID event=UUID.randomUUID();
                    database.update("INSERT INTO outbox_events (id,task_id,type,payload_json) VALUES (?,?,?,jsonb_build_object('schemaVersion','1.0','eventId',?::text,'taskId',?::text,'taskType',?::text,'projectId',?::text,'occurredAt',now()))",event,task.get("id"),task.get("type"),event,task.get("id"),task.get("type"),task.get("project_id"));
                });
            } catch(Exception unavailable) {
                // An unreachable executor is not evidence that its process has exited.
            }
        }
    }
}
