package com.agenticvideo.business.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.util.Map;
import java.util.List;
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
@org.springframework.test.context.TestPropertySource(properties={"legacy.worker-enabled=true","legacy.agent-enabled=true","analysis.execution-enabled=true","legacy.inspection-enabled=true"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConversationRecoveryApiIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6-bookworm");
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("conversation.events.cleanup-enabled", () -> "true");
        properties.add("conversation.events.cleanup-ms", () -> "500");
        properties.add("internal.worker-token", () -> "conversation-integration-worker-token");
    }
    @LocalServerPort int port;
    @org.springframework.beans.factory.annotation.Autowired org.springframework.jdbc.core.JdbcTemplate fixtures;

    @Test
    void ownerRestoresAcceptedMessagesAndTaskAtAnAuthenticatedProjectCursor() throws Exception {
        BrowserApi owner = signedIn("recovery-owner");
        BrowserApi outsider = signedIn("recovery-outsider");
        String project = json(owner.post("/projects", "{\"name\":\"恢复对话\"}")).path("data").path("id").asText();
        String session = json(owner.post("/projects/" + project + "/conversations", "{\"title\":\"查询\"}")).path("data").path("id").asText();
        String task = json(owner.post("/conversations/" + session + "/turns", "{\"content\":\"请查找制作步骤\"}", "recover-turn")).path("data").path("taskId").asText();
        var snapshot = owner.get("/projects/" + project + "/snapshot");
        assertEquals(200, snapshot.statusCode(), snapshot.body());
        JsonNode data = json(snapshot).path("data");
        assertEquals("请查找制作步骤", data.path("conversations").get(0).path("items").get(0).path("content").asText());
        assertEquals(task, data.path("conversations").get(0).path("latestTask").path("taskId").asText());
        org.junit.jupiter.api.Assertions.assertFalse(data.path("cursor").asText().isBlank());
        assertEquals(404, outsider.get("/projects/" + project + "/snapshot").statusCode());
        assertEquals(404, outsider.get("/projects/" + project + "/events?after=" + data.path("cursor").asText()).statusCode());
    }

    @Test
    void disconnectedSubscriberReplaysCommittedPublicEventsWithoutProviderOrToolInternals() throws Exception {
        BrowserApi owner = signedIn("replay-owner");
        String project = json(owner.post("/projects", "{\"name\":\"事件恢复\"}")).path("data").path("id").asText();
        String session = json(owner.post("/projects/" + project + "/conversations", "{\"title\":\"查询\"}")).path("data").path("id").asText();
        String cursor = json(owner.get("/projects/" + project + "/snapshot")).path("data").path("cursor").asText();
        String task = json(owner.post("/conversations/" + session + "/turns", "{\"content\":\"查制作步骤\"}", "replay-turn")).path("data").path("taskId").asText();
        String first = nextEvent(owner, project, cursor);
        org.junit.jupiter.api.Assertions.assertTrue(first.contains("turn.accepted"), first);
        org.junit.jupiter.api.Assertions.assertTrue(first.contains(task), first);
        assertEquals(first, nextEvent(owner, project, cursor));
        org.junit.jupiter.api.Assertions.assertFalse(first.contains("input_json"));
        org.junit.jupiter.api.Assertions.assertFalse(first.contains("leaseToken"));
    }

    @Test
    void draftsUseUnicodeOffsetsAndVersionsAndRejectOldAttemptsAndPrivateEvents() throws Exception {
        BrowserApi owner = signedIn("draft-owner");
        String project = json(owner.post("/projects", "{\"name\":\"草稿隔离\"}")).path("data").path("id").asText();
        String session = json(owner.post("/projects/" + project + "/conversations", "{\"title\":\"查询\"}")).path("data").path("id").asText();
        String task = json(owner.post("/conversations/" + session + "/turns", "{\"content\":\"查询步骤\"}", "draft-turn")).path("data").path("taskId").asText();
        JsonNode claim = json(internal(task, "claim", Map.of("executorId", "draft-test", "claimRequestId", java.util.UUID.randomUUID()))).path("data");
        var event = new java.util.HashMap<String,Object>();
        String message = java.util.UUID.randomUUID().toString();
        event.put("attemptId", claim.path("attemptId").asText()); event.put("leaseToken", claim.path("leaseToken").asText());
        event.put("producerSeq", 1); event.put("type", "assistant.delta");
        event.put("payload", Map.of("messageId", message, "baseContentVersion", 0, "offset", 0, "appendText", "步骤😀"));
        var accepted = internal(task, "events", event);
        assertEquals(200, accepted.statusCode(), accepted.body());
        assertEquals(json(accepted).path("data"), json(internal(task, "events", event)).path("data"));
        event.put("producerSeq", 2); event.put("payload", Map.of("messageId", message, "baseContentVersion", 1, "offset", 4, "appendText", "错误位置"));
        assertEquals(409, internal(task, "events", event).statusCode());
        event.put("payload", Map.of("messageId", message, "baseContentVersion", 1, "offset", 3, "appendText", "：上传素材。"));
        assertEquals(200, internal(task, "events", event).statusCode());
        event.put("producerSeq", 3); event.put("type", "tool.raw"); event.put("payload", Map.of("arguments", "private-secret"));
        assertEquals(400, internal(task, "events", event).statusCode());
        event.put("type", "activity.updated"); event.put("payload", Map.of("phase", "SEARCHING", "arguments", "private-secret"));
        assertEquals(400, internal(task, "events", event).statusCode());
        event.put("attemptId", java.util.UUID.randomUUID()); event.put("payload", Map.of("phase", "SEARCHING"));
        assertEquals(409, internal(task, "events", event).statusCode());
        JsonNode draft = json(owner.get("/projects/" + project + "/snapshot")).path("data").path("conversations").get(0).path("drafts").get(0);
        assertEquals("步骤😀：上传素材。", draft.path("content").asText());
        assertEquals("INCOMPLETE", draft.path("status").asText());
        assertEquals(2, draft.path("contentVersion").asInt());
        assertEquals(200, internal(task,"complete",Map.of("attemptId",claim.path("attemptId").asText(),"leaseToken",claim.path("leaseToken").asText(),
            "producerKey","draft-final","resultType","CONVERSATION_ANSWERED","result",Map.of("content","步骤😀：上传素材。","evidenceIds",List.of()))).statusCode());
        assertEquals(0,json(owner.get("/projects/"+project+"/snapshot")).path("data").path("conversations").get(0).path("drafts").size());
    }

    @Test
    void snapshotAndReplayDoNotSkipALateCommittingTransaction() throws Exception {
        BrowserApi owner = signedIn("late-commit-owner");
        String project = json(owner.post("/projects", "{\"name\":\"提交顺序\"}")).path("data").path("id").asText();
        String first = json(owner.post("/projects/" + project + "/conversations", "{\"title\":\"先事务\"}")).path("data").path("id").asText();
        String second = json(owner.post("/projects/" + project + "/conversations", "{\"title\":\"后事务\"}")).path("data").path("id").asText();
        try(var a=java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
            var b=java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())) {
            a.setAutoCommit(false);b.setAutoCommit(false);
            try(var statement=a.createStatement()) {
                statement.executeUpdate("INSERT INTO conversation_messages(id,conversation_id,message_seq,role,content) VALUES ('"+java.util.UUID.randomUUID()+"','"+first+"',1,'USER','先事务的消息')");
                statement.execute("SET CONSTRAINTS ALL IMMEDIATE");
            }
            String cursor=json(owner.get("/projects/"+project+"/snapshot")).path("data").path("cursor").asText();
            var waiting=new java.util.concurrent.CountDownLatch(1);
            var later=java.util.concurrent.CompletableFuture.runAsync(()->{
                try(var statement=b.createStatement()) {
                    statement.executeUpdate("INSERT INTO conversation_messages(id,conversation_id,message_seq,role,content) VALUES ('"+java.util.UUID.randomUUID()+"','"+second+"',1,'USER','后事务的消息')");
                    waiting.countDown();b.commit();
                }catch(Exception failure){throw new RuntimeException(failure);}
            });
            org.junit.jupiter.api.Assertions.assertTrue(waiting.await(3,java.util.concurrent.TimeUnit.SECONDS));
            a.commit();later.get(5,java.util.concurrent.TimeUnit.SECONDS);
            String firstEvent=nextEvent(owner,project,cursor);
            org.junit.jupiter.api.Assertions.assertTrue(firstEvent.contains("先事务的消息"),firstEvent);
            String next=firstEvent.lines().filter(line->line.startsWith("id:")).findFirst().orElseThrow().substring(3).strip();
            String secondEvent=nextEvent(owner,project,next);
            org.junit.jupiter.api.Assertions.assertTrue(secondEvent.contains("后事务的消息"),secondEvent);
            var snapshot=json(owner.get("/projects/"+project+"/snapshot")).path("data");
            assertEquals(1,snapshot.path("conversations").get(0).path("items").size());
            assertEquals(1,snapshot.path("conversations").get(1).path("items").size());
        }
    }

    @Test
    void expiredCursorRequiresSnapshotAndCursorCannotBeMovedBetweenProjects() throws Exception {
        BrowserApi owner=signedIn("expiry-owner");
        String project=json(owner.post("/projects","{\"name\":\"游标期限\"}")).path("data").path("id").asText();
        String session=json(owner.post("/projects/"+project+"/conversations","{\"title\":\"查询\"}")).path("data").path("id").asText();
        owner.post("/conversations/"+session+"/turns","{\"content\":\"查询\"}","expire-turn");
        String cursor=json(owner.get("/projects/"+project+"/snapshot")).path("data").path("cursor").asText();
        fixtures.update("UPDATE project_event_counters SET retained_after=event_seq WHERE project_id=?",java.util.UUID.fromString(project));
        assertEquals(410,owner.get("/projects/"+project+"/events?after="+project+":0").statusCode());
        assertEquals(400,owner.get("/projects/"+project+"/events?after="+java.util.UUID.randomUUID()+":0").statusCode());
        assertEquals(400,owner.get("/projects/"+project+"/events?after="+project+":999999").statusCode());
        org.junit.jupiter.api.Assertions.assertFalse(cursor.isEmpty());
    }

    @Test
    void retentionExpiresReplayButPreservesCompletedBusinessHistory() throws Exception {
        BrowserApi owner=signedIn("retention-owner");
        String project=json(owner.post("/projects","{\"name\":\"日志回收\"}")).path("data").path("id").asText();
        String session=json(owner.post("/projects/"+project+"/conversations","{\"title\":\"查询\"}")).path("data").path("id").asText();
        owner.post("/conversations/"+session+"/turns","{\"content\":\"保留这条公开消息\"}","retain-turn");
        fixtures.update("UPDATE public_events SET occurred_at=now()-interval '8 days' WHERE project_id=?",java.util.UUID.fromString(project));
        int status=0;
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        do {
            var response=owner.client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/projects/"+project+"/events?after="+project+":0")).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
            status=response.statusCode();response.body().close();
            if(status!=410)Thread.sleep(100);
        }while(status!=410 && System.nanoTime()<deadline);
        assertEquals(410,status);
        assertEquals("保留这条公开消息",json(owner.get("/conversations/"+session+"/snapshot")).path("data").path("items").get(0).path("content").asText());
    }

    @Test
    void recoveryBoundsOldDraftsKeepsActiveReplyAndPagesEveryIncompleteAttempt() throws Exception {
        BrowserApi owner=signedIn("draft-window-owner");
        String project=json(owner.post("/projects","{\"name\":\"有界恢复\"}")).path("data").path("id").asText();
        String session=json(owner.post("/projects/"+project+"/conversations","{\"title\":\"草稿历史\"}")).path("data").path("id").asText();
        for(int number=0;number<13;number++) {
            String task=json(owner.post("/conversations/"+session+"/turns","{\"content\":\"查询这轮素材\"}","window-"+number)).path("data").path("taskId").asText();
            JsonNode claim=json(internal(task,"claim",Map.of("executorId","window-test","claimRequestId",java.util.UUID.randomUUID()))).path("data");
            String text=number==12?"当前正在形成的新回复":"历史未完成草稿"+number;
            assertEquals(200,internal(task,"events",Map.of("attemptId",claim.path("attemptId").asText(),"leaseToken",claim.path("leaseToken").asText(),"producerSeq",1,"type","assistant.delta", "payload",Map.of("messageId",java.util.UUID.randomUUID(),"baseContentVersion",0,"offset",0,"appendText",text))).statusCode());
            if(number<12)assertEquals(200,internal(task,"fail",Map.of("attemptId",claim.path("attemptId").asText(),"leaseToken",claim.path("leaseToken").asText(),"producerKey","window-failed","code","CONVERSATION_UNVERIFIED","message","该轮未完成" )).statusCode());
        }
        JsonNode restored=json(owner.get("/conversations/"+session+"/snapshot")).path("data");
        assertEquals(10,restored.path("drafts").size());
        assertEquals("当前正在形成的新回复",restored.path("drafts").get(0).path("content").asText());
        assertEquals(true,restored.path("draftsTruncated").asBoolean());
        JsonNode first=json(owner.get("/conversations/"+session+"/drafts")).path("data");
        assertEquals(10,first.path("items").size());
        JsonNode second=json(owner.get("/conversations/"+session+"/drafts?before="+first.path("nextCursor").asText())).path("data");
        assertEquals(3,second.path("items").size());
        assertEquals(true,second.path("nextCursor").isNull());
    }

    @Test
    void newExecutionAttemptKeepsItsOwnDraftAndRejectsLateTextFromPreviousAttempt() throws Exception {
        BrowserApi owner=signedIn("attempt-draft-owner");
        String project=json(owner.post("/projects","{\"name\":\"恢复尝试\"}")).path("data").path("id").asText();
        String session=json(owner.post("/projects/"+project+"/conversations","{\"title\":\"隔离\"}")).path("data").path("id").asText();
        String task=json(owner.post("/conversations/"+session+"/turns","{\"content\":\"查询素材\"}","attempt-draft")).path("data").path("taskId").asText();
        JsonNode first=json(internal(task,"claim",Map.of("executorId","old-executor","claimRequestId",java.util.UUID.randomUUID()))).path("data");
        var old=new java.util.HashMap<String,Object>();old.put("attemptId",first.path("attemptId").asText());old.put("leaseToken",first.path("leaseToken").asText());old.put("producerSeq",1);old.put("type","assistant.delta");
        old.put("payload",Map.of("messageId",java.util.UUID.randomUUID(),"baseContentVersion",0,"offset",0,"appendText","旧尝试尚未完成"));
        assertEquals(200,internal(task,"events",old).statusCode());
        // Arrange a lost executor whose lease recovery has returned the task to PENDING.
        fixtures.update("UPDATE task_attempts SET status='EXPIRED',lease_expires_at=now()-interval '1 minute' WHERE id=?",java.util.UUID.fromString(first.path("attemptId").asText()));
        fixtures.update("UPDATE tasks SET status='PENDING',current_attempt_id=NULL WHERE id=?",java.util.UUID.fromString(task));
        JsonNode second=json(internal(task,"claim",Map.of("executorId","new-executor","claimRequestId",java.util.UUID.randomUUID()))).path("data");
        assertEquals(200,internal(task,"events",Map.of("attemptId",second.path("attemptId").asText(),"leaseToken",second.path("leaseToken").asText(),"producerSeq",1,"type","assistant.delta","payload",Map.of("messageId",java.util.UUID.randomUUID(),"baseContentVersion",0,"offset",0,"appendText","新尝试公开回复"))).statusCode());
        assertEquals(409,internal(task,"events",old).statusCode());
        assertEquals(200,internal(task,"events",Map.of("attemptId",second.path("attemptId").asText(),"leaseToken",second.path("leaseToken").asText(),"producerSeq",2,"type","activity.updated","payload",Map.of("phase","MODELING"))).statusCode());
        JsonNode snapshot=json(owner.get("/conversations/"+session+"/snapshot")).path("data");
        assertEquals(2,snapshot.path("drafts").size());
        assertEquals("新尝试公开回复",snapshot.path("drafts").get(0).path("content").asText());
        assertEquals(true,snapshot.path("drafts").get(0).path("current").asBoolean());
        assertEquals(false,snapshot.path("drafts").get(1).path("current").asBoolean());
        assertEquals("MODELING",snapshot.path("latestTask").path("phase").asText());
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class SessionExpiryFixture {
        @org.springframework.context.annotation.Bean
        org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter> shortSession() {
            var filter=new org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter>();
            filter.setFilter((request,response,chain)->{
                var http=(jakarta.servlet.http.HttpServletRequest)request;
                if("1".equals(http.getHeader("X-Fixture-Session-Seconds")))http.getSession(false).setMaxInactiveInterval(1);
                chain.doFilter(request,response);
            });
            filter.addUrlPatterns("/api/v1/projects/*");return filter;
        }
    }

    @Test
    void idleLoginExpiryClosesAnEstablishedStreamAndRequiresFreshAuthentication() throws Exception {
        BrowserApi owner=signedIn("idle-stream-owner");
        String project=json(owner.post("/projects","{\"name\":\"登录期限\"}")).path("data").path("id").asText();
        String cursor=json(owner.get("/projects/"+project+"/snapshot")).path("data").path("cursor").asText();
        var stream=owner.client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/projects/"+project+"/events?after="+cursor))
                .header("X-Fixture-Session-Seconds","1").GET().build(),HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200,stream.statusCode());
        try(var input=stream.body()) {
            String expired=java.util.concurrent.CompletableFuture.supplyAsync(()->{
                try {
                    var reader=new java.io.BufferedReader(new java.io.InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8));
                    StringBuilder text=new StringBuilder();for(String line;(line=reader.readLine())!=null;)text.append(line).append('\n');return text.toString();
                }catch(java.io.IOException failure){throw new RuntimeException(failure);}
            }).get(5,java.util.concurrent.TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.assertTrue(expired.contains("stream.auth-required"),expired);
        }
        assertEquals(401,owner.get("/auth/me").statusCode());
    }

    private String nextEvent(BrowserApi owner, String project, String cursor) throws Exception {
        var reply = owner.client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/projects/" + project + "/events?after=" + cursor))
                .timeout(java.time.Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, reply.statusCode());
        assertEquals("text/event-stream", reply.headers().firstValue("content-type").orElse(""));
        try (var input = reply.body()) {
            return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    var reader = new java.io.BufferedReader(new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8));
                    StringBuilder event = new StringBuilder();
                    for (String line; (line = reader.readLine()) != null;) {
                        if (line.isEmpty() && event.toString().contains("data:")) return event.toString();
                        if (line.isEmpty()) event.setLength(0); else event.append(line).append('\n');
                    }
                    return event.toString();
                } catch (java.io.IOException failure) { throw new RuntimeException(failure); }
            }).get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
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
