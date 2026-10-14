package com.agenticvideo.business.messaging;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="tasks.dispatch-enabled", havingValue="true")
class TaskDispatch {
    private final JdbcTemplate database;
    private final RabbitTemplate broker;
    private final int pendingRenotifySeconds;
    private final boolean analysisEnabled;
    TaskDispatch(JdbcTemplate database, RabbitTemplate broker,
            @Value("${tasks.pending-renotify-seconds:60}") int pendingRenotifySeconds,
            @Value("${analysis.execution-enabled:false}") boolean analysisEnabled) {
        this.database = database; this.broker = broker; this.analysisEnabled=analysisEnabled; this.pendingRenotifySeconds = Math.max(2, pendingRenotifySeconds);
    }
    @Bean Queue mediaQueue() { return new Queue("media.v1", true, false, false, java.util.Map.of("x-dead-letter-exchange", "", "x-dead-letter-routing-key", "media.dead.v1")); }

    @Bean Queue mediaDeadQueue() { return new Queue("media.dead.v1", true); }
    @Bean Queue analysisQueue() { return new Queue("analysis.v1",true,false,false,java.util.Map.of("x-dead-letter-exchange","","x-dead-letter-routing-key","analysis.dead.v1")); }
    @Bean Queue analysisDeadQueue() { return new Queue("analysis.dead.v1",true); }

    @Scheduled(fixedDelayString="${tasks.dispatch-interval-ms:1000}", initialDelay=500)
    public void publish() {
        // RabbitMQ is a wake-up channel; a successful publish does not prove a task was claimed.
        database.update("""
                UPDATE outbox_events e SET status='PENDING',available_at=now(),published_at=NULL
                FROM tasks t WHERE e.task_id=t.id AND t.status='PENDING' AND e.type IN ('MEDIA_PREPARE','CONTENT_ANALYZE','INDEX_BUILD','VISION_PREPARE','VISION_ANALYZE','PREVIEW_RENDER','EXPORT_RENDER')
                AND e.status='PUBLISHED' AND e.published_at < now()-make_interval(secs => ?)
                """, pendingRenotifySeconds);
        UUID claim = UUID.randomUUID();
        var events = database.queryForList("""
                WITH candidates AS (
                    SELECT e.id FROM outbox_events e JOIN tasks t ON t.id=e.task_id
                    WHERE e.type IN ('MEDIA_PREPARE','CONTENT_ANALYZE','INDEX_BUILD','VISION_PREPARE','VISION_ANALYZE','PREVIEW_RENDER','EXPORT_RENDER') AND (? OR e.type IN ('MEDIA_PREPARE','VISION_PREPARE','PREVIEW_RENDER','EXPORT_RENDER')) AND e.available_at<=now() AND t.status='PENDING' AND t.next_execution_at<=now()
                    AND (e.status='PENDING' OR e.status='PUBLISHING' AND e.lease_expires_at<now())
                    ORDER BY e.available_at FOR UPDATE OF e SKIP LOCKED LIMIT 10
                )
                UPDATE outbox_events e SET status='PUBLISHING',publish_attempts=publish_attempts+1,publisher_claim_id=?,lease_expires_at=now()+interval '60 seconds'
                FROM candidates c WHERE e.id=c.id RETURNING e.id,e.type,e.payload_json,e.publish_attempts
                """, analysisEnabled,claim);
        for (var event : events) {
            try {
                MessageProperties properties = new MessageProperties();
                properties.setContentType("application/json");
                properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                properties.setMessageId(event.get("id").toString());
                Message message = new Message(event.get("payload_json").toString().getBytes(StandardCharsets.UTF_8), properties);
                broker.execute(channel -> {
                    var returned=new java.util.concurrent.atomic.AtomicBoolean();
                    com.rabbitmq.client.ReturnListener listener=(code,text,exchange,key,props,body)->returned.set(true);
                    channel.addReturnListener(listener);
                    try {
                        channel.confirmSelect();
                        String route=java.util.Set.of("CONTENT_ANALYZE","INDEX_BUILD","VISION_ANALYZE").contains(event.get("type"))?"analysis.v1":"media.v1";
                        var props=new com.rabbitmq.client.AMQP.BasicProperties.Builder().contentType("application/json").deliveryMode(2).messageId(event.get("id").toString()).build();
                        channel.basicPublish("",route,true,props,message.getBody());
                        channel.waitForConfirmsOrDie(5000);
                        if(returned.get())throw new java.io.IOException("Task notification was not routed");
                        return null;
                    }finally {channel.removeReturnListener(listener);}
                });
                database.update("UPDATE outbox_events SET status='PUBLISHED',published_at=now(),publisher_claim_id=NULL,lease_expires_at=NULL,last_error_code=NULL WHERE id=? AND publisher_claim_id=?", event.get("id"), claim);
            } catch (AmqpException unavailable) {
                int attempts=((Number)event.get("publish_attempts")).intValue();
                int backoff=Math.min(60,1 << Math.min(6,attempts));
                database.update("UPDATE outbox_events SET status='PENDING',available_at=now()+make_interval(secs => ?),publisher_claim_id=NULL,lease_expires_at=NULL,last_error_code='BROKER_UNCONFIRMED' WHERE id=? AND publisher_claim_id=?",backoff,event.get("id"),claim);
            }
        }
    }
}
