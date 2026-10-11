package com.agenticvideo.media;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Sparse real PTS observations; selection never invents frames at nominal FPS timestamps. */
@Service
public class RepresentativeFrames {
    private final MediaInspection processes;
    public RepresentativeFrames(MediaInspection processes){this.processes=processes;}
    public Map<String,Object> extract(Path source,long duration,long formatStart,BooleanSupplier stopped)throws Exception{
        long interval=Math.max(2000,(duration+23)/24);
        String output=processes.run(List.of("ffmpeg","-nostdin","-v","info","-threads","1","-copyts","-i",source.toString(),
                "-map","0:v:0","-vf","select='isnan(prev_selected_t)+gte(t-prev_selected_t,"+interval/1000.0+")',scale=960:960:force_original_aspect_ratio=decrease,showinfo",
                "-fps_mode","vfr","-frames:v","25","-q:v","3","-start_number","0","-y",source.getParent().resolve("frame-%03d.jpg").toString()),source.getParent(),600,stopped);
        var matcher=Pattern.compile("n:\\s*([0-9]+).*?pts_time:([0-9.\\-]+)").matcher(output);
        var frames=new ArrayList<Object>();
        while(matcher.find()){
            int ordinal=Integer.parseInt(matcher.group(1));
            Path file=source.getParent().resolve(String.format(Locale.ROOT,"frame-%03d.jpg",ordinal));
            if(!Files.exists(file))continue;
            long time=new java.math.BigDecimal(matcher.group(2)).multiply(java.math.BigDecimal.valueOf(1000)).setScale(0,java.math.RoundingMode.HALF_UP).longValueExact()-formatStart;
            if(time<0||time>=duration||frames.size()>=25)throw new IllegalStateException("Invalid frame timeline");
            byte[] bytes=Files.readAllBytes(file);
            if(bytes.length<1||bytes.length>1048576)throw new IllegalStateException("Representative frame exceeds bounded size");
            frames.add(Map.of("ordinal",ordinal,"observedAtMs",time,"sourcePts",matcher.group(2),"sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),"sizeBytes",bytes.length));
        }
        if(frames.isEmpty())throw new IllegalStateException("No representative frames decoded");
        return Map.of("frames",frames,"profile",Map.of("kind","SPARSE_FRAMES","rule","real-pts-interval-v1","intervalMs",interval,"maxFrames",25,"complete",false));
    }
}
