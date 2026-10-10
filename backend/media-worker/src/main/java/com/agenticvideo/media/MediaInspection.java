package com.agenticvideo.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Service;

@Service
public class MediaInspection {
    private final ObjectMapper json;
    public MediaInspection(ObjectMapper json) { this.json = json; }
    public Map<String, Object> inspect(Path source, long expectedBytes, String expectedHash, BooleanSupplier stopped) throws Exception {
        if (Files.size(source) != expectedBytes) throw new InvalidMedia("BYTE_SIZE_MISMATCH", "文件真实大小与声明不符");
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(source)) {
            byte[] buffer = new byte[32768];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (stopped.getAsBoolean()) throw new InterruptedException("Execution stopped");
                hash.update(buffer, 0, count);
            }
        }
        String actualHash = HexFormat.of().formatHex(hash.digest());
        if (!actualHash.equalsIgnoreCase(expectedHash)) throw new InvalidMedia("HASH_MISMATCH", "文件服务端校验值与声明不符");
        String metadata = run(List.of("ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json", source.toAbsolutePath().toString()), source.getParent(), 30, stopped);
        JsonNode media = json.readTree(metadata);
        JsonNode video = null;
        JsonNode audio = null;
        for (JsonNode stream : media.path("streams")) {
            if (video == null && "video".equals(stream.path("codec_type").asText()) && stream.path("disposition").path("attached_pic").asInt() == 0) video = stream;
            if (audio == null && "audio".equals(stream.path("codec_type").asText())) audio = stream;
        }
        if (video == null) throw new InvalidMedia("NO_VIDEO_STREAM", "文件没有可用视频流");
        long duration = milliseconds(media.path("format").path("duration"));
        if (duration <= 0 || duration > 3600000) throw new InvalidMedia("DURATION_LIMIT", "视频时长必须大于零且不超过 60 分钟");
        run(List.of("ffmpeg", "-nostdin", "-v", "error", "-xerror", "-threads", "1", "-i", source.toAbsolutePath().toString(),
                "-map", "0:v:0", "-map", "0:a?", "-f", "null", "-"), source.getParent(), 600, stopped);
        if (media.path("format").isObject()) ((com.fasterxml.jackson.databind.node.ObjectNode) media.path("format")).remove("filename");
        long formatStart = milliseconds(media.path("format").path("start_time"));
        long videoStart = milliseconds(video.path("start_time"));
        var mapping = new java.util.LinkedHashMap<String, Object>();
        mapping.put("formatStartMs", formatStart);
        mapping.put("videoStartMs", videoStart);
        mapping.put("videoTimeBase", video.path("time_base").asText());
        mapping.put("hasAudio", audio != null);
        if (audio != null) {
            long audioStart = milliseconds(audio.path("start_time"));
            mapping.put("audioStartMs", audioStart);
            mapping.put("audioOffsetFromVideoMs", audioStart - videoStart);
            mapping.put("audioTimeBase", audio.path("time_base").asText());
        }
        return Map.of("byteSize", expectedBytes, "sha256", actualHash, "durationMs", duration, "mediaInfo", media, "timeMapping", mapping);
    }
    private long milliseconds(JsonNode value) {
        String text = value.asText("0");
        if (text.equals("N/A")) return 0;
        try { return new BigDecimal(text).multiply(BigDecimal.valueOf(1000)).setScale(0, RoundingMode.HALF_UP).longValueExact(); }
        catch (ArithmeticException | NumberFormatException invalid) { throw new InvalidMedia("INVALID_TIMELINE", "媒体时间信息无效"); }
    }
    public Map<String,Object> prepareAudio(Path source,Map<String,Object> mapping,BooleanSupplier stopped) throws Exception {
        Path audio=source.getParent().resolve("audio.wav");
        run(List.of("ffmpeg","-nostdin","-v","error","-threads","1","-i",source.toAbsolutePath().toString(),
                "-map","0:a:0","-vn","-af","asetpts=PTS-STARTPTS,aresample=async=1:first_pts=0","-ar","16000","-ac","1","-c:a","pcm_s16le","-y",audio.toString()),source.getParent(),600,stopped);
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(var input=Files.newInputStream(audio)) {
            byte[] buffer=new byte[32768];int count;
            while((count=input.read(buffer))!=-1) { if(stopped.getAsBoolean()) throw new InterruptedException("Execution stopped");digest.update(buffer,0,count); }
        }
        long offset=((Number)mapping.get("audioStartMs")).longValue()-((Number)mapping.get("formatStartMs")).longValue();
        return Map.of("sizeBytes",Files.size(audio),"sha256",HexFormat.of().formatHex(digest.digest()),"sourceOffsetMs",offset,
                "sampleRate",16000,"channels",1,"format","wav","rule","mono-16k-pcm-pts-v1");
    }
    private String run(List<String> command, Path directory, int timeoutSeconds, BooleanSupplier stopped) throws IOException, InterruptedException {
        Path output = Files.createTempFile(directory, "probe-", ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (stopped.getAsBoolean()) throw new InterruptedException("Execution stopped");
                if (System.nanoTime() > deadline) throw new InvalidMedia("MEDIA_TIMEOUT", "媒体检查超时");
            }
            if (process.exitValue() != 0) throw new InvalidMedia("UNDECODABLE_MEDIA", "视频无法完整解码");
            try (var input = Files.newInputStream(output)) {
                byte[] bounded = input.readNBytes(1024 * 1024 + 1);
                if (bounded.length > 1024 * 1024) throw new InvalidMedia("METADATA_LIMIT", "媒体信息过大");
                return new String(bounded, java.nio.charset.StandardCharsets.UTF_8);
            }
        } finally {
            if (process.isAlive()) {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(); }
            }
            Files.deleteIfExists(output);
        }
    }
    public static final class InvalidMedia extends RuntimeException {
        public final String code;
        InvalidMedia(String code, String message) { super(message); this.code = code; }
    }
}
