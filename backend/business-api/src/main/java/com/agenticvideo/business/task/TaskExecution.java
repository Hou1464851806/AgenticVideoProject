package com.agenticvideo.business.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@Service
public class TaskExecution {
    private final JdbcTemplate database;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final String secret;
    TaskExecution(JdbcTemplate database, PlatformTransactionManager manager, ObjectMapper json,
            @Value("${internal.worker-token:}") String secret) {
        this.database = database; this.transactions = new TransactionTemplate(manager); this.json = json; this.secret = secret;
    }
    public Map<String, Object> read(UUID id) {
        var tasks = database.queryForList("SELECT * FROM tasks WHERE id=?", id);
        if (tasks.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "任务不存在或无权访问");
        return tasks.get(0);
    }
    public int attemptCount(UUID id) { return database.queryForObject("SELECT count(*) FROM task_attempts WHERE task_id=?",Integer.class,id); }
    public Object claim(UUID id, TaskController.Claim request) {
        return transactions.execute(tx -> {
            var rows = database.queryForList("SELECT * FROM tasks WHERE id=? FOR UPDATE", id);
            if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "任务不存在");
            var task = rows.get(0);
            if (!"MEDIA_PREPARE".equals(task.get("type"))) throw new ResponseStatusException(CONFLICT, "执行器不能领取此任务类型");
            if ("RUNNING".equals(task.get("status"))) {
                var attempt = database.queryForMap("SELECT *, lease_expires_at>now() AS valid FROM task_attempts WHERE id=?", task.get("current_attempt_id"));
                if (request.executorId().equals(attempt.get("executor_id")) && request.claimRequestId().equals(attempt.get("claim_request_id")) && Boolean.TRUE.equals(attempt.get("valid")))
                    return claimed(task, attempt);
                throw new ResponseStatusException(CONFLICT, "任务已有执行者或领取资格已过期");
            }
            if (!"PENDING".equals(task.get("status"))) return Map.of("taskId", id, "status", task.get("status"), "claimed", false);
            UUID attemptId = UUID.randomUUID();
            int number = database.queryForObject("SELECT coalesce(max(attempt_number),0)+1 FROM task_attempts WHERE task_id=?", Integer.class, id);
            database.update("INSERT INTO task_attempts (id,task_id,attempt_number,executor_id,claim_request_id,lease_token_hash,lease_expires_at) VALUES (?,?,?,?,?,?,now()+interval '60 seconds')", attemptId, id, number, request.executorId(), request.claimRequestId(), hash(token(attemptId)));
            database.update("UPDATE tasks SET status='RUNNING',current_attempt_id=? WHERE id=?", attemptId, id);
            task.put("status", "RUNNING");
            return claimed(task, database.queryForMap("SELECT * FROM task_attempts WHERE id=?", attemptId));
        });
    }
    private Map<String, Object> requireAttempt(Map<String, Object> task, UUID attemptId, String leaseToken, boolean requireLive) {
        if (!attemptId.equals(task.get("current_attempt_id"))) throw new ResponseStatusException(CONFLICT, "任务执行尝试已失效");
        var attempt = database.queryForMap("SELECT *,lease_expires_at>now() AS live FROM task_attempts WHERE id=?", attemptId);
        if (!MessageDigest.isEqual(hash(leaseToken).getBytes(StandardCharsets.US_ASCII), attempt.get("lease_token_hash").toString().getBytes(StandardCharsets.US_ASCII))
                || requireLive && !Boolean.TRUE.equals(attempt.get("live"))) throw new ResponseStatusException(CONFLICT, "任务执行凭证或租约已失效");
        return attempt;
    }
    private Map<String, Object> locked(UUID id) {
        var rows = database.queryForList("SELECT * FROM tasks WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "任务不存在");
        return rows.get(0);
    }
    public Object heartbeat(UUID id, TaskController.Execution request) {
        return transactions.execute(tx -> {
            var task = locked(id);
            requireAttempt(task, request.attemptId(), request.leaseToken(), true);
            if (!java.util.List.of("RUNNING", "CANCEL_REQUESTED").contains(task.get("status"))) throw new ResponseStatusException(CONFLICT, "任务已进入终态");
            database.update("UPDATE task_attempts SET heartbeat_at=now(),lease_expires_at=now()+interval '60 seconds' WHERE id=?", request.attemptId());
            var expiry = database.queryForObject("SELECT lease_expires_at FROM task_attempts WHERE id=?", java.sql.Timestamp.class, request.attemptId());
            return Map.of("status", task.get("status"), "action", "CANCEL_REQUESTED".equals(task.get("status")) ? "STOP" : "CONTINUE",
                    "leaseExpiresAt", expiry.toInstant().toString());
        });
    }
    public Object cancel(UUID id) {
        return transactions.execute(tx -> {
            var task = locked(id);
            String state = task.get("status").toString();
            String next = switch(state) { case "PENDING" -> "CANCELLED"; case "RUNNING" -> "CANCEL_REQUESTED"; default -> state; };
            database.update("UPDATE tasks SET status=? WHERE id=?", next, id);
            return Map.of("taskId", id, "status", next);
        });
    }
    public Object cancelled(UUID id, TaskController.Stopped request) {
        return transactions.execute(tx -> {
            var task = locked(id);
            requireAttempt(task, request.attemptId(), request.leaseToken(), false);
            if (!request.processExited()) throw new ResponseStatusException(CONFLICT, "执行进程尚未退出");
            if ("CANCELLED".equals(task.get("status"))) return Map.of("status", "CANCELLED");
            if (!"CANCEL_REQUESTED".equals(task.get("status"))) throw new ResponseStatusException(CONFLICT, "任务没有有效取消请求");
            database.update("UPDATE task_attempts SET status='CANCELLED' WHERE id=?", request.attemptId());
            database.update("UPDATE tasks SET status='CANCELLED' WHERE id=?", id);
            return Map.of("status", "CANCELLED");
        });
    }
    public Object complete(UUID id, TaskController.Completed request) {
        return transactions.execute(tx -> {
            var task = locked(id);
            if (!"MEDIA_PREPARED".equals(request.resultType()) || !"MEDIA_PREPARE".equals(task.get("type")))
                throw new ResponseStatusException(CONFLICT, "任务结果类型不匹配");
            String result = request.result().toString();
            var previous = database.queryForList("SELECT *,payload_json=?::jsonb AS same_payload FROM execution_reports WHERE task_id=? AND attempt_id=? AND report_kind='COMPLETE' AND producer_key=?", result, id, request.attemptId(), request.producerKey());
            if (!previous.isEmpty()) {
                var attempt = database.queryForMap("SELECT lease_token_hash FROM task_attempts WHERE id=?", request.attemptId());
                if (!MessageDigest.isEqual(hash(request.leaseToken()).getBytes(StandardCharsets.US_ASCII), attempt.get("lease_token_hash").toString().getBytes(StandardCharsets.US_ASCII)) || !Boolean.TRUE.equals(previous.get(0).get("same_payload")))
                    throw new ResponseStatusException(CONFLICT, "重复任务回报身份或内容不符");
                try { return json.readTree(previous.get(0).get("outcome_json").toString()); }
                catch (java.io.IOException invalid) { throw new IllegalStateException(invalid); }
            }
            requireAttempt(task, request.attemptId(), request.leaseToken(), true);
            if (!"RUNNING".equals(task.get("status"))) throw new ResponseStatusException(CONFLICT, "任务已失去结果发布资格");
            com.fasterxml.jackson.databind.JsonNode input;
            try { input = json.readTree(task.get("input_json").toString()); }
            catch (java.io.IOException invalid) { throw new IllegalStateException(invalid); }
            UUID assetId = UUID.fromString(input.path("assetId").asText());
            long duration = request.result().path("durationMs").asLong();
            if (request.result().path("byteSize").asLong(-1) != input.path("sizeBytes").asLong()
                    || !request.result().path("sha256").asText().equalsIgnoreCase(input.path("sha256").asText())
                    || duration <= 0 || duration > 3600000 || !request.result().path("mediaInfo").isObject() || !request.result().path("timeMapping").isObject())
                throw new ResponseStatusException(CONFLICT, "媒体验证结果不符合固定输入或时长限制");
            int published = database.update("UPDATE assets SET validation_status='VALID',duration_ms=?,media_info_json=?::jsonb,time_mapping_json=?::jsonb,row_version=row_version+1 WHERE id=? AND validation_status='VALIDATING'",
                    duration, request.result().path("mediaInfo").toString(), request.result().path("timeMapping").toString(), assetId);
            if (published != 1) throw new ResponseStatusException(CONFLICT, "素材已失去发布资格");
            database.update("UPDATE tasks SET status='SUCCEEDED',result_json=?::jsonb WHERE id=?", result, id);
            database.update("UPDATE task_attempts SET status='SUCCEEDED' WHERE id=?", request.attemptId());
            String outcome = "{\"status\":\"SUCCEEDED\",\"assetId\":\"" + assetId + "\"}";
            database.update("INSERT INTO execution_reports (task_id,attempt_id,report_kind,producer_key,payload_json,outcome_json) VALUES (?,?,'COMPLETE',?,?::jsonb,?::jsonb)", id, request.attemptId(), request.producerKey(), result, outcome);
            return Map.of("status", "SUCCEEDED", "assetId", assetId);
        });
    }
    public Object fail(UUID id,TaskController.Failed request) {
        return transactions.execute(tx -> {
            var task=locked(id);
            if(!"MEDIA_PREPARE".equals(task.get("type"))) throw new ResponseStatusException(CONFLICT,"任务结果类型不匹配");
            String payload;
            try { payload=json.writeValueAsString(Map.of("code",request.code(),"message",request.message())); }
            catch(java.io.IOException invalid) { throw new IllegalStateException(invalid); }
            var previous=database.queryForList("SELECT *,payload_json=?::jsonb AS same_payload FROM execution_reports WHERE task_id=? AND attempt_id=? AND report_kind='FAIL' AND producer_key=?",payload,id,request.attemptId(),request.producerKey());
            if(!previous.isEmpty()) {
                var credentials=database.queryForMap("SELECT lease_token_hash FROM task_attempts WHERE id=?",request.attemptId());
                if(!MessageDigest.isEqual(hash(request.leaseToken()).getBytes(StandardCharsets.US_ASCII),credentials.get("lease_token_hash").toString().getBytes(StandardCharsets.US_ASCII)) || !Boolean.TRUE.equals(previous.get(0).get("same_payload")))
                    throw new ResponseStatusException(CONFLICT,"重复错误回报内容或凭证不符");
                try { return json.readTree(previous.get(0).get("outcome_json").toString()); }
                catch(java.io.IOException invalid) { throw new IllegalStateException(invalid); }
            }
            var attempt=requireAttempt(task,request.attemptId(),request.leaseToken(),true);
            if(!"RUNNING".equals(task.get("status"))) throw new ResponseStatusException(CONFLICT,"任务已失去回报资格");
            database.update("UPDATE task_attempts SET status='FAILED' WHERE id=?",request.attemptId());
            boolean transientFailure="MEDIA_EXECUTION_FAILED".equals(request.code());
            if(transientFailure && ((Number)attempt.get("attempt_number")).intValue()<3) {
                database.update("UPDATE tasks SET status='PENDING',current_attempt_id=NULL WHERE id=?",id);
                UUID eventId=UUID.randomUUID();
                database.update("INSERT INTO outbox_events (id,task_id,type,payload_json,available_at) VALUES (?,?,'MEDIA_PREPARE',jsonb_build_object('schemaVersion','1.0','eventId',?::text,'taskId',?::text,'taskType','MEDIA_PREPARE','projectId',?::text,'occurredAt',now()),now()+interval '5 seconds')",eventId,id,eventId,id,task.get("project_id"));
                String outcome="{\"status\":\"PENDING\"}";
                database.update("INSERT INTO execution_reports (task_id,attempt_id,report_kind,producer_key,payload_json,outcome_json) VALUES (?,?,'FAIL',?,?::jsonb,?::jsonb)",id,request.attemptId(),request.producerKey(),payload,outcome);
                return Map.of("status","PENDING");
            }
            com.fasterxml.jackson.databind.JsonNode input;
            try { input=json.readTree(task.get("input_json").toString()); }
            catch(java.io.IOException invalid) { throw new IllegalStateException(invalid); }
            UUID assetId=UUID.fromString(input.path("assetId").asText());
            database.update("UPDATE assets SET validation_status='INVALID',error_json=?::jsonb,row_version=row_version+1 WHERE id=? AND validation_status='VALIDATING'",payload,assetId);
            database.update("UPDATE tasks SET status='FAILED',result_json=?::jsonb WHERE id=?",payload,id);
            if(!transientFailure) database.update("INSERT INTO object_cleanup_jobs (id,asset_id,object_key) VALUES (?,?,?) ON CONFLICT(asset_id) DO NOTHING",UUID.randomUUID(),assetId,input.path("objectKey").asText());
            String outcome="{\"status\":\"FAILED\",\"assetId\":\""+assetId+"\"}";
            database.update("INSERT INTO execution_reports (task_id,attempt_id,report_kind,producer_key,payload_json,outcome_json) VALUES (?,?,'FAIL',?,?::jsonb,?::jsonb)",id,request.attemptId(),request.producerKey(),payload,outcome);
            return Map.of("status","FAILED","assetId",assetId);
        });
    }
    private Object claimed(Map<String, Object> task, Map<String, Object> attempt) {
        UUID id = (UUID) attempt.get("id");
        try {
            return Map.of("taskId", task.get("id"), "status", "RUNNING", "claimed", true, "attemptId", id,
                    "leaseToken", token(id), "leaseExpiresAt", ((java.sql.Timestamp) attempt.get("lease_expires_at")).toInstant().toString(),
                    "input", json.readTree(task.get("input_json").toString()));
        } catch (java.io.IOException invalid) { throw new IllegalStateException("Invalid persisted task input", invalid); }
    }
    private String token(UUID attemptId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(attemptId.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException unavailable) { throw new IllegalStateException(unavailable); }
    }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.GeneralSecurityException unavailable) { throw new IllegalStateException(unavailable); }
    }
}
