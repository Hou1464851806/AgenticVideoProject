package com.agenticvideo.business.analysis;

import com.agenticvideo.business.bailian.*;
import com.agenticvideo.business.media.MediaProcessing;
import com.agenticvideo.business.search.SearchIndexBuilder;
import com.agenticvideo.business.task.TaskExecution;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Concrete analysis workflows. Every provider action is a short transaction around a remote call. */
@Service
public class AnalysisProcessing {
    private final TaskExecution tasks;private final ObjectMapper json;private final MediaProcessing media;
    private final BailianHttp http;private final BailianAsr asr;private final BailianVision vision;private final BailianEmbedding embedding;
    private final SearchIndexBuilder builder;private final int querySeconds;
    public AnalysisProcessing(TaskExecution tasks,ObjectMapper json,MediaProcessing media,BailianHttp http,BailianAsr asr,BailianVision vision,BailianEmbedding embedding,SearchIndexBuilder builder,
            @Value("${analysis.asr-query-seconds:5}") int querySeconds) {
        this.tasks=tasks;this.json=json;this.media=media;this.http=http;this.asr=asr;this.vision=vision;this.embedding=embedding;this.builder=builder;this.querySeconds=Math.max(1,querySeconds);
    }
    public void execute(String type,TaskExecution.LocalRun run,BooleanSupplier stopped) throws Exception {
        switch(type) {
            case "CONTENT_ANALYZE" -> speech(run,stopped);
            case "VISION_ANALYZE" -> visual(run,stopped);
            case "INDEX_BUILD" -> index(run,stopped);
            default -> throw new IllegalArgumentException("没有此分析操作");
        }
    }
    private JsonNode record(TaskExecution.LocalRun run,String action,Object data) {
        return json.valueToTree(tasks.providerLocal(run,action,json.valueToTree(data)));
    }
    private JsonNode lookup(TaskExecution.LocalRun run){return record(run,"LOOKUP",Map.of());}
    private void speech(TaskExecution.LocalRun run,BooleanSupplier stopped) throws Exception {
        check(stopped);var call=lookup(run);String status=call.path("status").asText();
        if("SUCCEEDED".equals(status)){completeSpeech(run,call);return;}
        if("READY".equals(status)) {
            http.requireConfigured();
            String address=media.transferAudio(run.attemptId(),run.input().path("audio"),stopped,path->asr.upload(path,run.input().path("model").asText()));
            check(stopped);var grant=record(run,"PREPARE",Map.of());
            if(!grant.path("canSubmit").asBoolean())throw new PaidUncertain();
            try {
                var response=asr.submit(address,run.input().path("model").asText());
                record(run,"RESPONSE",response);
                record(run,"SUBMITTED",Map.of("providerTaskId",response.path("output").path("task_id").asText(),"requestId",response.path("request_id").asText()));
            }catch(RuntimeException uncertain){record(run,"UNCERTAIN",Map.of());throw new PaidUncertain();}
            check(stopped);tasks.deferLocal(run,querySeconds);return;
        }
        // A saved submit response can recover the remote task identity without a second payment.
        if(Set.of("SUBMITTING","UNCERTAIN").contains(status) && call.path("response").path("output").hasNonNull("task_id")) {
            record(run,"SUBMITTED",Map.of("providerTaskId",call.path("response").path("output").path("task_id").asText(),"requestId",call.path("response").path("request_id").asText()));
            call=lookup(run);status=call.path("status").asText();
        }
        if(!"SUBMITTED".equals(status))throw new PaidUncertain();
        JsonNode response=call.path("response");
        if(!Set.of("SUCCEEDED","FAILED","UNKNOWN").contains(response.path("output").path("task_status").asText())) {
            check(stopped);response=asr.query(call.path("providerTaskId").asText());record(run,"RESPONSE",response);
        }
        String remote=response.path("output").path("task_status").asText();
        if("SUCCEEDED".equals(remote)) {
            check(stopped);var transcript=asr.transcript(response);
            record(run,"SUCCEEDED",Map.of("providerTaskId",call.path("providerTaskId").asText(),"transcript",transcript,"usage",response.path("usage")));
            check(stopped);completeSpeech(run,lookup(run));
        }else if(Set.of("FAILED","UNKNOWN").contains(remote)) {
            record(run,"FAILED",Map.of("providerTaskId",call.path("providerTaskId").asText(),"failure",Map.of("status",remote,"code",response.path("output").path("code").asText("UNKNOWN")),"usage",response.path("usage")));
            throw new InvalidResult("百炼语音任务已终止");
        }else if(Set.of("PENDING","RUNNING").contains(remote)) {
            if(call.path("deadlineExceeded").asBoolean())throw new InvalidResult("语音任务查询已超过30分钟；保留提供方身份，可核查后继续查询");
            check(stopped);tasks.deferLocal(run,querySeconds);
        }else throw new BailianHttp.Unavailable("百炼语音状态未知，保留原任务身份");
    }
    private void completeSpeech(TaskExecution.LocalRun run,JsonNode call) {tasks.completeLocal(run,"SPEECH_ANALYZED",Map.of("modelCallId",call.path("id").asText()));}
    private void visual(TaskExecution.LocalRun run,BooleanSupplier stopped) throws Exception {
        var manifest=lookup(run);var seen=new HashSet<String>();
        for(var frame:manifest.path("frames")) {
            check(stopped);String id=frame.path("callId").asText();
            if(!seen.add(id)||"SUCCEEDED".equals(frame.path("status").asText()))continue;
            JsonNode response=frame.path("response");
            if(response.isObject())record(run,"RESUME_RESPONSE",Map.of("callId",id));
            else {
                if(!"READY".equals(frame.path("status").asText()))throw new PaidUncertain();
                http.requireConfigured();byte[] image=media.readFrame(frame,stopped);check(stopped);
                record(run,"PREPARE",Map.of("callId",id));
                try {response=vision.observe(image,manifest.path("model").asText());record(run,"RESPONSE",Map.of("callId",id,"response",response));}
                catch(RuntimeException uncertain){record(run,"UNCERTAIN",Map.of("callId",id));throw new PaidUncertain();}
            }
            try {record(run,"SUCCEEDED",Map.of("callId",id,"observation",vision.observation(response),"requestId",response.path("request_id").asText(),"usage",response.path("usage")));}
            catch(RuntimeException invalid){throw new InvalidResult("视觉响应已保存，但观察内容或用量无效；未重复付费");}
        }
        check(stopped);tasks.completeLocal(run,"VISION_ANALYZED",Map.of());
    }
    private void index(TaskExecution.LocalRun run,BooleanSupplier stopped) throws Exception {
        var profile=lookup(run);
        for(var batch:profile.path("batches")) {
            check(stopped);if("SUCCEEDED".equals(batch.path("status").asText()))continue;
            int ordinal=batch.path("ordinal").asInt();JsonNode response=batch.path("response");
            if(response.isObject())record(run,"RESUME_RESPONSE",Map.of("ordinal",ordinal));
            else {
                if(!"READY".equals(batch.path("status").asText()))throw new PaidUncertain();
                http.requireConfigured();var texts=new ArrayList<String>();for(var unit:batch.path("units"))texts.add(unit.path("text").asText());
                check(stopped);record(run,"PREPARE",Map.of("ordinal",ordinal));
                try {response=embedding.embed(texts,profile.path("model").asText(),profile.path("dimension").asInt(),"document");record(run,"RESPONSE",Map.of("ordinal",ordinal,"response",response));}
                catch(RuntimeException uncertain){throw new PaidUncertain();}
            }
            try {record(run,"SUCCEEDED",Map.of("ordinal",ordinal,"vectors",embedding.vectors(response,batch.path("units").size()),"requestId",response.path("request_id").asText(),"usage",response.path("usage")));}
            catch(RuntimeException invalid){throw new InvalidResult("Embedding 响应已保存，但向量或用量无效；未重复付费");}
        }
        check(stopped);record(run,"INDEXING",Map.of());profile=lookup(run);builder.write(profile,stopped);
        tasks.completeLocal(run,"INDEX_BUILT",Map.of("generationId",profile.path("generationId").asText()));
    }
    private static void check(BooleanSupplier stopped) throws InterruptedException {if(stopped.getAsBoolean())throw new InterruptedException("分析执行已停止");}
    public static class PaidUncertain extends RuntimeException {public PaidUncertain(){super("付费模型提交结果不明确，停止自动重付；请核查原回执");}}
    public static class InvalidResult extends RuntimeException {public InvalidResult(String message){super(message);}}
}
