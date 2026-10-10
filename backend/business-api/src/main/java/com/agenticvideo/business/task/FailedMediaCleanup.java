package com.agenticvideo.business.task;

import com.agenticvideo.business.upload.MultipartStorage;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name="tasks.dispatch-enabled",havingValue="true")
class FailedMediaCleanup {
    private final JdbcTemplate database;
    private final MultipartStorage storage;
    private final TransactionTemplate transactions;
    FailedMediaCleanup(JdbcTemplate database,MultipartStorage storage,PlatformTransactionManager manager) { this.database=database;this.storage=storage;transactions=new TransactionTemplate(manager); }
    @Scheduled(fixedDelay=1000,initialDelay=1000)
    public void clean() {
        UUID claim=UUID.randomUUID();
        var jobs=database.queryForList("""
                WITH candidates AS (
                    SELECT j.id FROM object_cleanup_jobs j JOIN assets a ON a.id=j.asset_id
                    WHERE a.validation_status='INVALID' AND j.available_at<=now()
                    AND (j.status='PENDING' OR j.status='CLEANING' AND j.lease_expires_at<now())
                    FOR UPDATE OF j SKIP LOCKED LIMIT 5
                )
                UPDATE object_cleanup_jobs j SET status='CLEANING',claim_id=?,lease_expires_at=now()+interval '60 seconds'
                FROM candidates c WHERE j.id=c.id RETURNING j.*
                """,claim);
        for(var job:jobs) {
            try {
                String key=job.get("object_key").toString();
                storage.deleteObject(key);
                if(storage.objectSize(key)!=null) throw new IllegalStateException("Object cleanup unconfirmed");
                transactions.executeWithoutResult(tx -> {
                    int committed=database.update("UPDATE object_cleanup_jobs SET status='CLEANED',completed_at=now(),claim_id=NULL,lease_expires_at=NULL WHERE id=? AND claim_id=?",job.get("id"),claim);
                    if(committed==1) database.update("UPDATE storage_reservations SET state='RELEASED' WHERE upload_id=(SELECT upload_id FROM assets WHERE id=?)",job.get("asset_id"));
                });
            } catch(RuntimeException unavailable) {
                database.update("UPDATE object_cleanup_jobs SET status='PENDING',available_at=now()+interval '5 seconds',claim_id=NULL,lease_expires_at=NULL WHERE id=? AND claim_id=?",job.get("id"),claim);
            }
        }
    }
}
