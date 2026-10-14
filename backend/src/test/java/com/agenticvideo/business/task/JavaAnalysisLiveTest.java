package com.agenticvideo.business.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit paid acceptance. Uses real Bailian clients, including private ASR transfer. */
@EnabledIfEnvironmentVariable(named="RUN_LIVE_JAVA_ANALYSIS",matches="1")
class JavaAnalysisLiveTest extends AnalysisIntegrationSupport {
    @DynamicPropertySource static void provider(DynamicPropertyRegistry properties) {
        properties.add("DASHSCOPE_API_KEY",()->Objects.requireNonNull(System.getenv("DASHSCOPE_API_KEY")));
        properties.add("DASHSCOPE_BASE_URL",()->System.getenv().getOrDefault("DASHSCOPE_BASE_URL","https://dashscope.aliyuncs.com/api/v1"));
    }
    @Test void labelledSpeechRetainsSourceTimingAndNativeSearch() throws Exception {
        var uploaded=upload("labelled-speech.mp4");awaitAnalysis(uploaded);
        String text=String.join("",db.queryForList("SELECT text FROM evidence_segments WHERE asset_id=? AND source_kind='SPEECH' ORDER BY start_ms",String.class,uploaded.asset()));
        assertTrue(text.contains("上传")&&text.contains("导出"),text);
        assertTrue(db.queryForObject("SELECT min(start_ms)>=1436 AND bool_and(start_ms=audio_start_ms+1436) FROM evidence_segments WHERE asset_id=? AND source_kind='SPEECH'",Boolean.class,uploaded.asset()));
        assertPublishedAndQueryable(uploaded,"上传素材");
    }
    @Test void naturalSpeechProducesRecognizableEvidence() throws Exception {
        var uploaded=upload("human-speech.mp4");awaitAnalysis(uploaded);
        String text=String.join("",db.queryForList("SELECT text FROM evidence_segments WHERE asset_id=? AND source_kind='SPEECH'",String.class,uploaded.asset()));
        assertTrue(text.contains("告别")&&text.contains("开始"),text);
        assertPublishedAndQueryable(uploaded,"告别");
    }
    @Test void silentVfrFramesPublishPointsAndVisibleText() throws Exception {
        var uploaded=upload("labelled-vision-vfr.mp4");awaitAnalysis(uploaded);
        assertEquals("NOT_APPLICABLE",db.queryForObject("SELECT status FROM analysis_stages WHERE asset_id=? AND stage_key='ASR'",String.class,uploaded.asset()));
        String text=String.join("",db.queryForList("SELECT text FROM evidence_segments WHERE asset_id=? AND source_kind='VISIBLE_TEXT'",String.class,uploaded.asset()));
        assertTrue(text.contains("证据42")&&text.contains("画面17"),text);
        assertEquals(0,db.queryForObject("SELECT count(*) FROM evidence_segments WHERE asset_id=? AND start_ms<>end_ms",Integer.class,uploaded.asset()));
        assertEquals(List.of(0L,2320L,4760L,7080L),db.queryForList("SELECT observed_at_ms FROM vision_frames WHERE analysis_id=(SELECT analysis_id FROM asset_analysis_heads WHERE asset_id=?) ORDER BY observed_at_ms",Long.class,uploaded.asset()));
        assertFalse(new ObjectMapper().valueToTree(speech.evidence(uploaded.asset())).path("vision").path("coverage").path("complete").asBoolean(true));
        assertPublishedAndQueryable(uploaded,"证据42");
    }
    @Test void audioMentionDoesNotBecomeVisualFact() throws Exception {
        var uploaded=upload("speech-mention-only.mp4");awaitAnalysis(uploaded);
        String speechText=String.join("",db.queryForList("SELECT text FROM evidence_segments WHERE asset_id=? AND source_kind='SPEECH'",String.class,uploaded.asset()));
        assertTrue(speechText.contains("猫"),speechText);
        String visual=String.join("",db.queryForList("SELECT text FROM evidence_segments WHERE asset_id=? AND source_kind='VISUAL'",String.class,uploaded.asset()));
        assertFalse(visual.contains("猫"),visual);
        assertPublishedAndQueryable(uploaded,"矩形");
    }
    private String questionHttp(String path,Object body) throws Exception {
        var response=java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:"+port+"/internal/v1/questions"+path))
                .header("Authorization","Bearer internal-integration-worker-token").header("Content-Type","application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(new ObjectMapper().writeValueAsString(body))).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode()==200||response.statusCode()==201,response.body());return response.body();
    }
    private com.fasterxml.jackson.databind.JsonNode readTool(String scope,String call,String tool,Object arguments) throws Exception {
        var json=new ObjectMapper();var input=json.createObjectNode().put("tool",tool);input.set("args",json.valueToTree(arguments));
        var request=Map.of("call_id",call,"tool_name",tool,"arguments",arguments,"request_hash",com.agenticvideo.business.tools.ToolJson.hash(input));
        String body=questionHttp("/scopes/"+scope+"/tools",request);
        assertTrue(body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=32768);
        var result=json.readTree(body);assertEquals("READY",result.path("status").asText(),body);assertFalse(result.path("mock").asBoolean(true));return result;
    }
    private void assertPublishedAndQueryable(Uploaded uploaded,String query) throws Exception {
        var generation=db.queryForMap("SELECT g.* FROM search_index_heads h JOIN search_index_generations g ON g.id=h.generation_id WHERE h.asset_id=?",uploaded.asset());
        assertEquals("PUBLISHED",generation.get("status"));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM retrieval_units u WHERE u.asset_id=? AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(?::jsonb) p WHERE p->>'unitId'=u.id::text)",Integer.class,uploaded.asset(),generation.get("units_json").toString()));
        var json=new ObjectMapper();
        var snapshot=json.readTree(questionHttp("/scopes",Map.of("project_id",uploaded.project(),"asset_ids",List.of(uploaded.asset()))));
        String scope=snapshot.path("scope_id").asText();
        var listing=readTool(scope,"assets","list_assets",Map.of());
        assertEquals(uploaded.asset().toString(),listing.path("data").path("assets").get(0).path("id").asText());
        var overview=readTool(scope,"overview","get_video_overview",Map.of("asset_id",uploaded.asset()));
        assertFalse(overview.path("data").path("entries").isEmpty());assertTrue(overview.path("records").isEmpty());
        long duration=db.queryForObject("SELECT duration_ms FROM assets WHERE id=?",Long.class,uploaded.asset());
        var range=readTool(scope,"range","read_video_range",Map.of("asset_id",uploaded.asset(),"start_ms",0,"end_ms",duration));
        assertEquals(db.queryForObject("SELECT count(*) FROM evidence_segments WHERE asset_id=?",Integer.class,uploaded.asset()),range.path("records").size());
        for(var record:range.path("records"))if(!"SPEECH".equals(record.path("source_kind").asText())){assertTrue(record.has("observed_at_ms"));assertFalse(record.has("end_ms"));}
        var conditions=Map.of("query",query,"source_kinds",List.of("SPEECH","VISUAL","VISIBLE_TEXT"));
        var search=readTool(scope,"search","search_evidence",conditions);
        assertFalse(search.path("records").isEmpty());
        assertEquals("AVAILABLE",search.path("data").path("channels").path("DENSE").path("status").asText());
        assertEquals("AVAILABLE",search.path("data").path("channels").path("BM25").path("status").asText());
        assertEquals(search,readTool(scope,"search","search_evidence",conditions));
        readTool(scope,"search-replay","search_evidence",conditions);
        assertEquals(1,db.queryForObject("SELECT count(*) FROM query_model_calls q JOIN question_searches s ON s.id=q.question_search_id WHERE s.scope_id=?",Integer.class,UUID.fromString(scope)));
        // Retain real identities and raw ToolResult JSON for Python contract checks, outside prompts and logs.
        var directory=java.nio.file.Path.of("target/m1-tools-live",uploaded.asset().toString());java.nio.file.Files.createDirectories(directory);
        java.nio.file.Files.writeString(directory.resolve("scope.json"),snapshot.toString());
        java.nio.file.Files.writeString(directory.resolve("results.json"),json.valueToTree(List.of(listing,overview,range,search)).toString());
        var receipts=new LinkedHashMap<String,List<Map<String,Object>>>();
        receipts.put("asr",db.queryForList("SELECT id,model,status,provider_task_id,request_id,usage_json FROM model_calls WHERE analysis_id=?",generation.get("analysis_id")));
        receipts.put("vision",db.queryForList("SELECT id,model,status,request_id,usage_json FROM vision_model_calls WHERE analysis_id=?",generation.get("analysis_id")));
        receipts.put("document_embeddings",db.queryForList("SELECT ordinal,status,request_id,usage_json FROM embedding_batches WHERE generation_id=? ORDER BY ordinal",generation.get("id")));
        receipts.put("query_embeddings",db.queryForList("SELECT q.id,q.model,q.status,q.request_id,q.usage_json FROM query_model_calls q JOIN question_searches s ON s.id=q.question_search_id WHERE s.scope_id=?",UUID.fromString(scope)));
        // JDBC JSONB values are converted to structured JSON rather than driver metadata.
        for(var rows:receipts.values())for(var row:rows)if(row.get("usage_json")!=null)row.put("usage_json",json.readTree(row.get("usage_json").toString()));
        java.nio.file.Files.writeString(directory.resolve("model-receipts.json"),json.valueToTree(receipts).toString());
        assertEquals(0,db.queryForObject("SELECT count(*) FROM task_attempts a JOIN tasks t ON t.id=a.task_id WHERE input_json->>'assetId'=? AND NOT a.local_execution",Integer.class,uploaded.asset().toString()));
        System.out.println("JAVA_M1_LIVE_VERIFIED fixture="+db.queryForObject("SELECT filename FROM assets WHERE id=?",String.class,uploaded.asset())+" evidence="+db.queryForObject("SELECT count(*) FROM evidence_segments WHERE asset_id=?",Integer.class,uploaded.asset())+" generation="+generation.get("id")+" scope="+scope+" query="+search.path("data").path("query_id").asText()+" tools=4 mock=false");
    }
}
