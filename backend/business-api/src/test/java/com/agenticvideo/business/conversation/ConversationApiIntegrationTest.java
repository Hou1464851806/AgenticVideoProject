package com.agenticvideo.business.conversation;

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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConversationApiIntegrationTest {
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
    void multipleSessionsKeepIndependentHistoryAndPrivateOwnershipWithoutAPlan() throws Exception {
        BrowserApi owner = signedIn("conversation-owner");
        BrowserApi outsider = signedIn("conversation-outsider");
        String project = json(owner.post("/projects", "{\"name\":\"查询素材\"}")).path("data").path("id").asText();
        var first = owner.post("/projects/" + project + "/conversations", "{\"title\":\"寻找主题\",\"assetIds\":[]}");
        assertEquals(201, first.statusCode(), first.body());
        String firstId = json(first).path("data").path("id").asText();
        var second = owner.post("/projects/" + project + "/conversations", "{\"title\":\"查找人名\",\"assetIds\":[]}");
        assertEquals(201, second.statusCode(), second.body());
        String secondId = json(second).path("data").path("id").asText();
        org.junit.jupiter.api.Assertions.assertNotEquals(firstId, secondId);
        assertEquals(2, json(owner.get("/projects/" + project + "/conversations")).path("data").path("items").size());
        for (String id : java.util.List.of(firstId, secondId)) {
            var history = owner.get("/conversations/" + id + "/messages");
            assertEquals(200, history.statusCode(), history.body());
            assertEquals(0, json(history).path("data").path("items").size());
            assertEquals(404, outsider.get("/conversations/" + id + "/messages").statusCode());
        }
        assertEquals(404, outsider.get("/projects/" + project + "/conversations").statusCode());
        assertEquals(404, outsider.post("/projects/" + project + "/conversations", "{\"title\":\"侵入\",\"assetIds\":[]}").statusCode());
    }

    @Test
    void acceptedTurnIsIdempotentAndSerializesConcurrentSessionRequests() throws Exception {
        BrowserApi owner = signedIn("turn-owner");
        String project = json(owner.post("/projects", "{\"name\":\"轮次接纳\"}")).path("data").path("id").asText();
        String session = json(owner.post("/projects/" + project + "/conversations", "{\"title\":\"查询\"}")).path("data").path("id").asText();
        String path = "/conversations/" + session + "/turns";
        String body = "{\"content\":\"介绍素材中的制作步骤\",\"references\":[],\"context\":{}}";
        var accepted = owner.post(path, body, "first-query");
        assertEquals(202, accepted.statusCode(), accepted.body());
        JsonNode identities = json(accepted).path("data");
        assertEquals("PENDING", json(owner.get("/tasks/" + identities.path("taskId").asText())).path("data").path("status").asText());
        assertEquals(identities, json(owner.post(path, body, "first-query")).path("data"));
        assertEquals(409, owner.post(path, "{\"content\":\"不同内容\"}", "first-query").statusCode());
        var competing = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try { return owner.post(path, body, "second-query").statusCode(); }
            catch (Exception failure) { throw new RuntimeException(failure); }
        });
        assertEquals(409, owner.post(path, body, "third-query").statusCode());
        assertEquals(409, competing.get());
        JsonNode history = json(owner.get("/conversations/" + session + "/messages")).path("data").path("items");
        assertEquals(1, history.size());
        assertEquals("介绍素材中的制作步骤", history.get(0).path("content").asText());
        assertEquals(identities.path("messageId").asText(), history.get(0).path("id").asText());
        String other = json(owner.post("/projects/" + project + "/conversations", "{\"title\":\"独立查询\"}")).path("data").path("id").asText();
        assertEquals(202, owner.post("/conversations/" + other + "/turns", body, "first-query").statusCode());
    }

    @Test
    void nonexistentOrUnrecognizedReferencesAreRejectedBeforeAcceptingAMessage() throws Exception {
        BrowserApi owner = signedIn("reference-owner");
        String project = json(owner.post("/projects", "{\"name\":\"固定范围\"}")).path("data").path("id").asText();
        String session = json(owner.post("/projects/" + project + "/conversations", "{\"title\":\"引用核查\"}")).path("data").path("id").asText();
        String path = "/conversations/" + session + "/turns";
        assertEquals(404, owner.post(path, "{\"content\":\"查这个素材\",\"references\":[{\"type\":\"ASSET\",\"id\":\"00000000-0000-0000-0000-000000000001\"}]}", "bad-reference").statusCode());
        assertEquals(400, owner.post(path, "{\"content\":\"执行命令\",\"references\":[{\"type\":\"SHELL\",\"id\":\"ls\"}]}", "unknown-reference").statusCode());
        assertEquals(400, owner.post(path, "{\"content\":\"读取别的会话\",\"context\":{\"conversationId\":\"00000000-0000-0000-0000-000000000001\"}}", "bad-context").statusCode());
        assertEquals(0, json(owner.get("/conversations/" + session + "/messages")).path("data").path("items").size());
        assertEquals(202, owner.post(path, "{\"content\":\"查询范围内的素材\"}", "valid-reference").statusCode());
    }

    @Test
    void onlyCurrentAttemptCanPublishOnePublicAnswerAndDuplicatePublicationReturnsReceipt() throws Exception {
        BrowserApi owner = signedIn("publication-owner");
        String project = json(owner.post("/projects", "{\"name\":\"公开答复\"}")).path("data").path("id").asText();
        String session = json(owner.post("/projects/" + project + "/conversations", "{\"title\":\"空范围查询\"}")).path("data").path("id").asText();
        String task = json(owner.post("/conversations/" + session + "/turns", "{\"content\":\"找到制作步骤\"}", "publish-query")).path("data").path("taskId").asText();
        var claim = internal(task, "claim", java.util.Map.of("executorId", "conversation-test", "claimRequestId", java.util.UUID.randomUUID()));
        assertEquals(200, claim.statusCode(), claim.body());
        JsonNode credentials = json(claim).path("data");
        assertEquals(session, credentials.path("input").path("conversationId").asText());
        assertEquals("找到制作步骤", credentials.path("input").path("content").asText());
        var body = new java.util.HashMap<String,Object>();
        body.put("attemptId", java.util.UUID.randomUUID()); body.put("leaseToken", credentials.path("leaseToken").asText());
        body.put("producerKey", "answer-one"); body.put("resultType", "CONVERSATION_ANSWERED");
        body.put("result", java.util.Map.of("content", "当前素材范围内没有语音证据，无法确定制作步骤。", "evidenceIds", java.util.List.of()));
        assertEquals(409, internal(task, "complete", body).statusCode());
        assertEquals(1, json(owner.get("/conversations/" + session + "/messages")).path("data").path("items").size());
        body.put("attemptId", credentials.path("attemptId").asText());
        var completed = internal(task, "complete", body);
        assertEquals(200, completed.statusCode(), completed.body());
        assertEquals(json(completed).path("data"), json(internal(task, "complete", body)).path("data"));
        assertEquals("SUCCEEDED", json(owner.get("/tasks/" + task)).path("data").path("status").asText());
        JsonNode history = json(owner.get("/conversations/" + session + "/messages")).path("data").path("items");
        assertEquals(2, history.size());
        assertEquals("ASSISTANT", history.get(1).path("role").asText());
        assertEquals("当前素材范围内没有语音证据，无法确定制作步骤。", history.get(1).path("content").asText());
        body.put("result", java.util.Map.of("content", "改变结果", "evidenceIds", java.util.List.of()));
        assertEquals(409, internal(task, "complete", body).statusCode());
    }

    @Test
    void agentReadsOnlyAuthorizedBusinessToolsUnderItsCurrentLease() throws Exception {
        BrowserApi owner = signedIn("tool-owner");
        String project = json(owner.post("/projects", "{\"name\":\"工具权限\"}")).path("data").path("id").asText();
        String session = json(owner.post("/projects/" + project + "/conversations", "{\"title\":\"受控查询\",\"assetIds\":[]}")).path("data").path("id").asText();
        String task = json(owner.post("/conversations/" + session + "/turns", "{\"content\":\"找制作步骤\"}", "tool-query")).path("data").path("taskId").asText();
        JsonNode claim = json(internal(task, "claim", java.util.Map.of("executorId", "tool-test", "claimRequestId", java.util.UUID.randomUUID()))).path("data");
        var request = new java.util.HashMap<String,Object>();
        request.put("attemptId", claim.path("attemptId").asText()); request.put("leaseToken", claim.path("leaseToken").asText());
        request.put("producerKey", "search-one"); request.put("name", "search_evidence"); request.put("arguments", java.util.Map.of("query", "步骤"));
        var reply = internal(task, "tool", request);
        assertEquals(200, reply.statusCode(), reply.body());
        assertEquals(0, json(reply).path("data").path("hits").size());
        assertEquals(json(reply).path("data"), json(internal(task, "tool", request)).path("data"));
        request.put("producerKey", "unknown-tool"); request.put("name", "shell");
        assertEquals(400, internal(task, "tool", request).statusCode());
        request.put("producerKey", "forged-project"); request.put("name", "search_evidence");
        request.put("arguments", java.util.Map.of("query", "步骤", "projectId", java.util.UUID.randomUUID()));
        assertEquals(400, internal(task, "tool", request).statusCode());
        request.put("arguments", java.util.Map.of("query", "步骤")); request.put("attemptId", java.util.UUID.randomUUID());
        assertEquals(409, internal(task, "tool", request).statusCode());
    }

    @Test
    void paidModelSubmissionGrantCannotBeReusedAndToolBudgetCannotBeReset() throws Exception {
        BrowserApi owner = signedIn("budget-owner");
        String project = json(owner.post("/projects", "{\"name\":\"调用预算\"}")).path("data").path("id").asText();
        String session = json(owner.post("/projects/" + project + "/conversations", "{\"title\":\"预算核查\"}")).path("data").path("id").asText();
        String task = json(owner.post("/conversations/" + session + "/turns", "{\"content\":\"查找素材\"}", "budget-query")).path("data").path("taskId").asText();
        JsonNode claim = json(internal(task, "claim", java.util.Map.of("executorId", "budget-test", "claimRequestId", java.util.UUID.randomUUID()))).path("data");
        var request = new java.util.HashMap<String,Object>();
        request.put("attemptId", claim.path("attemptId").asText()); request.put("leaseToken", claim.path("leaseToken").asText());
        request.put("action", "PREPARE"); request.put("data", java.util.Map.of("callNumber",1,"request",java.util.Map.of("model","deepseek-flash","messages",java.util.List.of())));
        var grant = internal(task,"provider-call",request);
        assertEquals(200,grant.statusCode(),grant.body());
        assertEquals(true,json(grant).path("data").path("canSubmit").asBoolean());
        var repeated = json(internal(task,"provider-call",request)).path("data");
        assertEquals(false,repeated.path("canSubmit").asBoolean());
        assertEquals("UNCERTAIN",repeated.path("status").asText());
        request.put("data", java.util.Map.of("callNumber",7,"request",java.util.Map.of("model","deepseek-flash")));
        assertEquals(409,internal(task,"provider-call",request).statusCode());
        request.remove("action"); request.remove("data"); request.put("name","list_assets"); request.put("arguments",java.util.Map.of());
        for (int call=1;call<=12;call++) {
            request.put("producerKey","bounded-tool-"+call);
            assertEquals(200,internal(task,"tool",request).statusCode());
        }
        request.put("producerKey","bounded-tool-13");
        assertEquals(409,internal(task,"tool",request).statusCode());
        request.put("producerKey","bounded-tool-1");
        assertEquals(200,internal(task,"tool",request).statusCode());
    }

    @Test
    void explicitEvidenceReferenceCannotBePublishedUntilThisTurnReadsItsEvidence() throws Exception {
        BrowserApi owner = signedIn("citation-provenance-owner");
        String project = json(owner.post("/projects", "{\"name\":\"引用来源核查\"}")).path("data").path("id").asText();
        java.util.UUID projectId = java.util.UUID.fromString(project);
        java.util.UUID asset = java.util.UUID.randomUUID(), upload = java.util.UUID.randomUUID();
        java.util.UUID analysis = java.util.UUID.randomUUID(), model = java.util.UUID.randomUUID(), fixtureTask = java.util.UUID.randomUUID();
        java.util.UUID evidence = java.util.UUID.randomUUID();
        String hash = "a".repeat(64);
        // Seed already-published speech observations: producing them through the public API
        // requires Silo/media/model services. All behavior under test uses real HTTP/PostgreSQL.
        fixtures.update("INSERT INTO upload_sessions(id,project_id,actor_user_id,idempotency_key,filename,declared_bytes,declared_sha256,part_size_bytes,expected_parts,object_key,writer_token) SELECT ?,id,owner_user_id,?,'speech.mp4',1,?,1,1,?,? FROM projects WHERE id=?",
                upload, upload.toString(), hash, upload.toString(), java.util.UUID.randomUUID(), projectId);
        fixtures.update("INSERT INTO assets(id,project_id,upload_id,object_key,filename,byte_size,sha256,validation_status) VALUES (?,?,?,?,'speech.mp4',1,?,'VALID')",
                asset, projectId, upload, asset.toString(), hash);
        fixtures.update("INSERT INTO asset_analysis_revisions(id,project_id,asset_id,source_sha256,profile_hash) VALUES (?,?,?,?,'speech-v1')", analysis, projectId, asset, hash);
        fixtures.update("INSERT INTO asset_analysis_heads(asset_id,analysis_id) VALUES (?,?)", asset, analysis);
        fixtures.update("INSERT INTO tasks(id,project_id,type,status,input_json) VALUES (?,?,'CONTENT_ANALYZE','SUCCEEDED','{}')", fixtureTask, projectId);
        fixtures.update("INSERT INTO model_calls(id,analysis_id,task_id,model,parameters_json,status) VALUES (?,?,?,'fixture','{}','SUCCEEDED')", model, analysis, fixtureTask);
        fixtures.update("INSERT INTO evidence_segments(id,project_id,asset_id,analysis_id,source_kind,ordinal,text,start_ms,end_ms,audio_start_ms,audio_end_ms,observation_json,model_call_id) VALUES (?,?,?,?,'SPEECH',0,'先上传素材',0,1000,0,1000,'{}',?)",
                evidence, projectId, asset, analysis, model);
        String session = json(owner.post("/projects/" + project + "/conversations", new ObjectMapper().writeValueAsString(java.util.Map.of("title", "引用核查", "assetIds", java.util.List.of(asset))))).path("data").path("id").asText();
        String turnBody = new ObjectMapper().writeValueAsString(java.util.Map.of("content", "核查这条原句", "references", java.util.List.of(java.util.Map.of("type", "EVIDENCE", "id", evidence))));
        String task = json(owner.post("/conversations/" + session + "/turns", turnBody, "citation-query")).path("data").path("taskId").asText();
        JsonNode claim = json(internal(task, "claim", java.util.Map.of("executorId", "citation-test", "claimRequestId", java.util.UUID.randomUUID()))).path("data");
        var publication = new java.util.HashMap<String,Object>();
        publication.put("attemptId", claim.path("attemptId").asText()); publication.put("leaseToken", claim.path("leaseToken").asText());
        publication.put("producerKey", "citation-answer"); publication.put("resultType", "CONVERSATION_ANSWERED");
        publication.put("result", java.util.Map.of("content", "第一步是上传素材。", "evidenceIds", java.util.List.of(evidence)));
        var unread = internal(task, "complete", publication);
        assertEquals(409, unread.statusCode(), unread.body());
        assertEquals(1, json(owner.get("/conversations/" + session + "/messages")).path("data").path("items").size());
        var read = new java.util.HashMap<String,Object>();
        read.put("attemptId", claim.path("attemptId").asText()); read.put("leaseToken", claim.path("leaseToken").asText());
        read.put("producerKey", "citation-read"); read.put("name", "read_evidence_context"); read.put("arguments", java.util.Map.of("evidenceId", evidence));
        var observed = internal(task, "tool", read);
        assertEquals(200, observed.statusCode(), observed.body());
        assertEquals(evidence.toString(), json(observed).path("data").path("observations").get(0).path("evidenceId").asText());
        var completed = internal(task, "complete", publication);
        assertEquals(200, completed.statusCode(), completed.body());
        assertEquals(evidence.toString(), json(owner.get("/conversations/" + session + "/messages")).path("data").path("items").get(1).path("references").get(0).path("id").asText());
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
        HttpResponse<String> post(String path, String body) throws Exception { return post(path, body, "unused"); }
        HttpResponse<String> post(String path, String body, String key) throws Exception {
            String token = json(get("/auth/csrf")).path("data").path("csrfToken").asText();
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                    .header("Content-Type", "application/json").header("X-XSRF-TOKEN", token).header("Idempotency-Key", key)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
