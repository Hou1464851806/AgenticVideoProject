package com.agenticvideo.business.conversation;

import com.agenticvideo.business.project.ProjectAccess;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Committed database events are authoritative; disconnect only releases a subscription. */
@Service
public class ProjectEventStream {
    private final JdbcTemplate database;
    private final ProjectAccess access;
    private final ObjectMapper json;
    private final org.springframework.transaction.support.TransactionTemplate snapshots;
    private final java.util.concurrent.ScheduledThreadPoolExecutor polling;
    private final java.util.concurrent.Semaphore slots = new java.util.concurrent.Semaphore(128);

    public ProjectEventStream(JdbcTemplate database, ProjectAccess access, ObjectMapper json, org.springframework.transaction.PlatformTransactionManager manager) {
        this.database = database; this.access = access; this.json = json;
        snapshots=new org.springframework.transaction.support.TransactionTemplate(manager);
        snapshots.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshots.setReadOnly(true);
        polling = (java.util.concurrent.ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "project-event-stream"); thread.setDaemon(true); return thread;
        });
        polling.setRemoveOnCancelPolicy(true);
    }

    public SseEmitter subscribe(UUID project, String cursor, Authentication account, HttpSession session) {
        access.requireOwned(project, account);
        long after = decode(project, cursor);
        requireRetained(project, after);
        if (!slots.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "事件订阅已满，请稍后重试");
        SseEmitter emitter = new SseEmitter(300_000L);
        AtomicReference<ScheduledFuture<?>> future = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable cleanup = () -> {
            if (closed.compareAndSet(false, true)) {
                slots.release();
                var job = future.get(); if (job != null) job.cancel(false);
            }
        };
        emitter.onCompletion(cleanup); emitter.onTimeout(cleanup); emitter.onError(failure -> cleanup.run());
        final long[] position = {after}, heartbeat = {0};
        Runnable read = () -> {
            if (closed.get()) return;
            try {
                if (!authenticated(session)) {
                    emitter.send(SseEmitter.event().name("stream.auth-required").data("{}"));
                    cleanup.run(); emitter.complete(); return;
                }
                access.requireOwned(project, account);
                java.util.List<java.util.Map<String,Object>> events;
                try {
                    events=snapshots.execute(tx->{
                        requireRetained(project,position[0]);
                        return database.queryForList("SELECT event_seq,type,body FROM public_events WHERE project_id=? AND event_seq>? ORDER BY event_seq LIMIT 100",project,position[0]);
                    });
                }catch(ResponseStatusException expired) {
                    emitter.send(SseEmitter.event().name("stream.reset").data("{}"));
                    cleanup.run();emitter.complete();return;
                }
                for (var event : events) {
                    long sequence = ((Number) event.get("event_seq")).longValue();
                    emitter.send(SseEmitter.event().id(project + ":" + sequence).name(event.get("type").toString()).data(json.readTree(event.get("body").toString())));
                    position[0] = sequence;
                }
                long now = System.currentTimeMillis();
                if (now - heartbeat[0] >= 15_000) { emitter.send(SseEmitter.event().comment("heartbeat")); heartbeat[0] = now; }
            } catch (Exception disconnected) { cleanup.run(); emitter.complete(); }
        };
        future.set(polling.scheduleWithFixedDelay(read, 0, 500, TimeUnit.MILLISECONDS));
        if (closed.get()) future.get().cancel(false);
        return emitter;
    }

    private boolean authenticated(HttpSession session) {
        if (session == null) return false;
        try {
            int timeout = session.getMaxInactiveInterval();
            return session.getAttribute("SPRING_SECURITY_CONTEXT") != null
                    && (timeout <= 0 || System.currentTimeMillis() - session.getLastAccessedTime() < timeout * 1000L);
        } catch (IllegalStateException expired) { return false; }
    }

    private long decode(UUID project, String cursor) {
        try {
            String[] parts = cursor.split(":", -1);
            if (parts.length != 2 || !UUID.fromString(parts[0]).equals(project)) throw new IllegalArgumentException();
            long sequence = Long.parseLong(parts[1]); if (sequence < 0) throw new IllegalArgumentException(); return sequence;
        } catch (RuntimeException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "事件游标无效"); }
    }

    private void requireRetained(UUID project, long after) {
        var counters = database.queryForList("SELECT event_seq,retained_after FROM project_event_counters WHERE project_id=?", project);
        long latest = counters.isEmpty() ? 0 : ((Number) counters.get(0).get("event_seq")).longValue();
        long retained = counters.isEmpty() ? 0 : ((Number) counters.get(0).get("retained_after")).longValue();
        if (after < retained) throw new ResponseStatusException(HttpStatus.GONE, "事件游标已过期，请重新读取快照");
        if (after > latest) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "事件游标超出项目进度");
    }

    @PreDestroy void close() { polling.shutdownNow(); }
}
