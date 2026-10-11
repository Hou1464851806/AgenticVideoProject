package com.agenticvideo.business.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.auth.max-attempts=100")
class VisualApiIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6-bookworm");
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("internal.worker-token", () -> "conversation-integration-worker-token");
    }
    @LocalServerPort int port;
    @org.springframework.beans.factory.annotation.Autowired org.springframework.jdbc.core.JdbcTemplate fixtures;

    @Test
    void visualEvidenceHasPointTimesInSearchAgentContextAndCitations() throws Exception {
        BrowserApi owner=signedIn("visual-point-owner"),outsider=signedIn("visual-point-outsider");
        String project=json(owner.post("/projects","{\"name\":\"画面核查\"}")).path("data").path("id").asText();
        var source=speechFixture(project);
        assertEquals(404,outsider.post("/assets/"+source.asset()+"/vision-analysis","{}").statusCode());
        String task=json(owner.post("/assets/"+source.asset()+"/vision-analysis","{}")).path("data").path("taskId").asText();
        assertEquals(task,json(owner.post("/assets/"+source.asset()+"/vision-analysis","{}")).path("data").path("taskId").asText());
        var claim=json(internal(task,"claim",java.util.Map.of("executorId","visual-test","claimRequestId",java.util.UUID.randomUUID()))).path("data");
        var receipt=new java.util.LinkedHashMap<String,Object>();receipt.put("attemptId",claim.path("attemptId").asText());receipt.put("leaseToken",claim.path("leaseToken").asText());receipt.put("action","LOOKUP");receipt.put("data",java.util.Map.of());
        var frame=json(internal(task,"provider-call",receipt)).path("data").path("frames").get(0);
        receipt.put("action","PREPARE");receipt.put("data",java.util.Map.of("callId",frame.path("callId").asText()));assertEquals(200,internal(task,"provider-call",receipt).statusCode());assertEquals(409,internal(task,"provider-call",receipt).statusCode());
        receipt.put("action","SUCCEEDED");receipt.put("data",java.util.Map.of("callId",frame.path("callId").asText(),"observation",java.util.Map.of("description","红色矩形","visibleText",java.util.List.of("证据42")),"requestId","fixture-external-observation","usage",java.util.Map.of("total_tokens",100)));
        assertEquals(200,internal(task,"provider-call",receipt).statusCode());assertEquals(200,internal(task,"provider-call",receipt).statusCode());
        var complete=new java.util.LinkedHashMap<>(receipt);complete.remove("action");complete.remove("data");complete.put("producerKey","visual-completed");complete.put("resultType","VISION_ANALYZED");complete.put("result",java.util.Map.of());
        assertEquals(200,internal(task,"complete",complete).statusCode());assertEquals(200,internal(task,"complete",complete).statusCode());
        var found=json(owner.post("/projects/"+project+"/search","{\"query\":\"证据42\",\"literalPhrase\":true,\"sourceKinds\":[\"VISIBLE_TEXT\"]}")).path("data").path("hits");assertEquals(1,found.size());
        var hit=found.get(0);assertEquals(2000,hit.path("observedAtMs").asInt());assertEquals(false,hit.has("startMs"));String evidence=hit.path("evidenceIds").get(0).asText();
        assertEquals(404,outsider.post("/evidence/"+evidence+"/frame-access","{}").statusCode());
        var context=json(owner.get("/evidence/"+evidence+"/context?boundedWindowMs=0")).path("data").path("observations");assertEquals(1,context.size());assertEquals("FRAME_OBSERVATION",context.get(0).path("temporalPrecision").asText());
        var units=json(owner.get("/assets/"+source.asset()+"/evidence")).path("data").path("units");
        assertEquals(2,java.util.stream.StreamSupport.stream(units.spliterator(),false).filter(unit->java.util.Set.of("VISUAL","VISIBLE_TEXT").contains(unit.path("sourceKind").asText())).count());
        for(var unit:units)if("VISIBLE_TEXT".equals(unit.path("sourceKind").asText())){assertEquals(2000,unit.path("observedAtMs").asInt());assertEquals(false,unit.has("startMs"));}
        String session=json(owner.post("/projects/"+project+"/conversations","{\"title\":\"视觉查询\",\"assetIds\":[\""+source.asset()+"\"]}")).path("data").path("id").asText();
        String conversation=json(owner.post("/conversations/"+session+"/turns","{\"content\":\"找证据42\"}","visual-query")).path("data").path("taskId").asText();
        var agent=json(internal(conversation,"claim",java.util.Map.of("executorId","visual-agent","claimRequestId",java.util.UUID.randomUUID()))).path("data");
        var tool=new java.util.LinkedHashMap<String,Object>();tool.put("attemptId",agent.path("attemptId").asText());tool.put("leaseToken",agent.path("leaseToken").asText());tool.put("producerKey","visual-search");tool.put("name","search_evidence");tool.put("arguments",java.util.Map.of("query","证据42","sourceKinds",java.util.List.of("VISIBLE_TEXT")));
        var response=internal(conversation,"tool",tool);assertEquals(200,response.statusCode(),response.body());var agentHit=json(response).path("data").path("hits").get(0);assertEquals(2000,agentHit.path("observedAtMs").asInt());assertEquals(false,agentHit.has("startMs"));
        tool.put("producerKey","visual-context");tool.put("name","read_evidence_context");tool.put("arguments",java.util.Map.of("evidenceId",evidence));
        var observed=internal(conversation,"tool",tool);assertEquals(200,observed.statusCode(),observed.body());assertEquals(2000,json(observed).path("data").path("observations").get(0).path("observedAtMs").asInt());
        var answer=new java.util.LinkedHashMap<String,Object>();answer.put("attemptId",agent.path("attemptId").asText());answer.put("leaseToken",agent.path("leaseToken").asText());answer.put("producerKey","visual-answer");answer.put("resultType","CONVERSATION_ANSWERED");answer.put("result",java.util.Map.of("content","在 2 秒的观察帧看见证据42。","evidenceIds",java.util.List.of(evidence)));
        assertEquals(200,internal(conversation,"complete",answer).statusCode());
        var citation=json(owner.get("/conversations/"+session+"/messages")).path("data").path("items").get(1).path("references").get(0);
        assertEquals(2000,citation.path("observedAtMs").asInt());assertEquals(false,citation.has("startMs"));
    }
    @Test
    void cancelledVisionCannotPublishAndDoesNotDisableSpeech() throws Exception {
        BrowserApi owner=signedIn("cancelled-vision-owner");
        String project=json(owner.post("/projects","{\"name\":\"取消画面\"}")).path("data").path("id").asText();
        var source=speechFixture(project);
        String task=json(owner.post("/assets/"+source.asset()+"/vision-analysis","{}")).path("data").path("taskId").asText();
        var claim=json(internal(task,"claim",java.util.Map.of("executorId","cancel-vision","claimRequestId",java.util.UUID.randomUUID()))).path("data");
        var credentials=java.util.Map.of("attemptId",claim.path("attemptId").asText(),"leaseToken",claim.path("leaseToken").asText());
        assertEquals(202,owner.post("/tasks/"+task+"/cancel","{}").statusCode());
        assertEquals(200,internal(task,"cancelled",java.util.Map.of("attemptId",claim.path("attemptId").asText(),"leaseToken",claim.path("leaseToken").asText(),"processExited",true)).statusCode());
        var late=new java.util.LinkedHashMap<String,Object>(credentials);late.put("producerKey","late-vision");late.put("resultType","VISION_ANALYZED");late.put("result",java.util.Map.of());assertEquals(409,internal(task,"complete",late).statusCode());
        var asset=json(owner.get("/assets/"+source.asset())).path("data");assertEquals("CANCELLED",asset.path("analysis").path("stages").path("VISION").asText());assertEquals("SUCCEEDED",asset.path("analysis").path("stages").path("ASR").asText());
        assertEquals(1,json(owner.post("/projects/"+project+"/search","{\"query\":\"上传素材\",\"literalPhrase\":true}")).path("data").path("hits").size());
    }

    @Test
    void pendingAnalysisReturnsEmptyEvidenceRatherThanServerError() throws Exception {
        BrowserApi owner=signedIn("pending-visual-owner");
        String project=json(owner.post("/projects","{\"name\":\"等待分析\"}")).path("data").path("id").asText();var source=speechFixture(project);
        fixtures.update("DELETE FROM asset_analysis_heads WHERE asset_id=?",source.asset());
        fixtures.update("UPDATE assets SET validation_status='VALIDATING' WHERE id=?",source.asset());
        var response=owner.get("/assets/"+source.asset()+"/evidence");assertEquals(200,response.statusCode(),response.body());assertEquals(0,json(response).path("data").path("vision").path("observations").size());
    }

    private record Speech(java.util.UUID asset, java.util.UUID first, java.util.UUID second) {}
    private Speech speechFixture(String project) {
        var projectId=java.util.UUID.fromString(project);
        var asset=java.util.UUID.randomUUID(); var upload=java.util.UUID.randomUUID();
        var analysis=java.util.UUID.randomUUID(); var model=java.util.UUID.randomUUID(); var task=java.util.UUID.randomUUID();
        var first=java.util.UUID.randomUUID(); var second=java.util.UUID.randomUUID(); String hash="b".repeat(64);
        // Seed a previously completed ASR; all assertions observe production HTTP behavior.
        // Separate browser verification uses actual uploaded media and real model evidence.
        fixtures.update("INSERT INTO upload_sessions(id,project_id,actor_user_id,idempotency_key,filename,declared_bytes,declared_sha256,part_size_bytes,expected_parts,object_key,writer_token) SELECT ?,id,owner_user_id,?,'speech.mp4',1,?,1,1,?,? FROM projects WHERE id=?", upload,upload.toString(),hash,upload.toString(),java.util.UUID.randomUUID(),projectId);
        fixtures.update("INSERT INTO assets(id,project_id,upload_id,object_key,filename,byte_size,sha256,validation_status,duration_ms,media_info_json,time_mapping_json) VALUES (?,?,?,?,'speech.mp4',1,?,'VALID',20000,'{\"streams\":[{\"codec_type\":\"video\"},{\"codec_type\":\"audio\"}]}','{\"hasAudio\":true}')",asset,projectId,upload,asset.toString(),hash);
        fixtures.update("INSERT INTO asset_analysis_revisions(id,project_id,asset_id,source_sha256,profile_hash) VALUES (?,?,?,?,'speech-v1')",analysis,projectId,asset,hash);
        fixtures.update("INSERT INTO asset_analysis_heads(asset_id,analysis_id) VALUES (?,?)",asset,analysis);
        fixtures.update("INSERT INTO tasks(id,project_id,type,status,input_json) VALUES (?,?,'CONTENT_ANALYZE','SUCCEEDED','{}')",task,projectId);
        fixtures.update("INSERT INTO model_calls(id,analysis_id,task_id,model,parameters_json,status) VALUES (?,?,?,'fixture','{}','SUCCEEDED')",model,analysis,task);
        for(int i=0;i<2;i++) fixtures.update("INSERT INTO evidence_segments(id,project_id,asset_id,analysis_id,source_kind,ordinal,text,start_ms,end_ms,audio_start_ms,audio_end_ms,observation_json,model_call_id) VALUES (?,?,?,?,'SPEECH',?,?,?,?,?,?, '{}',?)",i==0?first:second,projectId,asset,analysis,i,i==0?"第一步，上传素材。":"第二步，核查原文。",i==0?2000:5000,i==0?5000:10000,i==0?2000:5000,i==0?5000:10000,model);
        for(var evidence:java.util.List.of(first,second)) fixtures.update("INSERT INTO retrieval_units(id,project_id,asset_id,analysis_id,source_kind,text,start_ms,end_ms,rules_hash) SELECT gen_random_uuid(),project_id,asset_id,analysis_id,source_kind,text,start_ms,end_ms,'sentence-v1' FROM evidence_segments WHERE id=?",evidence);
        fixtures.update("INSERT INTO analysis_stages(asset_id,analysis_id,stage_key,status) VALUES (?,?,'VISION','NOT_STARTED'),(?,?,'ASR','SUCCEEDED')",asset,analysis,asset,analysis);
        fixtures.update("INSERT INTO vision_frames(id,analysis_id,ordinal,observed_at_ms,source_pts,object_key,sha256,size_bytes) VALUES (?,?,0,2000,'2',?, ?,1)",java.util.UUID.randomUUID(),analysis,"derived/frames/"+asset+"/fixture/frame.jpg",hash);
        return new Speech(asset,first,second);
    }

    private HttpResponse<String> internal(String task, String action, Object body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/v1/tasks/" + task + "/" + action))
                .header("Authorization", "Bearer conversation-integration-worker-token").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(new ObjectMapper().writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private BrowserApi signedIn(String username) throws Exception {
        BrowserApi browser = new BrowserApi();
        String credentials = new ObjectMapper().writeValueAsString(java.util.Map.of("username", username, "password", "project-strong-password"));
        assertEquals(201, browser.post("/auth/register", credentials).statusCode());
        assertEquals(200, browser.post("/auth/login", credentials).statusCode());
        return browser;
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return new ObjectMapper().readTree(response.body());
    }

    private final class BrowserApi {
        final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        HttpResponse<String> get(String path) throws Exception {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> patch(String path,String body) throws Exception {
            String token=json(get("/auth/csrf")).path("data").path("csrfToken").asText();
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1"+path)).header("Content-Type","application/json").header("X-XSRF-TOKEN",token).method("PATCH",HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> post(String path, String body) throws Exception { return post(path, body, "unused"); }
        HttpResponse<String> post(String path, String body, String key) throws Exception {
            String token = json(get("/auth/csrf")).path("data").path("csrfToken").asText();
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                    .header("Content-Type", "application/json").header("X-XSRF-TOKEN", token).header("Idempotency-Key", key)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
