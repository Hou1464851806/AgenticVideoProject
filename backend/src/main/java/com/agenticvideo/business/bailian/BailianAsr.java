package com.agenticvideo.business.bailian;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.net.http.HttpRequest;
import java.util.*;
import org.springframework.stereotype.Component;

/** Qwen file transcription: temporary private transfer, one submit, subsequent read-only queries. */
@Component
public class BailianAsr {
    private final BailianHttp http;
    public BailianAsr(BailianHttp http){this.http=http;}
    public String upload(Path audio,String model) throws java.io.IOException {
        var policy=http.request("GET","/uploads?action=getPolicy&model="+model,null,Map.of()).path("data");
        if(Files.size(audio)>policy.path("max_file_size_mb").asLong()*1024*1024)throw new BailianHttp.Unavailable("音轨超过百炼临时上传限制");
        String key=policy.path("upload_dir").asText()+"/"+UUID.randomUUID()+".wav",boundary=UUID.randomUUID().toString();
        var fields=new LinkedHashMap<String,String>();
        fields.put("OSSAccessKeyId",policy.path("oss_access_key_id").asText());fields.put("Signature",policy.path("signature").asText());
        fields.put("policy",policy.path("policy").asText());fields.put("x-oss-object-acl",policy.path("x_oss_object_acl").asText());
        fields.put("x-oss-forbid-overwrite",policy.path("x_oss_forbid_overwrite").asText());fields.put("key",key);fields.put("success_action_status","200");
        var prefix=new StringBuilder();
        fields.forEach((name,value)->prefix.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n").append(value).append("\r\n"));
        prefix.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\nContent-Type: audio/wav\r\n\r\n");
        var body=HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(prefix.toString().getBytes(StandardCharsets.UTF_8)),HttpRequest.BodyPublishers.ofFile(audio),HttpRequest.BodyPublishers.ofString("\r\n--"+boundary+"--\r\n"));
        http.upload(policy.path("upload_host").asText(),"multipart/form-data; boundary="+boundary,body);
        return "oss://"+key;
    }
    public JsonNode submit(String address,String model) {
        return http.request("POST","/services/audio/asr/transcription",Map.of("model",model,"input",Map.of("file_url",address),"parameters",Map.of("channel_id",List.of(0),"enable_itn",false,"enable_words",true)),Map.of("X-DashScope-Async","enable","X-DashScope-OssResourceResolve","enable"));
    }
    public JsonNode query(String task){return http.request("GET","/tasks/"+UUID.fromString(task),null,Map.of());}
    public JsonNode transcript(JsonNode response){return http.download(response.path("output").path("result").path("transcription_url").asText().replaceFirst("^http:","https:"));}
}
