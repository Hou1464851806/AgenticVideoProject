package com.agenticvideo.business.render;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

/** Snake-case internal transport only; public preview responses keep their existing shape. */
public final class PreviewContracts {
    private PreviewContracts() {}
    public record Request(String output_key) {}
    public enum TaskStatus { PENDING, RUNNING, CANCEL_REQUESTED, SUCCEEDED, FAILED, CANCELLED }
    public enum ArtifactState { AVAILABLE, UNAVAILABLE, REMOVED }
    public record Artifact(UUID artifact_id,ArtifactState state,long duration_ms) {}
    public record Playback(String url,int expires_in_seconds) {}
    public record Error(String code,String message) {}
    public record Result(UUID request_id,UUID project_id,UUID revision_id,UUID output_id,UUID task_id,
                         TaskStatus task_status,String created_at,Artifact artifact,Playback playback,
                         boolean retry_allowed,Error error) {}
    public record ListItem(UUID request_id,String output_key,UUID output_id,UUID task_id,TaskStatus task_status,String created_at) {}
    public record ListResult(UUID requirement_id,UUID revision_id,List<ListItem> items) {}

    public static Request request(JsonNode input) {
        if(!input.isObject())throw new IllegalArgumentException("预览请求必须是 JSON 对象");
        input.fieldNames().forEachRemaining(field->{
            if(!field.equals("output_key"))throw new IllegalArgumentException("预览请求包含不受支持的字段："+field);
        });
        var key=input.get("output_key");
        if(key!=null && (!key.isTextual() || key.asText().isEmpty() || key.asText().codePointCount(0,key.asText().length())>64))
            throw new IllegalArgumentException("output_key 必须为 1–64 个字符");
        return new Request(key==null?null:key.asText());
    }
}
