package com.agenticvideo.business.media;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/** Media operations own processes, storage and temporary files, but never task state or messaging. */
@Service
public class MediaProcessing {
    private final MediaInspection inspection;
    private final RepresentativeFrames frames;
    private final MediaRenderer renderer;
    private final WorkDirectories work;
    private final S3Client storage;
    private final String bucket;

    MediaProcessing(MediaInspection inspection, RepresentativeFrames frames, MediaRenderer renderer, WorkDirectories work,
            @Value("${storage.endpoint}") String endpoint, @Value("${storage.access-key}") String access,
            @Value("${storage.secret-key}") String secret, @Value("${storage.bucket:originals}") String bucket) {
        this.inspection=inspection; this.frames=frames; this.renderer=renderer; this.work=work; this.bucket=bucket;
        storage=S3Client.builder().endpointOverride(java.net.URI.create(endpoint)).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(access,secret)))
                .forcePathStyle(true).build();
    }

    public record Result(String type, Map<String,Object> data) {}

    public Result execute(UUID attempt, JsonNode input, long required, BooleanSupplier stopped) throws Exception {
        Path directory=work.create(attempt);
        try {
            if(required<1 || Files.getFileStore(directory).getUsableSpace()<required)
                throw new MediaInspection.InvalidMedia("MEDIA_SPACE_LOW","当前媒体执行空间不足，请清理工作区或扩容后重试");
            if(Set.of("PREVIEW","EXPORT").contains(input.path("operation").asText())) {
                var rendered=renderer.render(input,directory,storage,bucket,stopped,required);
                check(stopped);
                return new Result("EXPORT".equals(input.path("operation").asText())?"EXPORT_RENDERED":"PREVIEW_RENDERED",rendered);
            }
            Path source=directory.resolve("source");
            try(var data=storage.getObject(b->b.bucket(bucket).key(input.path("objectKey").asText()));var output=Files.newOutputStream(source)) {
                byte[] buffer=new byte[32768]; int count;
                while((count=data.read(buffer))!=-1) { check(stopped); output.write(buffer,0,count); }
            }
            var media=inspection.inspect(source,input.path("sizeBytes").asLong(),input.path("sha256").asText(),stopped);
            if("VISION_PREPARE".equals(input.path("operation").asText()))
                return new Result("VISION_FRAMES_PREPARED",prepareFrames(attempt,input,source,media,stopped));
            var published=new LinkedHashMap<String,Object>(media);
            @SuppressWarnings("unchecked") var mapping=(Map<String,Object>)media.get("timeMapping");
            if(Boolean.TRUE.equals(mapping.get("hasAudio"))) {
                var audio=new LinkedHashMap<String,Object>(inspection.prepareAudio(source,mapping,stopped));
                String key="derived/audio/"+input.path("assetId").asText()+"/"+attempt+".wav";
                check(stopped);
                storage.putObject(b->b.bucket(bucket).key(key).contentType("audio/wav"),software.amazon.awssdk.core.sync.RequestBody.fromFile(directory.resolve("audio.wav")));
                audio.put("objectKey",key); published.put("derivedAudio",audio);
            }
            try { published.put("representativeFrames",prepareFrames(attempt,input,source,media,stopped)); }
            catch(InterruptedException cancelled) { throw cancelled; }
            catch(Exception unavailable) { published.put("visionFailure",Map.of("code","VISION_FRAMES_FAILED","message","代表帧提取暂时失败；媒体验证和语音分析仍可使用")); }
            check(stopped);
            return new Result("MEDIA_PREPARED",published);
        } finally {
            try { work.delete(directory); }
            catch(java.io.IOException unavailable) {
                org.slf4j.LoggerFactory.getLogger(MediaProcessing.class).warn("Media work cleanup deferred for attempt {}",attempt);
            }
        }
    }

    private Map<String,Object> prepareFrames(UUID attempt,JsonNode input,Path source,Map<String,Object> media,BooleanSupplier stopped) throws Exception {
        @SuppressWarnings("unchecked") var mapping=(Map<String,Object>)media.get("timeMapping");
        var result=new LinkedHashMap<String,Object>(frames.extract(source,((Number)media.get("durationMs")).longValue(),((Number)mapping.get("formatStartMs")).longValue(),stopped));
        var manifest=new ArrayList<Object>();
        for(Object item:(List<?>)result.get("frames")) {
            @SuppressWarnings("unchecked") var frame=new LinkedHashMap<String,Object>((Map<String,Object>)item);
            String name=String.format(Locale.ROOT,"frame-%03d.jpg",((Number)frame.get("ordinal")).intValue());
            String key="derived/frames/"+input.path("assetId").asText()+"/"+attempt+"/"+name;
            check(stopped);
            storage.putObject(b->b.bucket(bucket).key(key).contentType("image/jpeg"),software.amazon.awssdk.core.sync.RequestBody.fromFile(source.getParent().resolve(name)));
            frame.put("objectKey",key); manifest.add(frame);
        }
        result.put("frames",manifest); return result;
    }
    public byte[] readFrame(JsonNode frame,BooleanSupplier stopped) throws Exception {
        check(stopped);
        try(var data=storage.getObject(b->b.bucket(bucket).key(frame.path("objectKey").asText()))) {
            byte[] bytes=data.readNBytes(1048577);
            requireIdentity(bytes,frame);
            check(stopped);return bytes;
        }
    }
    @FunctionalInterface public interface AudioTransfer { String upload(Path audio) throws Exception; }
    public String transferAudio(UUID attempt,JsonNode audio,BooleanSupplier stopped,AudioTransfer transfer) throws Exception {
        Path directory=work.create(attempt),path=directory.resolve("audio.wav");
        try {
            long expected=audio.path("sizeBytes").asLong();
            if(expected<1||Files.getFileStore(directory).getUsableSpace()<expected+536870912L)throw new java.io.IOException("音轨下载空间不足");
            var digest=java.security.MessageDigest.getInstance("SHA-256");long size=0;
            try(var data=storage.getObject(b->b.bucket(bucket).key(audio.path("objectKey").asText()));var output=Files.newOutputStream(path)) {
                byte[] buffer=new byte[65536];int count;
                while((count=data.read(buffer))!=-1){check(stopped);size+=count;if(size>expected)throw new java.io.IOException("音轨大小不符");digest.update(buffer,0,count);output.write(buffer,0,count);}
            }
            if(size!=expected||!HexFormat.of().formatHex(digest.digest()).equals(audio.path("sha256").asText()))throw new java.io.IOException("音轨身份不符");
            check(stopped);return transfer.upload(path);
        }finally{work.delete(directory);}
    }
    private static void requireIdentity(byte[] bytes,JsonNode frame) throws Exception {
        if(bytes.length!=frame.path("sizeBytes").asLong()||bytes.length>1048576||!HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)).equals(frame.path("sha256").asText()))throw new java.io.IOException("代表帧身份不符");
    }
    private static void check(BooleanSupplier stopped) throws InterruptedException { if(stopped.getAsBoolean())throw new InterruptedException("Execution stopped"); }
    @jakarta.annotation.PreDestroy void close() { storage.close(); }
}
