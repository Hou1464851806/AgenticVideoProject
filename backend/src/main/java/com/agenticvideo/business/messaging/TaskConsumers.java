package com.agenticvideo.business.messaging;

import com.agenticvideo.business.task.LocalTaskRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="tasks.consume-enabled",havingValue="true")
class TaskConsumers {
    private final LocalTaskRunner runner;
    private final ObjectMapper json;
    TaskConsumers(LocalTaskRunner runner,ObjectMapper json) { this.runner=runner; this.json=json; }
    @RabbitListener(queues="media.v1",ackMode="MANUAL",concurrency="1")
    public void media(Message message,Channel channel) throws Exception {
        long delivery=message.getMessageProperties().getDeliveryTag();
        var event=json.readTree(message.getBody());
        UUID task;
        try { task=UUID.fromString(event.path("taskId").asText()); }
        catch(IllegalArgumentException malformed) { channel.basicReject(delivery,false); return; }
        if(!java.util.Set.of("MEDIA_PREPARE","VISION_PREPARE","PREVIEW_RENDER","EXPORT_RENDER").contains(runner.type(task))){channel.basicReject(delivery,false);return;}
        // The listener itself executes the stage: prefetch and concurrency bound actual work.
        // A database failure throws; rejected delivery is recoverable through persistent task/outbox state.
        boolean completed=runner.execute(task);
        if(completed)channel.basicAck(delivery,false);
        else channel.basicReject(delivery,false);
    }
    @RabbitListener(queues="analysis.v1",ackMode="MANUAL",concurrency="${analysis.consumer-concurrency:2}",autoStartup="${analysis.execution-enabled:false}")
    public void analysis(Message message,Channel channel) throws Exception {
        long delivery=message.getMessageProperties().getDeliveryTag();
        UUID task;
        try {task=UUID.fromString(json.readTree(message.getBody()).path("taskId").asText());}
        catch(IllegalArgumentException | java.io.IOException malformed){channel.basicReject(delivery,false);return;}
        if(!java.util.Set.of("CONTENT_ANALYZE","VISION_ANALYZE","INDEX_BUILD").contains(runner.type(task))){channel.basicReject(delivery,false);return;}
        if(runner.execute(task))channel.basicAck(delivery,false);
        else channel.basicReject(delivery,false);
    }
}
