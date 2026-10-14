package com.agenticvideo.business.conversation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Recovery logs are expendable; authoritative messages and results are not. */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="conversation.events.cleanup-enabled",havingValue="true")
class PublicEventRetention {
    private final JdbcTemplate database;
    private final TransactionTemplate transactions;
    private final long eventSeconds,draftSeconds;
    PublicEventRetention(JdbcTemplate database,PlatformTransactionManager manager,
            @Value("${conversation.events.retention-seconds:604800}") long eventSeconds,
            @Value("${conversation.drafts.retention-seconds:604800}") long draftSeconds) {
        if(eventSeconds<1 || draftSeconds<1)throw new IllegalArgumentException("公开日志保留期限必须为正数");
        this.database=database;this.transactions=new TransactionTemplate(manager);this.eventSeconds=eventSeconds;this.draftSeconds=draftSeconds;
    }
    @Scheduled(fixedDelayString="${conversation.events.cleanup-ms:60000}",initialDelayString="${conversation.events.cleanup-ms:60000}")
    void clean(){
        var projects=database.queryForList("SELECT DISTINCT project_id FROM public_events WHERE occurred_at<now()-?*interval '1 second' LIMIT 100",eventSeconds);
        for(var project:projects)transactions.executeWithoutResult(tx->{
            // The counter lock makes cleanup atomic with publication and cursor validation.
            database.queryForList("SELECT project_id FROM project_event_counters WHERE project_id=? FOR UPDATE",project.get("project_id"));
            database.update("WITH removed AS (DELETE FROM public_events WHERE project_id=? AND occurred_at<now()-?*interval '1 second' RETURNING event_seq) UPDATE project_event_counters SET retained_after=greatest(retained_after,coalesce((SELECT max(event_seq) FROM removed),retained_after)) WHERE project_id=?",project.get("project_id"),eventSeconds,project.get("project_id"));
        });
        database.update("DELETE FROM assistant_drafts d USING task_attempts a,tasks t WHERE d.attempt_id=a.id AND d.task_id=t.id AND a.heartbeat_at<now()-?*interval '1 second' AND (t.status IN ('FAILED','CANCELLED','SUCCEEDED') OR t.current_attempt_id IS DISTINCT FROM d.attempt_id)",draftSeconds);
    }
}
