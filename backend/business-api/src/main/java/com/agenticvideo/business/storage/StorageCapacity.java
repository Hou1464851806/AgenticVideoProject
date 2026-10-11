package com.agenticvideo.business.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.CONFLICT;

/** Admission is serialized across projects; filesystem facts include already written objects. */
@Service
public class StorageCapacity {
    private final JdbcTemplate database;
    private final List<Path> paths;
    private final long margin;
    private final long maxReserved;
    public StorageCapacity(JdbcTemplate database,
            @Value("${capacity.paths:${java.io.tmpdir}}") String paths,
            @Value("${capacity.min-free-bytes:536870912}") long margin,
            @Value("${capacity.max-reserved-bytes:9223372036854775807}") long maxReserved) {
        if(margin<0 || maxReserved<1)throw new IllegalArgumentException("Invalid storage safety margin");
        this.database=database;this.paths=Arrays.stream(paths.split(",")).map(String::trim).map(Path::of).toList();this.margin=margin;this.maxReserved=maxReserved;
    }
    /** Two original copies cover multipart assembly/workspace; 300 MiB covers an hour of WAV copies and scratch. */
    public long uploadBudget(long bytes) { return Math.addExact(Math.multiplyExact(bytes,2),314572800L); }
    /** Raw RGB frames at 30 fps bound normalized clips; add PCM, two encoded copies and scratch. */
    public long renderBudget(com.fasterxml.jackson.databind.JsonNode input) {
        long sources=0;
        for(var source:input.path("sources"))sources=Math.addExact(sources,source.path("sizeBytes").asLong());
        var configuration=input.path("configuration");
        long rate=Math.addExact(Math.multiplyExact(configuration.path("width").asLong(),configuration.path("height").asLong())*90/1000,
                Math.addExact(384,configuration.path("videoMaxRate").asLong()/4000));
        return Math.addExact(sources,Math.addExact(67108864,Math.multiplyExact(input.path("output").path("durationMs").asLong(),rate)));
    }
    /** Caller must hold a database transaction until its reservation has been inserted. */
    public void require(long additionalBytes) {
        database.queryForObject("SELECT pg_advisory_xact_lock(8072026)",Object.class);
        long free=Long.MAX_VALUE;
        try {
            for(Path path:paths)free=Math.min(free,Files.getFileStore(path).getUsableSpace());
        } catch(IOException unavailable) {
            throw new SpaceUnavailable("无法核对存储卷可用空间，请检查存储卷挂载后重试");
        }
        long pending=database.queryForObject("""
                SELECT coalesce(sum(r.future_bytes),0) FROM storage_reservations r
                JOIN upload_sessions u ON u.id=r.upload_id
                WHERE r.state='RESERVED' OR r.state='ALLOCATED' AND (
                    EXISTS (SELECT 1 FROM tasks t WHERE t.id=u.validation_task_id AND t.status IN ('PENDING','RUNNING','CANCEL_REQUESTED'))
                    OR EXISTS (SELECT 1 FROM tasks t WHERE t.type='CONTENT_ANALYZE' AND t.input_json->>'assetId'=u.asset_id::text AND t.status IN ('PENDING','RUNNING','CANCEL_REQUESTED')))
                """,Long.class);
        pending=Math.addExact(pending,database.queryForObject("SELECT coalesce(sum(disk_budget_bytes),0) FROM tasks WHERE status IN ('PENDING','RUNNING','CANCEL_REQUESTED')",Long.class));
        if(additionalBytes<0 || pending>maxReserved || additionalBytes>maxReserved-pending || free<margin || pending>free-margin || additionalBytes>free-margin-pending)
            throw new SpaceUnavailable("对象存储或工作区可用空间不足，请清理不需要的文件或扩容后重试");
    }
    public static final class SpaceUnavailable extends ResponseStatusException {
        public SpaceUnavailable(String reason) { super(CONFLICT,reason); }
    }

}
