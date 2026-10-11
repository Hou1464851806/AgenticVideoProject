package com.agenticvideo.business.upload;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.CONFLICT;

/** Durable revocation precedes external cleanup; storage calls never hold database locks. */
@Service
@org.springframework.scheduling.annotation.EnableScheduling
public class UploadCleanup {
    private final JdbcTemplate database;
    private final TransactionTemplate transactions;
    private final MultipartStorage storage;
    private final long settleSeconds;
    public UploadCleanup(JdbcTemplate database, PlatformTransactionManager manager, MultipartStorage storage,
            @Value("${upload.cleanup-settle-seconds:30}") long settleSeconds) {
        if(settleSeconds<1)throw new IllegalArgumentException("Invalid cleanup settle interval");
        this.database=database;this.transactions=new TransactionTemplate(manager);this.storage=storage;this.settleSeconds=settleSeconds;
    }
    public void cancel(UUID id) {
        transactions.executeWithoutResult(tx->{
            var row=database.queryForMap("SELECT * FROM upload_sessions WHERE id=? FOR UPDATE",id);
            String status=row.get("status").toString();
            if("COMPLETED".equals(status))throw new ResponseStatusException(CONFLICT,"上传已完成，不能取消已发布素材");
            if(java.util.List.of("CANCELLED","EXPIRED").contains(status))return;
            revoke(id,"CANCELLED");
        });
    }
    private void revoke(UUID id,String status) {
        database.update("""
                INSERT INTO upload_cleanup_jobs(upload_id,not_before)
                SELECT id,greatest(now(),coalesce(last_signed_url_expires_at,now()),coalesce(operation_expires_at,now()))+?*interval '1 second'
                FROM upload_sessions WHERE id=? ON CONFLICT(upload_id) DO NOTHING
                """,settleSeconds,id);
        database.update("UPDATE upload_sessions SET status=?,writer_token=?,writer_lease_expires_at=now(),operation_token=NULL,operation_expires_at=NULL WHERE id=?",status,UUID.randomUUID(),id);
        database.update("UPDATE storage_reservations SET asset_slots=0 WHERE upload_id=?",id);
    }
    @Scheduled(fixedDelayString="${upload.cleanup-interval-ms:10000}")
    public void reconcile() {
        transactions.executeWithoutResult(tx->{
            var expired=database.queryForList("SELECT id FROM upload_sessions WHERE status IN ('INITIALIZING','UPLOADING','PAUSED','COMPLETING') AND expires_at<=now() AND NOT(status='COMPLETING' AND operation_token IS NOT NULL AND operation_expires_at>now()) ORDER BY expires_at FOR UPDATE SKIP LOCKED LIMIT 20",UUID.class);
            for(UUID id:expired)revoke(id,"EXPIRED");
        });
        var ids=database.queryForList("SELECT upload_id FROM upload_cleanup_jobs WHERE next_attempt_at<=now() AND (lease_token IS NULL OR lease_expires_at<now()) ORDER BY next_attempt_at LIMIT 20",UUID.class);
        for(UUID id:ids)clean(id);
    }
    private void clean(UUID id) {
        UUID lease=UUID.randomUUID();
        int claimed=database.update("UPDATE upload_cleanup_jobs SET lease_token=?,lease_expires_at=now()+interval '120 seconds' WHERE upload_id=? AND (lease_token IS NULL OR lease_expires_at<now())",lease,id);
        if(claimed!=1)return;
        try {
            var row=database.queryForMap("SELECT object_key,status,declared_bytes FROM upload_sessions WHERE id=?",id);
            if(!java.util.List.of("CANCELLED","EXPIRED").contains(row.get("status").toString()))return;
            String key=row.get("object_key").toString();
            long physical=storage.uploadBytes(key);
            if(physical>0)transactions.executeWithoutResult(tx->{
                int reopened=database.update("UPDATE upload_cleanup_jobs SET status='PENDING',cleaned_at=NULL WHERE upload_id=? AND lease_token=?",id,lease);
                if(reopened==1)database.update("UPDATE storage_reservations SET bytes=?,state='RESERVED' WHERE upload_id=?",Math.max(physical,((Number)row.get("declared_bytes")).longValue()),id);
            });
            boolean empty=storage.cleanUpload(key);
            if(empty)transactions.executeWithoutResult(tx->{
                var current=database.queryForMap("SELECT status FROM upload_sessions WHERE id=? FOR UPDATE",id);
                if(!java.util.List.of("CANCELLED","EXPIRED").contains(current.get("status").toString()))return;
                int finished=database.update("UPDATE upload_cleanup_jobs SET status='CLEANED',cleaned_at=now() WHERE upload_id=? AND lease_token=? AND not_before<=now()",id,lease);
                if(finished==1)database.update("UPDATE storage_reservations SET bytes=0,state='RELEASED' WHERE upload_id=?",id);
            });
        } catch(software.amazon.awssdk.core.exception.SdkException unavailable) {
            // Keep bytes reserved until a later successful, post-drain verification.
        } finally {
            database.update("UPDATE upload_cleanup_jobs SET lease_token=NULL,lease_expires_at=NULL,next_attempt_at=now()+interval '1 second' WHERE upload_id=? AND lease_token=?",id,lease);
        }
    }
}
