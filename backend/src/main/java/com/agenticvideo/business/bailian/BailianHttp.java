package com.agenticvideo.business.bailian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Shared bounded transport only. Submission/recovery policy belongs to the business stage. */
@Component
public class BailianHttp {
    private final ObjectMapper json;
    private final String base,key;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    public BailianHttp(ObjectMapper json,@Value("${DASHSCOPE_BASE_URL:https://dashscope.aliyuncs.com/api/v1}") String base,
            @Value("${DASHSCOPE_API_KEY:}") String key) {this.json=json;this.base=base.replaceAll("/$","");this.key=key;}
    public void requireConfigured() {if(key.isBlank())throw new Unavailable("百炼模型凭据尚未配置");}
    public JsonNode request(String method,String path,Object body,Map<String,String> headers) {
        requireConfigured();
        var builder=HttpRequest.newBuilder(URI.create(base+path)).header("Authorization","Bearer "+key);
        headers.forEach(builder::header);
        try {
            builder.header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            return read(send(builder));
        }catch(IOException failure){throw new Unavailable("百炼请求或响应格式不可用");}
    }
    public JsonNode download(String address) {
        URI uri=providerUri(address);
        try{return read(send(HttpRequest.newBuilder(uri).GET()));}
        catch(IOException failure){throw new Unavailable("百炼转写结果不可用");}
    }
    public void upload(String address,String contentType,HttpRequest.BodyPublisher body) {
        var response=send(HttpRequest.newBuilder(providerUri(address)).header("Content-Type",contentType).POST(body));
        try(var stream=response.body()) {
            if(response.statusCode()/100!=2)throw new Unavailable("百炼临时文件上传失败，HTTP "+response.statusCode());
        }catch(IOException failure){throw new Unavailable("百炼临时文件上传失败");}
    }
    static URI providerUri(String address) {
        URI uri=URI.create(address);
        if(!"https".equals(uri.getScheme())||uri.getHost()==null||!uri.getHost().endsWith(".aliyuncs.com")||uri.getUserInfo()!=null)
            throw new Unavailable("百炼文件地址无效");
        return uri;
    }
    private HttpResponse<java.io.InputStream> send(HttpRequest.Builder request) {
        try{return client.send(request.timeout(Duration.ofSeconds(90)).build(),HttpResponse.BodyHandlers.ofInputStream());}
        catch(IOException failure){throw new Unavailable("百炼连接不可用");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new Unavailable("百炼请求已中断");}
    }
    private JsonNode read(HttpResponse<java.io.InputStream> response) throws IOException {
        try(var stream=response.body()) {
            if(response.statusCode()/100!=2)throw new Unavailable("百炼请求失败，HTTP "+response.statusCode());
            byte[] bytes=stream.readNBytes(8*1024*1024+1);
            if(bytes.length>8*1024*1024)throw new Unavailable("百炼响应超过限制");
            var result=json.readTree(bytes);
            if(result==null||!result.isObject())throw new Unavailable("百炼响应格式无效");
            return result;
        }
    }
    public static class Unavailable extends RuntimeException { public Unavailable(String message){super(message);} }
}
