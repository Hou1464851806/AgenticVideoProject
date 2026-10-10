package com.agenticvideo.business.upload;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@Service
public class UploadService {
    private static final long PART_BYTES = 8 * 1024 * 1024;
    private final JdbcTemplate database;
    private final TransactionTemplate transactions;
    private final MultipartStorage storage;
    public UploadService(JdbcTemplate database, PlatformTransactionManager manager, MultipartStorage storage) {
        this.database = database; this.transactions = new TransactionTemplate(manager); this.storage = storage;
    }
    public Object create(UUID projectId, UUID actorId, UploadController.CreateUpload request) {
        UUID id = transactions.execute(status -> {
            database.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", UUID.class, actorId);
            var previous = database.query("SELECT id FROM upload_sessions WHERE actor_user_id = ? AND idempotency_key = ?",
                    (row, index) -> row.getObject("id", UUID.class), actorId, request.idempotencyKey());
            if (!previous.isEmpty()) {
                var existing = find(previous.get(0));
                if (!existing.projectId().equals(projectId) || !existing.filename().equals(request.filename())
                        || existing.bytes() != request.sizeBytes() || !existing.sha256().equalsIgnoreCase(request.sha256()))
                    throw new ResponseStatusException(CONFLICT, "幂等键对应不同上传");
                return existing.id();
            }
            database.queryForObject("SELECT id FROM projects WHERE id = ? FOR UPDATE", UUID.class, projectId);
            var reserved = database.queryForMap("SELECT coalesce(sum(bytes),0) AS bytes, coalesce(sum(asset_slots),0) AS slots FROM storage_reservations WHERE project_id = ? AND state <> 'RELEASED'", projectId);
            if (request.sizeBytes() > 2L * 1024 * 1024 * 1024 || ((Number) reserved.get("bytes")).longValue() + request.sizeBytes() > 5L * 1024 * 1024 * 1024 || ((Number) reserved.get("slots")).intValue() >= 10)
                throw new ResponseStatusException(CONFLICT, "超出素材数量或存储限额");
            UUID uploadId = UUID.randomUUID();
            database.update("INSERT INTO upload_sessions (id,project_id,actor_user_id,idempotency_key,filename,declared_bytes,declared_sha256,part_size_bytes,expected_parts,object_key,writer_token) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    uploadId, projectId, actorId, request.idempotencyKey(), request.filename(), request.sizeBytes(), request.sha256().toLowerCase(Locale.ROOT), PART_BYTES,
                    (int) ((request.sizeBytes() + PART_BYTES - 1) / PART_BYTES), "originals/" + uploadId, UUID.randomUUID());
            database.update("INSERT INTO storage_reservations (id,project_id,upload_id,bytes) VALUES (?,?,?,?)", UUID.randomUUID(), projectId, uploadId, request.sizeBytes());
            return uploadId;
        });
        var upload = find(id);
        if (upload.s3UploadId() == null) {
            UUID operation = UUID.randomUUID();
            int claimed = database.update("UPDATE upload_sessions SET operation_token=?, operation_expires_at=now()+interval '30 seconds' WHERE id=? AND s3_upload_id IS NULL AND (operation_token IS NULL OR operation_expires_at < now())", operation, id);
            if (claimed == 1) {
                try {
                    String s3Id = storage.initializeUpload(upload.key());
                    database.update("UPDATE upload_sessions SET s3_upload_id=?,status='UPLOADING',operation_token=NULL,operation_expires_at=NULL WHERE id=? AND operation_token=?", s3Id, id, operation);
                } finally {
                    database.update("UPDATE upload_sessions SET operation_token=NULL,operation_expires_at=NULL WHERE id=? AND operation_token=?", id, operation);
                }
            }
        }
        return view(find(id));
    }
    public Upload find(UUID id) {
        return database.query("SELECT * FROM upload_sessions WHERE id=?", (row, index) -> new Upload(id,
                row.getObject("project_id", UUID.class), row.getString("filename"), row.getLong("declared_bytes"), row.getString("declared_sha256"),
                row.getString("object_key"), row.getString("s3_upload_id"), row.getString("status"), row.getObject("writer_token", UUID.class), row.getInt("expected_parts")), id)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "上传不存在或无权访问"));
    }
    public Map<String, Object> view(Upload upload) {
        Map<String, Object> result = new LinkedHashMap<>(Map.of("uploadId", upload.id(), "filename", upload.filename(), "status", upload.status(), "reservedBytes", upload.bytes(),
                "partSizeBytes", PART_BYTES, "expectedParts", upload.parts(), "writerToken", upload.writerToken()));
        var completed = database.queryForMap("SELECT asset_id,validation_task_id,expires_at FROM upload_sessions WHERE id=?", upload.id());
        result.put("expiresAt", completed.get("expires_at").toString());
        if (completed.get("asset_id") != null) {
            result.put("assetId", completed.get("asset_id"));
            result.put("validationTaskId", completed.get("validation_task_id"));
            result.put("validationStatus", database.queryForObject("SELECT validation_status FROM assets WHERE id=?", String.class, completed.get("asset_id")));
        }
        return result;
    }
    public Object list(UUID projectId, UUID cursor) {
        var ids = database.query("SELECT id FROM upload_sessions WHERE project_id=? AND (?::uuid IS NULL OR id>?) ORDER BY id LIMIT 101",
                (row,index) -> row.getObject("id", UUID.class), projectId, cursor, cursor);
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("items", ids.stream().limit(100).map(id -> view(find(id))).toList());
        page.put("nextCursor", ids.size() > 100 ? ids.get(99) : null);
        return page;
    }
    private void requireWriter(Upload upload, UUID token) {
        if (!upload.writerToken().equals(token)) throw new ResponseStatusException(CONFLICT, "上传执行资格已失效");
    }
    public Object partUrls(Upload upload, UploadController.PartUrls request) {
        requireWriter(upload, request.writerToken());
        if (!"UPLOADING".equals(upload.status())) throw new ResponseStatusException(CONFLICT, "当前上传状态不能签发分片");
        if (request.partNumbers().stream().anyMatch(n -> n > upload.parts()) || new HashSet<>(request.partNumbers()).size() != request.partNumbers().size())
            throw new ResponseStatusException(BAD_REQUEST, "分片序号无效");
        return Map.of("parts", request.partNumbers().stream().map(number -> Map.of("partNumber", number,
                "url", storage.partUrl(upload.key(), upload.s3UploadId(), number), "expiresAt", java.time.Instant.now().plusSeconds(300).toString(),
                "sizeBytes", number == upload.parts() ? upload.bytes() - (number - 1L) * PART_BYTES : PART_BYTES,
                "headers", Map.of())).toList());
    }
    public Object recordPart(Upload upload, int number, UploadController.PartReport report) {
        requireWriter(upload, report.writerToken());
        if (!"UPLOADING".equals(upload.status())) throw new ResponseStatusException(CONFLICT, "当前状态不能登记分片");
        var part = storage.parts(upload.key(), upload.s3UploadId()).stream().filter(p -> p.partNumber() == number).findFirst()
                .orElseThrow(() -> new ResponseStatusException(CONFLICT, "对象存储中没有该分片"));
        long expected = number == upload.parts() ? upload.bytes() - (number-1L)*PART_BYTES : PART_BYTES;
        if (number < 1 || number > upload.parts() || part.size() != expected || report.sizeBytes() != expected || !part.eTag().equals(report.etag()))
            throw new ResponseStatusException(CONFLICT, "分片事实不匹配");
        database.update("INSERT INTO upload_parts (upload_id,part_number,etag,size_bytes) VALUES (?,?,?,?) ON CONFLICT (upload_id,part_number) DO UPDATE SET etag=excluded.etag,size_bytes=excluded.size_bytes,verified_at=now()", upload.id(), number, part.eTag(), part.size());
        database.update("UPDATE upload_sessions SET last_activity_at=now(),expires_at=now()+interval '24 hours' WHERE id=?", upload.id());
        return Map.of("partNumber", number, "sizeBytes", part.size());
    }
    public Object complete(Upload upload, UUID writer) {
        requireWriter(upload, writer);
        if ("COMPLETED".equals(upload.status())) return view(upload);
        UUID operation = UUID.randomUUID();
        int claimed = database.update("UPDATE upload_sessions SET status='COMPLETING', operation_token=?,operation_expires_at=now()+interval '30 seconds' WHERE id=? AND status IN ('UPLOADING','COMPLETING') AND (operation_token IS NULL OR operation_expires_at < now())", operation, upload.id());
        if (claimed == 0) return view(find(upload.id()));
        try {
            Long size = storage.objectSize(upload.key());
            if (size == null) {
                var parts = storage.parts(upload.key(), upload.s3UploadId());
                if (parts.size() != upload.parts()) throw new ResponseStatusException(CONFLICT, "分片不完整");
                long total = 0;
                for (int i=0; i<parts.size(); i++) {
                    var part = parts.get(i);
                    long expected = i == parts.size()-1 ? upload.bytes() - i * PART_BYTES : PART_BYTES;
                    if (part.partNumber() != i+1 || part.size() != expected) throw new ResponseStatusException(CONFLICT, "分片大小或序号不匹配");
                    total += part.size();
                    database.update("INSERT INTO upload_parts (upload_id,part_number,etag,size_bytes) VALUES (?,?,?,?) ON CONFLICT (upload_id,part_number) DO UPDATE SET etag=excluded.etag,size_bytes=excluded.size_bytes,verified_at=now()",
                            upload.id(), part.partNumber(), part.eTag(), part.size());
                }
                if (total != upload.bytes()) throw new ResponseStatusException(CONFLICT, "文件大小不匹配");
                try { storage.complete(upload.key(), upload.s3UploadId(), parts); }
                catch (software.amazon.awssdk.core.exception.SdkException uncertain) {
                    if (storage.objectSize(upload.key()) == null) throw uncertain;
                }
                size = storage.objectSize(upload.key());
            }
            if (size == null || size != upload.bytes()) throw new ResponseStatusException(CONFLICT, "对象大小不匹配");
            transactions.executeWithoutResult(tx -> {
                var locked = database.queryForMap("SELECT * FROM upload_sessions WHERE id=? FOR UPDATE", upload.id());
                if (!operation.equals(locked.get("operation_token"))) throw new ResponseStatusException(CONFLICT, "完成资格已失效");
                UUID assetId = UUID.randomUUID();
                UUID taskId = UUID.randomUUID();
                database.update("INSERT INTO assets (id,project_id,upload_id,object_key,filename,byte_size,sha256) VALUES (?,?,?,?,?,?,?)", assetId, upload.projectId(), upload.id(), upload.key(), upload.filename(), upload.bytes(), upload.sha256());
                database.update("INSERT INTO tasks (id,project_id,type,input_json) VALUES (?,?,'MEDIA_PREPARE',jsonb_build_object('assetId',?::text,'uploadId',?::text,'objectKey',?::text,'sizeBytes',?::bigint,'sha256',?::text))", taskId, upload.projectId(), assetId, upload.id(), upload.key(), upload.bytes(), upload.sha256());
                UUID eventId = UUID.randomUUID();
                database.update("INSERT INTO outbox_events (id,task_id,type,payload_json) VALUES (?,?,'MEDIA_PREPARE',jsonb_build_object('schemaVersion','1.0','eventId',?::text,'taskId',?::text,'taskType','MEDIA_PREPARE','projectId',?::text,'occurredAt',now()))", eventId, taskId, eventId, taskId, upload.projectId());
                database.update("UPDATE storage_reservations SET state='ALLOCATED' WHERE upload_id=?", upload.id());
                database.update("UPDATE upload_sessions SET status='COMPLETED',asset_id=?,validation_task_id=?,operation_token=NULL,operation_expires_at=NULL WHERE id=?", assetId, taskId, upload.id());
            });
        } catch (ResponseStatusException invalid) {
            if (storage.objectSize(upload.key()) == null) {
                database.update("UPDATE upload_sessions SET status='UPLOADING' WHERE id=? AND operation_token=?", upload.id(), operation);
            }
            throw invalid;
        } finally {
            database.update("UPDATE upload_sessions SET operation_token=NULL,operation_expires_at=NULL WHERE id=? AND operation_token=?", upload.id(), operation);
        }
        return view(find(upload.id()));
    }
    public Object assets(UUID projectId) {
        return Map.of("items", database.query("SELECT id,filename,validation_status FROM assets WHERE project_id=? ORDER BY id LIMIT 100", (row,index) -> Map.of("id", row.getObject("id"), "filename", row.getString("filename"), "validationStatus", row.getString("validation_status")), projectId));
    }
    public record Upload(UUID id, UUID projectId, String filename, long bytes, String sha256, String key,
            String s3UploadId, String status, UUID writerToken, int parts) {}
}
