package com.agenticvideo.business.bailian;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class BailianVision {
    private final BailianHttp http;private final ObjectMapper json;
    public BailianVision(BailianHttp http,ObjectMapper json){this.http=http;this.json=json;}
    public JsonNode observe(byte[] image,String model) {
        String prompt="只观察输入的单张图片，不推断音频、前后事件或持续区间。返回严格JSON对象，description为实际画面场景与物体的中文描述，不混入屏幕文字；visibleText为可见文字逐条原样转写的字符串数组（包括数字），没有文字返回空数组。格式：{\"description\":\"...\",\"visibleText\":[\"...\"]}。";
        return http.request("POST","/services/aigc/multimodal-generation/generation",Map.of("model",model,"input",Map.of("messages",List.of(Map.of("role","user","content",List.of(Map.of("image","data:image/jpeg;base64,"+Base64.getEncoder().encodeToString(image)),Map.of("text",prompt))))),"parameters",Map.of("enable_thinking",false,"max_tokens",1200)),Map.of());
    }
    public JsonNode observation(JsonNode response) {
        var text=new StringBuilder();for(var item:response.path("output").path("choices").path(0).path("message").path("content"))text.append(item.path("text").asText());
        String content=text.toString().strip();
        if(content.startsWith("```"))content=content.substring(content.indexOf('\n')+1,content.lastIndexOf("```")).strip();
        try {var observation=json.readTree(content);if(observation==null)throw new java.io.IOException();return observation;}
        catch(java.io.IOException invalid){throw new BailianHttp.Unavailable("视觉响应不能解析为观察");}
    }
}
