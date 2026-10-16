package com.agenticvideo.business.render;

import com.agenticvideo.business.upload.MultipartStorage;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/** Explicit deletion is durable before contacting storage; download has no deletion side effect. */
@Service
public class ExportArtifacts {
    private final RenderArtifacts artifacts;
    private final MultipartStorage storage;
    private final JdbcTemplate database;
    private final TransactionTemplate transaction;
    public ExportArtifacts(RenderArtifacts artifacts, MultipartStorage storage, JdbcTemplate database, PlatformTransactionManager manager) {
        this.artifacts=artifacts;this.storage=storage;this.database=database;this.transaction=new TransactionTemplate(manager);
    }
    private Map<String,Object> exported(UUID id) {
        var row=artifacts.row(id);
        if(!"EXPORT".equals(row.get("kind")))throw new ResponseStatusException(CONFLICT,"此操作仅适用于正式成片");
        return row;
    }
    public Object read(UUID id) {
        var row=exported(id);
        Long bytes="REMOVED".equals(row.get("state"))?Long.valueOf(0):storage.objectSize(row.get("object_key").toString());
        return Map.of("artifactId",id,"state",row.get("state"),"storedBytes",bytes==null?0L:bytes);
    }
    public Object delete(UUID id, UUID actor, JsonNode body) {
        if(body==null || !body.isObject() || body.size()!=1 || !"DELETE".equals(body.path("explicitIntent").asText()))
            throw new ResponseStatusException(BAD_REQUEST,"需要明确删除成片文件");
        transaction.executeWithoutResult(status -> {
            database.queryForList("SELECT id FROM artifacts WHERE id=? FOR UPDATE",id);
            var row=exported(id);
            if(!java.util.Set.of("AVAILABLE","UNAVAILABLE","DELETE_PENDING","REMOVED").contains(row.get("state")))
                throw new ResponseStatusException(CONFLICT,"成片尚未完成");
            database.update("INSERT INTO artifact_deletion_requests(artifact_id,actor_user_id) VALUES (?,?) ON CONFLICT(artifact_id) DO NOTHING",id,actor);
            database.update("UPDATE artifacts SET state='DELETE_PENDING' WHERE id=? AND state IN ('AVAILABLE','UNAVAILABLE')",id);
        });
        remove(id);
        var row=exported(id);
        return Map.of("artifactId",id,"state",row.get("state"),"removedBytes","REMOVED".equals(row.get("state"))?database.queryForObject("SELECT coalesce(observed_bytes,0) FROM artifact_deletion_requests WHERE artifact_id=?",Long.class,id):0L);
    }
    private void remove(UUID id) {
        var row=exported(id);
        if(!"DELETE_PENDING".equals(row.get("state")))return;
        try {
            Long before=storage.objectSize(row.get("object_key").toString());
            database.update("UPDATE artifact_deletion_requests SET observed_bytes=coalesce(observed_bytes,?) WHERE artifact_id=?",before==null?0L:before,id);
            storage.deleteObject(row.get("object_key").toString());
            if(storage.objectSize(row.get("object_key").toString())!=null)return;
            transaction.executeWithoutResult(status -> {
                database.update("UPDATE artifacts SET state='REMOVED' WHERE id=? AND state='DELETE_PENDING'",id);
                database.update("UPDATE artifact_deletion_requests SET removed_at=now() WHERE artifact_id=? AND removed_at IS NULL",id);
            });
        }catch(RuntimeException unavailable){ /* Durable pending request is retried by the independent sweep. */ }
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=10000,initialDelay=10000)
    public void retry() {
        for(var row:database.queryForList("SELECT artifact_id FROM artifact_deletion_requests WHERE removed_at IS NULL ORDER BY requested_at LIMIT 20"))remove((UUID)row.get("artifact_id"));
    }
}
