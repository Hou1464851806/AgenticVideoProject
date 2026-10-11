package com.agenticvideo.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Component
public class MediaExecutor {
    private final String executorId = UUID.randomUUID().toString();
    private final ConcurrentMap<UUID, Execution> active = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, UUID> claims = new ConcurrentHashMap<>();
    private final Semaphore slot = new Semaphore(1);
    private final ExecutorService runner = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor();
    private final BusinessClient business;
    private final MediaInspection inspection;
    private final RepresentativeFrames frames;
    private final MediaRenderer renderer;
    private final WorkDirectories work;
    private final ObjectMapper json;
    private final S3Client storage;
    private final String bucket;
    MediaExecutor(RepresentativeFrames frames,BusinessClient business, MediaInspection inspection, WorkDirectories work, ObjectMapper json, MediaRenderer renderer,
            @Value("${worker.storage-endpoint}") String endpoint, @Value("${worker.storage-access-key}") String access,
            @Value("${worker.storage-secret-key}") String secret, @Value("${worker.storage-bucket}") String bucket) {
        this.frames=frames;this.renderer=renderer; this.business=business; this.inspection=inspection; this.work=work; this.json=json; this.bucket=bucket;
        storage = S3Client.builder().endpointOverride(java.net.URI.create(endpoint)).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(access,secret)))
                .forcePathStyle(true).build();
    }
    @RabbitListener(queues="media.v1", ackMode="MANUAL")
    public void notification(Message message, Channel channel) throws Exception {
        long delivery = message.getMessageProperties().getDeliveryTag();
        slot.acquire();
        boolean handedOff = false;
        try {
            JsonNode event=json.readTree(message.getBody());
            UUID taskId=UUID.fromString(event.path("taskId").asText());
            UUID claim=claims.computeIfAbsent(taskId,id -> UUID.randomUUID());
            var reply = business.post("/internal/v1/tasks/"+taskId+"/claim", Map.of("executorId",executorId,"claimRequestId",claim));
            if (reply.status()==409 || reply.status()==200 && !reply.data().path("claimed").asBoolean()) {
                channel.basicAck(delivery,false); claims.remove(taskId); return;
            }
            if (reply.status()!=200) { channel.basicNack(delivery,false,false); return; }
            UUID attemptId=UUID.fromString(reply.data().path("attemptId").asText());
            Execution execution=new Execution(taskId,attemptId,reply.data().path("leaseToken").asText(),Instant.parse(reply.data().path("leaseExpiresAt").asText()),reply.data().path("input"));
            if (execution.expired()) { channel.basicAck(delivery,false); return; }
            if (active.putIfAbsent(attemptId,execution)!=null) { channel.basicAck(delivery,false); return; }
            try { channel.basicAck(delivery,false); }
            catch(Exception unavailable) { active.remove(attemptId,execution); throw unavailable; }
            runner.submit(() -> execute(execution));
            handedOff=true;
        } catch(Exception unavailable) {
            try { channel.basicNack(delivery,false,false); } catch(Exception channelClosed) { /* Database recovery covers an ambiguous ACK. */ }
        } finally { if(!handedOff) slot.release(); }
    }
    private void execute(Execution execution) {
        ScheduledFuture<?> heartbeat=heartbeats.scheduleWithFixedDelay(() -> heartbeat(execution),0,10,TimeUnit.SECONDS);
        Path directory=null;
        try {
            directory=work.create(execution.attemptId);
            var capacity=business.post("/internal/v1/tasks/"+execution.taskId+"/space-check",Map.of("attemptId",execution.attemptId,"leaseToken",execution.token));
            if(capacity.status()==409 && "STORAGE_SPACE_LOW".equals(capacity.error().path("code").asText()))
                throw new MediaInspection.InvalidMedia("MEDIA_SPACE_LOW",capacity.error().path("message").asText());
            if(capacity.status()==401 || capacity.status()==409)throw new InterruptedException("Execution stopped before media admission");
            if(capacity.status()!=200)throw new java.io.IOException("Media capacity check unavailable");
            long required=capacity.data().path("requiredBytes").asLong();
            if(required<1 || Files.getFileStore(directory).getUsableSpace()<required)
                throw new MediaInspection.InvalidMedia("MEDIA_SPACE_LOW","当前媒体执行空间不足，请清理工作区或扩容后重试");
            if(java.util.Set.of("PREVIEW","EXPORT").contains(execution.input.path("operation").asText())) {
                var rendered=renderer.render(execution.input,directory,storage,bucket,execution::stopped,required);
                if(execution.stopped()) throw new InterruptedException("Execution stopped");
                report(execution,"complete",Map.of("attemptId",execution.attemptId,"leaseToken",execution.token,"producerKey","rendered","resultType","EXPORT".equals(execution.input.path("operation").asText())?"EXPORT_RENDERED":"PREVIEW_RENDERED","result",rendered));
                return;
            }
            Path source=directory.resolve("source");
            try(var input=storage.getObject(b -> b.bucket(bucket).key(execution.input.path("objectKey").asText())); var output=Files.newOutputStream(source)) {
                byte[] buffer=new byte[32768]; int count;
                while((count=input.read(buffer))!=-1) {
                    if(execution.stopped()) throw new InterruptedException("Execution stopped");
                    output.write(buffer,0,count);
                }
            }
            var result=inspection.inspect(source,execution.input.path("sizeBytes").asLong(),execution.input.path("sha256").asText(),execution::stopped);
            if(java.util.Set.of("VISION_PREPARE","INSPECTION_PREPARE").contains(execution.input.path("operation").asText())){
                var prepared=prepareFrames(execution,source,result);
                if(execution.stopped())throw new InterruptedException("Execution stopped");
                report(execution,"complete",Map.of("attemptId",execution.attemptId,"leaseToken",execution.token,"producerKey","vision-frames-prepared","resultType",execution.input.has("inspectionId")?"INSPECTION_FRAMES_PREPARED":"VISION_FRAMES_PREPARED","result",prepared));
                return;
            }
            var published=new java.util.LinkedHashMap<String,Object>(result);
            @SuppressWarnings("unchecked") var mapping=(Map<String,Object>)result.get("timeMapping");
            if(Boolean.TRUE.equals(mapping.get("hasAudio"))) {
                var audio=new java.util.LinkedHashMap<String,Object>(inspection.prepareAudio(source,mapping,execution::stopped));
                String key="derived/audio/"+execution.input.path("assetId").asText()+"/"+execution.attemptId+".wav";
                if(execution.stopped()) throw new InterruptedException("Execution stopped");
                storage.putObject(b -> b.bucket(bucket).key(key).contentType("audio/wav"),software.amazon.awssdk.core.sync.RequestBody.fromFile(directory.resolve("audio.wav")));
                audio.put("objectKey",key);published.put("derivedAudio",audio);
            }
            try { published.put("representativeFrames",prepareFrames(execution,source,result)); }
            catch(InterruptedException cancelled){throw cancelled;}
            catch(Exception unavailable){published.put("visionFailure",Map.of("code","VISION_FRAMES_FAILED","message","代表帧提取暂时失败；媒体验证和语音分析仍可使用"));}
            if(execution.stopped()) throw new InterruptedException("Execution stopped");
            report(execution,"complete",Map.of("attemptId",execution.attemptId,"leaseToken",execution.token,"producerKey","media-prepared","resultType","MEDIA_PREPARED","result",published));
        } catch(InterruptedException stopped) {
            // Inspection has waited for the local process to exit. Report after cleanup below.
        } catch(Exception failed) {
            String code=failed instanceof MediaInspection.InvalidMedia invalid ? invalid.code : "MEDIA_EXECUTION_FAILED";
            String reason=failed instanceof MediaInspection.InvalidMedia ? failed.getMessage() : "媒体执行暂时失败";
            try { report(execution,"fail",Map.of("attemptId",execution.attemptId,"leaseToken",execution.token,"producerKey","media-failed","code",code,"message",reason)); }
            catch(Exception unavailable) { /* A missing receipt is recovered through the authoritative attempt. */ }
        } finally {
            heartbeat.cancel(false);
            if(directory!=null) {
                try { work.delete(directory); }
                catch(java.io.IOException ignored) { /* Kept within this attempt's work directory for recovery cleanup. */ }
            }
            if(execution.budgetExpired()&&!execution.cancelRequested)heartbeat(execution);
            if(execution.cancelRequested) {
                try { report(execution,"cancelled",Map.of("attemptId",execution.attemptId,"leaseToken",execution.token,"processExited",true)); }
                catch(Exception unavailable) { /* Recovery verifies the stopped runtime before finalizing cancellation. */ }
            }
            active.remove(execution.attemptId,execution); claims.remove(execution.taskId); slot.release();
        }
    }
    private Map<String,Object> prepareFrames(Execution execution,Path source,Map<String,Object> media)throws Exception{
        @SuppressWarnings("unchecked") var mapping=(Map<String,Object>)media.get("timeMapping");
        long duration=((Number)media.get("durationMs")).longValue(),origin=((Number)mapping.get("formatStartMs")).longValue();
        var extracted=execution.input.has("inspectionId")?frames.window(source,duration,origin,execution.input.path("startMs").asLong(),execution.input.path("endMs").asLong(),execution.input.path("maxFrames").asInt(),execution.input.path("samplingKind").asText(),execution::stopped):frames.extract(source,duration,origin,execution::stopped);
        var result=new java.util.LinkedHashMap<String,Object>(extracted);
        var manifest=new java.util.ArrayList<Object>();
        for(Object item:(java.util.List<?>)result.get("frames")){
            @SuppressWarnings("unchecked") var frame=new java.util.LinkedHashMap<String,Object>((Map<String,Object>)item);
            String name=String.format(java.util.Locale.ROOT,"frame-%03d.jpg",((Number)frame.get("ordinal")).intValue());
            String key="derived/frames/"+execution.input.path("assetId").asText()+"/"+execution.attemptId+"/"+name;
            if(execution.stopped())throw new InterruptedException("Execution stopped");
            storage.putObject(b->b.bucket(bucket).key(key).contentType("image/jpeg"),software.amazon.awssdk.core.sync.RequestBody.fromFile(source.getParent().resolve(name)));
            frame.put("objectKey",key);manifest.add(frame);
        }
        result.put("frames",manifest);return result;
    }
    private void heartbeat(Execution execution) {
        try {
            var reply=business.post("/internal/v1/tasks/"+execution.taskId+"/heartbeat",Map.of("attemptId",execution.attemptId,"leaseToken",execution.token));
            if(reply.status()==200) {
                execution.cancelRequested="STOP".equals(reply.data().path("action").asText());
                execution.deadline=Instant.parse(reply.data().path("leaseExpiresAt").asText());
            } else if(reply.status()==409 || reply.status()==401) execution.lost=true;
        } catch(Exception unavailable) { if(execution.expired()) execution.lost=true; }
    }
    private void report(Execution execution,String action,Object body) throws Exception {
        for(int attempt=0;attempt<3;attempt++) {
            try {
                var reply=business.post("/internal/v1/tasks/"+execution.taskId+"/"+action,body);
                if(reply.status()==200) return;
                if(reply.status()==409) {
                    // Cancellation can win between the last heartbeat and the result receipt.
                    if(!action.equals("cancelled")) heartbeat(execution);
                    return;
                }
            } catch(Exception unavailable) { if(attempt==2) throw unavailable; }
            Thread.sleep(250L << attempt);
        }
        throw new java.io.IOException("Execution receipt unavailable");
    }
    public Map<String,Object> state(UUID attempt) {
        Execution execution=active.get(attempt);
        return Map.of("executorId",executorId,"state",execution==null ? "NOT_FOUND" : execution.stopped() ? "STOPPING" : "RUNNING");
    }
    public Map<String,Object> stop(UUID attempt) {
        Execution execution=active.get(attempt);
        if(execution!=null) execution.lost=true;
        return state(attempt);
    }
    @jakarta.annotation.PreDestroy public void close() throws InterruptedException {
        active.values().forEach(e -> e.lost=true); runner.shutdown();
        if(!runner.awaitTermination(35,TimeUnit.SECONDS)) runner.shutdownNow();
        heartbeats.shutdownNow(); storage.close();
    }
    private static final class Execution {
        final UUID taskId,attemptId; final String token; final JsonNode input;
        volatile Instant deadline; volatile boolean lost,cancelRequested;
        Execution(UUID task,UUID attempt,String token,Instant deadline,JsonNode input) { this.taskId=task;this.attemptId=attempt;this.token=token;this.deadline=deadline;this.input=input; }
        boolean expired() { return !Instant.now().isBefore(deadline); }
        boolean budgetExpired() { return input.has("budgetDeadline")&&!Instant.now().isBefore(Instant.parse(input.path("budgetDeadline").asText())); }
        boolean stopped() { return lost || cancelRequested || expired() || budgetExpired(); }
    }
}
