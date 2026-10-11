package com.agenticvideo.business.render;

import com.agenticvideo.business.upload.MultipartStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/** Owns attempt-specific upload identities and the transition into a playable artifact. */
@Service
public class RenderArtifacts {
    private final JdbcTemplate database;
    private final MultipartStorage storage;
    private final ObjectMapper json;
    public RenderArtifacts(JdbcTemplate database,MultipartStorage storage,ObjectMapper json) {this.database=database;this.storage=storage;this.json=json;}
    public Map<String,Object> row(UUID id) {
        var rows=database.queryForList("SELECT * FROM artifacts WHERE id=?",id);
        if(rows.isEmpty())throw new ResponseStatusException(NOT_FOUND,"预览产物不存在或无权访问");
        return rows.get(0);
    }
    public JsonNode prepare(Map<String,Object> task,UUID attempt,JsonNode input) {
        var existing=database.queryForList("SELECT * FROM artifacts WHERE attempt_id=?",attempt);
        Map<String,Object> artifact;
        if(existing.isEmpty()) {
            UUID id=UUID.randomUUID(),request=UUID.fromString(input.path("requestId").asText());
            String kind="EXPORT_RENDER".equals(task.get("type"))?"EXPORT":"PREVIEW";
            String key=("EXPORT".equals(kind)?"exports/":"previews/")+request+"/"+attempt+".mp4";
            database.update("INSERT INTO artifacts(id,project_id,request_id,revision_id,output_id,task_id,attempt_id,kind,object_key) VALUES (?,?,?,?,?,?,?,?,?)",
                    id,task.get("project_id"),request,UUID.fromString(input.path("revisionId").asText()),UUID.fromString(input.path("outputId").asText()),task.get("id"),attempt,kind,key);
            artifact=row(id);
        } else artifact=existing.get(0);
        ObjectNode granted=(ObjectNode)input.deepCopy();
        granted.put("artifactId",artifact.get("id").toString()).put("artifactObjectKey",artifact.get("object_key").toString());
        return granted;
    }
    public Object publish(Map<String,Object> task,JsonNode input,JsonNode result) {
        var rows=database.queryForList("SELECT * FROM artifacts WHERE attempt_id=? FOR UPDATE",task.get("current_attempt_id"));
        if(rows.isEmpty())throw new ResponseStatusException(CONFLICT,"当前尝试没有合法上传目标");
        var artifact=rows.get(0);
        if(!artifact.get("id").toString().equals(result.path("artifactId").asText()) || !artifact.get("object_key").equals(result.path("objectKey").asText()))
            throw new ResponseStatusException(CONFLICT,"产物身份或上传目标不属于当前执行尝试");
        long size=integer(result.path("byteSize")),duration=integer(result.path("durationMs"));
        long expected=input.path("output").path("durationMs").asLong();
        if(size<=0 || size>2147483648L || duration<=0 || Math.abs(duration-expected)>100 || !result.path("sha256").asText().matches("[0-9a-f]{64}"))
            throw new ResponseStatusException(CONFLICT,"产物大小、校验值或时长不符合固定渲染输入");
        JsonNode video=null,audio=null;
        for(var stream:result.path("mediaInfo").path("streams")) {
            if("video".equals(stream.path("codec_type").asText()))video=stream;
            if("audio".equals(stream.path("codec_type").asText()))audio=stream;
        }
        if(video==null || audio==null || !"h264".equals(video.path("codec_name").asText()) || !"aac".equals(audio.path("codec_name").asText())
                || video.path("width").asInt()!=input.path("configuration").path("width").asInt()
                || video.path("height").asInt()!=input.path("configuration").path("height").asInt()
                || audio.path("channels").asInt()!=2 || audio.path("sample_rate").asInt()!=48000)
            throw new ResponseStatusException(CONFLICT,"产物缺少受控规格的视频或音轨");
        if(!Long.valueOf(size).equals(storage.objectSize(artifact.get("object_key").toString())))
            throw new ResponseStatusException(CONFLICT,"产物尚未完整上传");
        try {
            database.update("UPDATE artifacts SET state='AVAILABLE',byte_size=?,sha256=?,duration_ms=?,media_info_json=?::jsonb,available_at=now(),last_accessed_at=now() WHERE id=? AND state='STAGED'",
                    size,result.path("sha256").asText(),duration,json.writeValueAsString(result.path("mediaInfo")),artifact.get("id"));
        } catch(java.io.IOException invalid){throw new IllegalStateException(invalid);}
        database.update("UPDATE render_requests SET artifact_id=? WHERE id=? AND task_id=?",artifact.get("id"),artifact.get("request_id"),task.get("id"));
        return Map.of("status","SUCCEEDED","requestId",artifact.get("request_id"),"artifactId",artifact.get("id"));
    }
    public boolean available(UUID id) {
        var artifact=row(id);
        if(!"AVAILABLE".equals(artifact.get("state")))return false;
        boolean exists=Objects.equals(artifact.get("byte_size"),storage.objectSize(artifact.get("object_key").toString()));
        if(!exists)database.update("UPDATE artifacts SET state='UNAVAILABLE' WHERE id=? AND state='AVAILABLE'",id);
        return exists;
    }
    public Object playback(UUID id) {
        if(!available(id))throw new ResponseStatusException(GONE,"预览产物不可用，请手动重新生成");
        var artifact=row(id);database.update("UPDATE artifacts SET last_accessed_at=now() WHERE id=?",id);
        return Map.of("artifactId",id,"url",storage.playbackUrl(artifact.get("object_key").toString()),"durationMs",artifact.get("duration_ms"),"expiresInSeconds",300);
    }
    /** Terminal attempts cannot upload again; failed deletions remain staged for the independent sweep. */
    public void cleanupTask(UUID task) {
        var rows=database.queryForList("SELECT r.id,r.object_key FROM artifacts r JOIN task_attempts a ON a.id=r.attempt_id WHERE r.task_id=? AND r.state='STAGED' AND a.status IN ('FAILED','EXPIRED','CANCELLED')",task);
        for(var artifact:rows) {
            try {
                storage.deleteObject(artifact.get("object_key").toString());
                database.update("UPDATE artifacts SET state='REMOVED' WHERE id=? AND state='STAGED'",artifact.get("id"));
            }catch(RuntimeException unavailable){ /* Retried independently without reopening the execution attempt. */ }
        }
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=10000,initialDelay=10000)
    public void cleanupExitedAttempts() {
        var tasks=database.queryForList("SELECT DISTINCT r.task_id FROM artifacts r JOIN task_attempts a ON a.id=r.attempt_id WHERE r.state='STAGED' AND a.status IN ('FAILED','EXPIRED','CANCELLED') LIMIT 20");
        for(var task:tasks)cleanupTask((UUID)task.get("task_id"));
    }
    private long integer(JsonNode value) {
        if(!value.isIntegralNumber() || !value.canConvertToLong())throw new ResponseStatusException(CONFLICT,"产物大小和时长必须是整数");
        return value.asLong();
    }
}
