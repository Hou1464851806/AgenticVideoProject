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
    private final UploadAdmission admission;
    private final UploadCleanup cleanup;
    private final com.agenticvideo.business.storage.StorageCapacity capacity;
    private final long inactivitySeconds;
    private final long maxFileBytes;
    private final long maxProjectBytes;
    private final int maxProjectAssets;
    public UploadService(JdbcTemplate database, PlatformTransactionManager manager, MultipartStorage storage, UploadCleanup cleanup, UploadAdmission admission, com.agenticvideo.business.storage.StorageCapacity capacity,
            @org.springframework.beans.factory.annotation.Value("${upload.inactivity-seconds:86400}") long inactivitySeconds,
            @org.springframework.beans.factory.annotation.Value("${upload.max-file-bytes:2147483648}") long maxFileBytes,
            @org.springframework.beans.factory.annotation.Value("${upload.max-project-bytes:5368709120}") long maxProjectBytes,
            @org.springframework.beans.factory.annotation.Value("${upload.max-project-assets:10}") int maxProjectAssets) {
        if(inactivitySeconds<1)throw new IllegalArgumentException("Invalid upload inactivity lifetime");
        if(maxFileBytes<1 || maxFileBytes>PART_BYTES*10000 || maxProjectBytes<1 || maxProjectAssets<1)
            throw new IllegalArgumentException("Invalid upload limits");
        this.capacity=capacity;this.cleanup=cleanup;this.admission=admission;
        this.maxFileBytes=maxFileBytes;this.maxProjectBytes=maxProjectBytes;this.maxProjectAssets=maxProjectAssets;
        this.database = database; this.transactions = new TransactionTemplate(manager); this.storage = storage;this.inactivitySeconds=inactivitySeconds;
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
                if (!request.writerId().equals(database.queryForObject("SELECT writer_owner FROM upload_sessions WHERE id=?", UUID.class, existing.id())))
                    throw new ResponseStatusException(CONFLICT, "上传由另一个页面控制，不能取得写入资格");
                return existing.id();
            }
            database.queryForObject("SELECT id FROM projects WHERE id = ? FOR UPDATE", UUID.class, projectId);
            var reserved = database.queryForMap("SELECT coalesce(sum(bytes),0) AS bytes, coalesce(sum(asset_slots),0) AS slots FROM storage_reservations WHERE project_id = ? AND state <> 'RELEASED'", projectId);
            if (request.sizeBytes() > maxFileBytes)
                throw new ResponseStatusException(CONFLICT, "单文件大小超过上限 " + maxFileBytes + " 字节，请压缩或拆分视频后重试");
            if (((Number) reserved.get("bytes")).longValue() > maxProjectBytes - request.sizeBytes())
                throw new ResponseStatusException(CONFLICT, "项目原始素材总量超过上限 " + maxProjectBytes + " 字节，请取消未完成上传、删除不需要的素材或新建项目");
            if (((Number) reserved.get("slots")).longValue() >= maxProjectAssets)
                throw new ResponseStatusException(CONFLICT, "项目视频数量达到上限 " + maxProjectAssets + "，请取消未完成上传、删除不需要的素材或新建项目");
            admission.require(actorId);
            capacity.require(capacity.uploadBudget(request.sizeBytes()));
            UUID uploadId = UUID.randomUUID();
            database.update("INSERT INTO upload_sessions (id,project_id,actor_user_id,idempotency_key,filename,declared_bytes,declared_sha256,part_size_bytes,expected_parts,object_key,writer_token,writer_owner) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    uploadId, projectId, actorId, request.idempotencyKey(), request.filename(), request.sizeBytes(), request.sha256().toLowerCase(Locale.ROOT), PART_BYTES,
                    (int) ((request.sizeBytes() + PART_BYTES - 1) / PART_BYTES), "originals/" + uploadId, UUID.randomUUID(),
                    request.writerId());
            database.update("INSERT INTO storage_reservations (id,project_id,upload_id,bytes,future_bytes) VALUES (?,?,?,?,?)", UUID.randomUUID(), projectId, uploadId, request.sizeBytes(),capacity.uploadBudget(request.sizeBytes()));
            database.update("UPDATE upload_sessions SET expires_at=now()+?*interval '1 second' WHERE id=?",inactivitySeconds,uploadId);
            return uploadId;
        });
        var upload = find(id);
        if (upload.s3UploadId() == null) {
            UUID operation = UUID.randomUUID();
            int claimed = database.update("UPDATE upload_sessions SET operation_token=?, operation_expires_at=now()+interval '30 seconds' WHERE id=? AND status='INITIALIZING' AND expires_at>now() AND s3_upload_id IS NULL AND (operation_token IS NULL OR operation_expires_at < now())", operation, id);
            if (claimed == 1) {
                try {
                    String s3Id = storage.initializeUpload(upload.key());
                    database.update("UPDATE upload_sessions SET s3_upload_id=?,status='UPLOADING',operation_token=NULL,operation_expires_at=NULL WHERE id=? AND operation_token=? AND status='INITIALIZING' AND expires_at>now()", s3Id, id, operation);
                } finally {
                    database.update("UPDATE upload_sessions SET operation_token=NULL,operation_expires_at=NULL WHERE id=? AND operation_token=?", id, operation);
                }
            }
        }
        var result = view(find(id));
        result.put("writerToken", find(id).writerToken());
        return result;
    }
    public Upload find(UUID id) {
        return database.query("SELECT * FROM upload_sessions WHERE id=?", (row, index) -> new Upload(id,
                row.getObject("project_id", UUID.class), row.getString("filename"), row.getLong("declared_bytes"), row.getString("declared_sha256"),
                row.getString("object_key"), row.getString("s3_upload_id"), row.getString("status"), row.getObject("writer_token", UUID.class), row.getInt("expected_parts")), id)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "上传不存在或无权访问"));
    }
    public Map<String, Object> view(Upload upload) {
        Map<String, Object> result = new LinkedHashMap<>(Map.of("uploadId", upload.id(), "filename", upload.filename(), "status", upload.status(), "reservedBytes", upload.bytes(),
                "partSizeBytes", PART_BYTES, "expectedParts", upload.parts()));
        var completed = database.queryForMap("SELECT asset_id,validation_task_id,expires_at FROM upload_sessions WHERE id=?", upload.id());
        // One MVCC snapshot: cleanup can release/reopen reservations between HTTP reads.
        // Separate queries could report CLEANED together with the preceding reserved bytes.
        var reservation=database.queryForMap("SELECT r.bytes,r.asset_slots,j.status AS cleanup_status FROM storage_reservations r LEFT JOIN upload_cleanup_jobs j ON j.upload_id=r.upload_id WHERE r.upload_id=?",upload.id());
        result.put("declaredBytes",upload.bytes());
        result.put("reservedBytes",reservation.get("bytes"));
        result.put("reservedSlots",reservation.get("asset_slots"));
        if(reservation.get("cleanup_status")!=null)result.put("cleanupStatus",reservation.get("cleanup_status"));
        result.put("expiresAt", ((java.sql.Timestamp) completed.get("expires_at")).toInstant().toString());
        result.put("confirmedBytes", database.queryForObject("SELECT coalesce(sum(size_bytes),0) FROM upload_parts WHERE upload_id=?", Long.class, upload.id()));
        if (completed.get("asset_id") != null) {
            result.put("assetId", completed.get("asset_id"));
            result.put("validationTaskId", completed.get("validation_task_id"));
            var asset=database.queryForMap("SELECT validation_status,error_json FROM assets WHERE id=?",completed.get("asset_id"));
            result.put("validationStatus",asset.get("validation_status"));
            if(asset.get("error_json")!=null) {
                try { result.put("error",new com.fasterxml.jackson.databind.ObjectMapper().readTree(asset.get("error_json").toString())); }
                catch(java.io.IOException invalid) { throw new IllegalStateException(invalid); }
            }
            var cleanup=database.queryForList("SELECT status FROM object_cleanup_jobs WHERE asset_id=?",completed.get("asset_id"));
            if(!cleanup.isEmpty()) result.put("cleanupStatus",cleanup.get(0).get("status"));
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
        Integer eligible=database.queryForObject("SELECT count(*) FROM upload_sessions WHERE id=? AND writer_token=? AND status NOT IN ('CANCELLED','EXPIRED') AND (status='COMPLETED' OR (writer_lease_expires_at>now() AND expires_at>now()))",Integer.class,upload.id(),token);
        if(eligible!=1)throw new ResponseStatusException(CONFLICT,"上传执行资格已失效，请重新核对原文件");
    }
    private void renewActivity(UUID id) {
        database.update("UPDATE upload_sessions SET writer_lease_expires_at=now()+interval '330 seconds',last_activity_at=now(),expires_at=CASE WHEN status='COMPLETED' THEN expires_at ELSE now()+?*interval '1 second' END WHERE id=?",inactivitySeconds,id);
    }
    public Object resume(Upload upload, UploadController.Resume request) {
        return transactions.execute(tx -> {
            var locked = database.queryForMap("SELECT *,writer_lease_expires_at>now() AS live FROM upload_sessions WHERE id=? FOR UPDATE", upload.id());
            var current = find(upload.id());
            if (!List.of("UPLOADING", "PAUSED").contains(current.status()) || !((java.sql.Timestamp)locked.get("expires_at")).toInstant().isAfter(java.time.Instant.now()))
                throw new ResponseStatusException(CONFLICT,"上传已取消或过期，请重新上传");
            if (current.bytes() != request.sizeBytes() || !current.sha256().equalsIgnoreCase(request.sha256()))
                throw new ResponseStatusException(CONFLICT, "重选文件内容与原上传不一致，不能续接");
            {
                if (Boolean.TRUE.equals(locked.get("live")) && !request.writerId().equals(locked.get("writer_owner")))
                    throw new ResponseStatusException(CONFLICT, "另一个页面持有上传写入资格，请在原页面继续或等待资格到期");
                UUID token = Boolean.TRUE.equals(locked.get("live")) ? current.writerToken() : UUID.randomUUID();
                database.update("UPDATE upload_sessions SET writer_owner=?,writer_token=?,writer_lease_expires_at=now()+interval '330 seconds' WHERE id=?", request.writerId(), token, current.id());
                current = find(current.id());
            }
            if (!List.of("UPLOADING", "PAUSED").contains(current.status())) throw new ResponseStatusException(CONFLICT, "当前上传状态不能恢复");
            var parts = storage.parts(current.key(), current.s3UploadId());
            long confirmed = 0;
            for (var part : parts) {
                long expected = part.partNumber() == current.parts() ? current.bytes() - (part.partNumber()-1L)*PART_BYTES : PART_BYTES;
                if (part.partNumber() < 1 || part.partNumber() > current.parts() || part.size() != expected)
                    throw new ResponseStatusException(CONFLICT, "存储中的分片大小或序号不匹配");
                confirmed += part.size();
            }
            database.update("DELETE FROM upload_parts WHERE upload_id=?", current.id());
            for (var part : parts) database.update("INSERT INTO upload_parts (upload_id,part_number,etag,size_bytes) VALUES (?,?,?,?)",
                    current.id(), part.partNumber(), part.eTag(), part.size());
            database.update("UPDATE upload_sessions SET status='UPLOADING',last_activity_at=now(),expires_at=now()+?*interval '1 second' WHERE id=?", inactivitySeconds,current.id());
            var result = view(find(current.id()));
            result.put("writerToken", current.writerToken());
            result.put("confirmedBytes", confirmed);
            result.put("confirmedParts", parts.stream().map(part -> Map.of("partNumber", part.partNumber(), "sizeBytes", part.size(), "etag", part.eTag())).toList());
            return result;
        });
    }
    public Object pause(Upload upload, UUID writer) {
        return transactions.execute(tx -> {
            database.queryForMap("SELECT id FROM upload_sessions WHERE id=? FOR UPDATE", upload.id());
            var current = find(upload.id());
            requireWriter(current, writer);
            if (!List.of("UPLOADING", "PAUSED").contains(current.status())) throw new ResponseStatusException(CONFLICT, "当前上传状态不能暂停");
            renewActivity(upload.id());
            database.update("UPDATE upload_sessions SET status='PAUSED' WHERE id=?", upload.id());
            return view(find(upload.id()));
        });
    }
    public Object partUrls(Upload upload, UploadController.PartUrls request) {
        requireWriter(upload,request.writerToken());
        if(!"UPLOADING".equals(upload.status()))throw new ResponseStatusException(CONFLICT,"当前上传状态不能签发分片");
        rejectOversizedParts(upload,storage.parts(upload.key(),upload.s3UploadId()));
        return transactions.execute(tx->{
            database.queryForMap("SELECT id FROM upload_sessions WHERE id=? FOR UPDATE",upload.id());
            var current=find(upload.id());
            requireWriter(current, request.writerToken());
            if (!"UPLOADING".equals(current.status())) throw new ResponseStatusException(CONFLICT, "当前上传状态不能签发分片");
            if (request.partNumbers().stream().anyMatch(n -> n > current.parts()) || new HashSet<>(request.partNumbers()).size() != request.partNumbers().size())
                throw new ResponseStatusException(BAD_REQUEST, "分片序号无效");
            checkLimits(current);
            capacity.require(0);
            renewActivity(current.id());
            database.update("UPDATE upload_sessions SET last_signed_url_expires_at=greatest(coalesce(last_signed_url_expires_at,now()),now()+?*interval '1 second') WHERE id=?",storage.partUrlSeconds(),current.id());
            return Map.of("parts", request.partNumbers().stream().map(number -> Map.of("partNumber", number,
                    "url", storage.partUrl(current.key(), current.s3UploadId(), number), "expiresAt", java.time.Instant.now().plusSeconds(storage.partUrlSeconds()).toString(),
                    "sizeBytes", number == current.parts() ? current.bytes() - (number - 1L) * PART_BYTES : PART_BYTES,
                    "headers", Map.of())).toList());
        });
    }
    public Object recordPart(Upload upload, int number, UploadController.PartReport report) {
        requireWriter(upload, report.writerToken());
        if (!List.of("UPLOADING", "PAUSED").contains(upload.status())) throw new ResponseStatusException(CONFLICT, "当前状态不能登记分片");
        var storedParts=storage.parts(upload.key(), upload.s3UploadId());
        rejectOversizedParts(upload,storedParts);
        var part = storedParts.stream().filter(p -> p.partNumber() == number).findFirst()
                .orElseThrow(() -> new ResponseStatusException(CONFLICT, "对象存储中没有该分片"));
        long expected = number == upload.parts() ? upload.bytes() - (number-1L)*PART_BYTES : PART_BYTES;
        if (number < 1 || number > upload.parts() || part.size() != expected || report.sizeBytes() != expected || !part.eTag().equals(report.etag()))
            throw new ResponseStatusException(CONFLICT, "分片事实不匹配");
        return transactions.execute(tx->{
            database.queryForMap("SELECT id FROM upload_sessions WHERE id=? FOR UPDATE",upload.id());
            var current=find(upload.id());
            requireWriter(current,report.writerToken());
            if(!List.of("UPLOADING","PAUSED").contains(current.status()))throw new ResponseStatusException(CONFLICT,"当前状态不能登记分片");
            database.update("INSERT INTO upload_parts (upload_id,part_number,etag,size_bytes) VALUES (?,?,?,?) ON CONFLICT (upload_id,part_number) DO UPDATE SET etag=excluded.etag,size_bytes=excluded.size_bytes,verified_at=now()", current.id(), number, part.eTag(), part.size());
            renewActivity(current.id());
            return Map.of("partNumber", number, "sizeBytes", part.size());
        });
    }
    public Object complete(Upload upload, UUID writer) {
        requireWriter(upload, writer);
        if ("COMPLETED".equals(upload.status())) return view(upload);
        UUID operation = UUID.randomUUID();
        int claimed = transactions.execute(tx -> {
            database.queryForMap("SELECT id FROM upload_sessions WHERE id=? FOR UPDATE",upload.id());
            requireWriter(find(upload.id()),writer);
            checkLimits(upload);
            capacity.require(0);
            return database.update("UPDATE upload_sessions SET status='COMPLETING', operation_token=?,operation_expires_at=now()+interval '30 seconds' WHERE id=? AND expires_at>now() AND status IN ('UPLOADING','COMPLETING') AND (operation_token IS NULL OR operation_expires_at < now())", operation, upload.id());
        });
        if (claimed == 0) return view(find(upload.id()));
        try {
            Long size = storage.objectSize(upload.key());
            if (size == null) {
                var parts = storage.parts(upload.key(), upload.s3UploadId());
                rejectOversizedParts(upload,parts);
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
            if (size != null && size > upload.bytes()) {
                cleanup.cancel(upload.id());
                throw new ResponseStatusException(CONFLICT,"实际文件超过声明大小，上传已取消并安排残留清理，请核对原文件后重新上传");
            }
            if (size == null || size != upload.bytes()) throw new ResponseStatusException(CONFLICT, "对象大小不匹配");
            transactions.executeWithoutResult(tx -> {
                var locked = database.queryForMap("SELECT * FROM upload_sessions WHERE id=? FOR UPDATE", upload.id());
                if (!operation.equals(locked.get("operation_token")) || !"COMPLETING".equals(locked.get("status"))
                        || !((java.sql.Timestamp)locked.get("operation_expires_at")).toInstant().isAfter(java.time.Instant.now()))
                    throw new ResponseStatusException(CONFLICT, "完成资格已失效");
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
    private void checkLimits(Upload upload) {
        if(upload.bytes()>maxFileBytes)throw new ResponseStatusException(CONFLICT,"单文件大小超过当前上限，请取消后压缩或拆分视频再上传");
        var reserved=database.queryForMap("SELECT coalesce(sum(bytes),0) AS bytes,coalesce(sum(asset_slots),0) AS slots FROM storage_reservations WHERE project_id=? AND state<>'RELEASED'",upload.projectId());
        if(((Number)reserved.get("bytes")).longValue()>maxProjectBytes || ((Number)reserved.get("slots")).longValue()>maxProjectAssets)
            throw new ResponseStatusException(CONFLICT,"项目素材预留超过当前限额，请取消不需要的上传或清理素材后重试");
    }
    private void rejectOversizedParts(Upload upload, List<software.amazon.awssdk.services.s3.model.Part> parts) {
        long total=0;
        for(var part:parts) {
            long expected=part.partNumber()==upload.parts()?upload.bytes()-(part.partNumber()-1L)*PART_BYTES:PART_BYTES;
            if(part.partNumber()<1 || part.partNumber()>upload.parts() || part.size()>expected || part.size()>upload.bytes()-total) {
                cleanup.cancel(upload.id());
                throw new ResponseStatusException(CONFLICT,"实际分片超过声明大小或分片规则，上传已取消并安排残留清理，请核对原文件后重新上传");
            }
            total+=part.size();
        }
    }
    public Object assets(UUID projectId, boolean includePending) {
        return Map.of("items", database.query("SELECT id,filename,validation_status FROM assets WHERE project_id=? AND (validation_status='VALID' OR ?) ORDER BY id LIMIT 100", (row,index) -> Map.of("id", row.getObject("id"), "filename", row.getString("filename"), "validationStatus", row.getString("validation_status")), projectId, includePending));
    }
    public record Upload(UUID id, UUID projectId, String filename, long bytes, String sha256, String key,
            String s3UploadId, String status, UUID writerToken, int parts) {}
}
