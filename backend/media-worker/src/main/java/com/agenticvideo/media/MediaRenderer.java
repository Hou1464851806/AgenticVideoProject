package com.agenticvideo.media;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;

/** Compiles immutable server input into controlled arguments; never accepts executable filter text. */
@Service
public class MediaRenderer {
    private final MediaInspection inspection;
    public MediaRenderer(MediaInspection inspection) { this.inspection=inspection; }
    public Map<String,Object> render(JsonNode input,Path directory,S3Client storage,String bucket,BooleanSupplier stopped) throws Exception {
        JsonNode output=input.path("output"),config=input.path("configuration");
        int width=config.path("width").asInt(),height=config.path("height").asInt();
        String profile=config.path("profile").asText();
        boolean low="LOW_360".equals(profile),hd="HD_720".equals(profile),full="FULL_HD_1080".equals(profile);
        boolean dimensions=low&&(width==640&&height==360 || width==360&&height==640)
                || hd&&(width==1280&&height==720 || width==720&&height==1280)
                || full&&(width==1920&&height==1080 || width==1080&&height==1920);
        if(!"ffmpeg-6.1.1-noto20230817-hardcut-v2".equals(input.path("rendererVersion").asText()) || !dimensions
                || !(low?"PREVIEW":"EXPORT").equals(input.path("operation").asText())
                || config.path("framesPerSecond").asInt()!=30
                || config.path("videoCrf").asInt()!=(low?28:23)
                || config.path("videoMaxRate").asInt()!=(low?800000:full?8000000:4000000)
                || config.path("audioBitrate").asInt()!=(low?64000:full?192000:128000)
                || config.path("audioSampleRate").asInt()!=48000 || config.path("audioChannels").asInt()!=2
                || !"Noto Sans CJK SC".equals(config.path("subtitleFont").asText()))
            throw new MediaInspection.InvalidMedia("RENDER_CONFIGURATION","合成规格无效");
        long sourceBytes=0;
        for(JsonNode source:input.path("sources"))sourceBytes=Math.addExact(sourceBytes,source.path("sizeBytes").asLong());
        long required=Math.addExact(sourceBytes,Math.addExact(64L*1024*1024,Math.multiplyExact(output.path("durationMs").asLong(),Math.addExact(Math.multiplyExact((long)width,height)*6/1000,config.path("videoMaxRate").asLong()/8000+100))));
        if(Files.getFileStore(directory).getUsableSpace()<required)throw new MediaInspection.InvalidMedia("PREVIEW_SPACE_LOW","当前执行空间不足，未开始合成");
        Map<String,Path> sources=new HashMap<>();Map<String,JsonNode> mappings=new HashMap<>();int sourceIndex=0;
        for(JsonNode source:input.path("sources")) {
            Path file=directory.resolve("source-"+(sourceIndex++));
            try(var data=storage.getObject(b->b.bucket(bucket).key(source.path("objectKey").asText()));var target=Files.newOutputStream(file)) {
                byte[] buffer=new byte[32768];int count;
                while((count=data.read(buffer))!=-1){check(stopped);target.write(buffer,0,count);}
            }
            inspection.inspect(file,source.path("sizeBytes").asLong(),source.path("sha256").asText(),stopped);
            sources.put(source.path("assetId").asText(),file);mappings.put(source.path("assetId").asText(),source.path("timeMapping"));
        }
        List<Path> clips=new ArrayList<>();int index=0;
        for(JsonNode clip:output.path("clips")) {
            check(stopped);
            Path source=sources.get(clip.path("assetId").asText());JsonNode mapping=mappings.get(clip.path("assetId").asText());
            if(source==null)throw new MediaInspection.InvalidMedia("PREVIEW_SOURCE_MISSING","版本引用的素材不可用");
            long start=clip.path("sourceInMs").asLong(),end=clip.path("sourceOutMs").asLong();String duration=seconds(end-start);
            Path normalized=directory.resolve("clip-"+(index++)+".mkv");
            List<String> command=new ArrayList<>(List.of("ffmpeg","-nostdin","-v","error","-threads","1","-i",source.toString()));
            boolean hasAudio=mapping.path("hasAudio").asBoolean();
            if(!hasAudio)command.addAll(List.of("-f","lavfi","-i","anullsrc=r=48000:cl=stereo"));
            long videoOffset=Math.max(0,mapping.path("videoStartMs").asLong()-mapping.path("formatStartMs").asLong());
            String video="[0:v:0]setpts=PTS-STARTPTS,tpad=start_duration="+seconds(videoOffset)+":start_mode=clone,trim=start="+seconds(start)+":end="+seconds(end)+",setpts=PTS-STARTPTS,fps="+config.path("framesPerSecond").asInt()+",scale=w='if(gte(dar,"+width+"/"+height+"),"+width+",max(2,trunc("+height+"*dar/2)*2))':h='if(gte(dar,"+width+"/"+height+"),max(2,trunc("+width+"/dar/2)*2),"+height+")',setsar=1,pad="+width+":"+height+":(ow-iw)/2:(oh-ih)/2:black,format=yuv420p[v]";
            long audioOffset=Math.max(0,mapping.path("audioStartMs").asLong()-mapping.path("formatStartMs").asLong());
            String audio=hasAudio?"[0:a:0]asetpts=PTS-STARTPTS,aresample=48000,adelay="+audioOffset+":all=1,atrim=start="+seconds(start)+":end="+seconds(end)+",asetpts=PTS-STARTPTS,apad,atrim=duration="+duration+"[a]":"[1:a:0]atrim=duration="+duration+",asetpts=PTS-STARTPTS[a]";
            command.addAll(List.of("-filter_complex_threads","1","-filter_complex",video+";"+audio,"-map","[v]","-map","[a]","-c:v","libx264","-preset","ultrafast","-crf","20","-threads","1","-c:a","pcm_s16le","-ar","48000","-ac","2","-t",duration,"-y",normalized.toString()));
            inspection.run(command,directory,600,stopped);clips.add(normalized);
        }
        if(clips.isEmpty())throw new MediaInspection.InvalidMedia("PREVIEW_EMPTY","版本没有可合成的片段");
        StringBuilder subtitles=new StringBuilder();int cueIndex=1;
        if(output.path("subtitles").path("enabled").asBoolean())for(JsonNode cue:output.path("subtitles").path("cues")) {
            // SRT text is data; strip markup and escape libass override syntax.
            String text=cue.path("text").asText().replaceAll("<[^>]*>","").replace("{","（").replace("}","）").replace("\\","＼").replace("\r","");
            subtitles.append(cueIndex++).append('\n').append(timestamp(cue.path("startMs").asLong())).append(" --> ").append(timestamp(cue.path("endMs").asLong())).append('\n').append(text).append("\n\n");
        }
        Files.writeString(directory.resolve("captions.srt"),subtitles.toString(),StandardCharsets.UTF_8);
        List<String> command=new ArrayList<>(List.of("ffmpeg","-nostdin","-v","error","-threads","1"));
        for(Path clip:clips)command.addAll(List.of("-i",clip.toString()));
        StringBuilder filter=new StringBuilder();for(int i=0;i<clips.size();i++)filter.append('[').append(i).append(":v:0][").append(i).append(":a:0]");
        filter.append("concat=n=").append(clips.size()).append(":v=1:a=1[base][a]");
        if(subtitles.length()>0)filter.append(";[base]subtitles=filename=captions.srt:fontsdir=/usr/share/fonts/opentype/noto:force_style='FontName=Noto Sans CJK SC,FontSize=22,Outline=1,MarginV=18'[v]");
        Path rendered=directory.resolve("preview.mp4");
        command.addAll(List.of("-filter_complex_threads","1","-filter_complex",filter.toString(),"-map",subtitles.length()>0?"[v]":"[base]","-map","[a]","-c:v","libx264","-threads","1","-preset","veryfast","-crf",config.path("videoCrf").asText(),"-maxrate",config.path("videoMaxRate").asText(),"-bufsize",Long.toString(config.path("videoMaxRate").asLong()*2),"-pix_fmt","yuv420p","-c:a","aac","-b:a",config.path("audioBitrate").asText(),"-ar",config.path("audioSampleRate").asText(),"-ac",config.path("audioChannels").asText(),"-movflags","+faststart","-t",seconds(output.path("durationMs").asLong()),"-y",rendered.toString()));
        inspection.run(command,directory,600,stopped);
        MessageDigest digest=MessageDigest.getInstance("SHA-256");try(var data=Files.newInputStream(rendered)){byte[] buffer=new byte[32768];int count;while((count=data.read(buffer))!=-1){check(stopped);digest.update(buffer,0,count);}}
        long bytes=Files.size(rendered);String hash=HexFormat.of().formatHex(digest.digest());
        var result=new LinkedHashMap<String,Object>(inspection.inspect(rendered,bytes,hash,stopped));
        if(Math.abs(((Number)result.get("durationMs")).longValue()-output.path("durationMs").asLong())>100)throw new MediaInspection.InvalidMedia("PREVIEW_DURATION","合成时长与版本不符");
        check(stopped);String key=input.path("artifactObjectKey").asText();
        storage.putObject(b->b.bucket(bucket).key(key).contentType("video/mp4"),software.amazon.awssdk.core.sync.RequestBody.fromFile(rendered));
        result.put("artifactId",input.path("artifactId").asText());result.put("objectKey",key);return result;
    }
    private static void check(BooleanSupplier stopped) throws InterruptedException {if(stopped.getAsBoolean())throw new InterruptedException("Execution stopped");}
    private static String seconds(long ms){return java.math.BigDecimal.valueOf(ms,3).toPlainString();}
    private static String timestamp(long ms){return String.format(Locale.ROOT,"%02d:%02d:%02d,%03d",ms/3600000,ms/60000%60,ms/1000%60,ms%1000);}
}
