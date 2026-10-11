package com.agenticvideo.business.upload;

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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UploadExpiryIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6-bookworm");
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("app.auth.max-attempts", () -> 200);
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Container
    static final org.testcontainers.containers.GenericContainer<?> SILO = new org.testcontainers.containers.GenericContainer<>(
            "pgsty/silo:RELEASE.2026-09-16T00-00-00Z@sha256:635197cb9f36d01bee221d34d1c7d7960f6a95c48b0b6c01d99cd13bdae51a46")
            .withEnv("MINIO_ROOT_USER", "integration-user")
            .withEnv("MINIO_ROOT_PASSWORD", "integration-password-strong")
            .withEnv("MINIO_API_MULTIPART_LISTING", "strict")
            .withCommand("server", "/data").withExposedPorts(9000)
            .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forHttp("/minio/health/ready").forPort(9000));
    static final SiloFaultProxy PROXY = new SiloFaultProxy(() -> SILO.getMappedPort(9000));
    @org.junit.jupiter.api.AfterAll static void closeProxy() throws Exception { PROXY.close(); }
    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry properties) {
        properties.add("storage.endpoint", PROXY::endpoint);
        properties.add("storage.public-endpoint", () -> "http://" + SILO.getHost() + ":" + SILO.getMappedPort(9000));
        properties.add("storage.list-page-size", () -> 1);
        properties.add("upload.cleanup-settle-seconds", () -> 1);
        properties.add("upload.cleanup-interval-ms", () -> 100);
        properties.add("upload.inactivity-seconds", () -> 3);
        properties.add("storage.part-url-seconds", () -> 3);
        properties.add("storage.access-key", () -> "integration-user");
        properties.add("storage.secret-key", () -> "integration-password-strong");
    }
    @LocalServerPort int port;

    @Test
    void lastLegalActivityExtendsExpiryButReadingDoesNotRenewAnExpiredUpload() throws Exception {
        var owner=signedIn("expiry-owner");
        String project=json(owner.post("/projects","{\"name\":\"过期上传\"}")).path("data").path("id").asText();
        var created=json(owner.post("/projects/"+project+"/uploads",new ObjectMapper().writeValueAsString(java.util.Map.of(
            "filename","expiry.mp4","sizeBytes",19,"sha256","e".repeat(64),"idempotencyKey","expiry-one","writerId",owner.writerId)))).path("data");
        String id=created.path("uploadId").asText(),writer=created.path("writerToken").asText();
        Thread.sleep(1500);
        assertEquals(200,owner.post("/uploads/"+id+"/part-urls","{\"partNumbers\":[1],\"writerToken\":\""+writer+"\"}").statusCode());
        Thread.sleep(2000);
        assertEquals("UPLOADING",json(owner.get("/uploads/"+id)).path("data").path("status").asText());
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(10).toNanos();
        JsonNode state;
        do{state=json(owner.get("/uploads/"+id)).path("data");if("EXPIRED".equals(state.path("status").asText()))break;Thread.sleep(100);}while(System.nanoTime()<deadline);
        assertEquals("EXPIRED",state.path("status").asText(),state.toString());
        assertEquals(409,owner.post("/uploads/"+id+"/resume",new ObjectMapper().writeValueAsString(java.util.Map.of("sizeBytes",19,"sha256","e".repeat(64),"writerId",owner.writerId))).statusCode());
        assertEquals(409,owner.post("/uploads/"+id+"/complete","{\"writerToken\":\""+writer+"\"}").statusCode());
        assertEquals(0,state.path("reservedSlots").asInt());
    }

    @Test
    void aValidCompletionLeaseSurvivesTheInactivityDeadlineWhileIncompleteCompletionDoesNotRenewIt() throws Exception {
        var owner=signedIn("valid-completing-owner");
        String project=json(owner.post("/projects","{\"name\":\"合法完成期限\"}")).path("data").path("id").asText();
        var upload=json(owner.post("/projects/"+project+"/uploads",new ObjectMapper().writeValueAsString(java.util.Map.of("filename","complete.mp4","sizeBytes",19,"sha256","a".repeat(64),"idempotencyKey","valid-completion","writerId",owner.writerId)))).path("data");
        String id=upload.path("uploadId").asText(),writer=upload.path("writerToken").asText();
        String credential=new ObjectMapper().writeValueAsString(java.util.Map.of("writerToken",writer));
        String deadline=json(owner.get("/uploads/"+id)).path("data").path("expiresAt").asText();
        assertEquals(409,owner.post("/uploads/"+id+"/complete",credential).statusCode());
        assertEquals(deadline,json(owner.get("/uploads/"+id)).path("data").path("expiresAt").asText());
        String url=json(owner.post("/uploads/"+id+"/part-urls","{\"partNumbers\":[1],\"writerToken\":\""+writer+"\"}")).path("data").path("parts").get(0).path("url").asText();
        assertEquals(200,HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[19])).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        String csrf=json(owner.get("/auth/csrf")).path("data").path("csrfToken").asText();
        PROXY.blocked=new java.util.concurrent.CountDownLatch(1);PROXY.release=new java.util.concurrent.CountDownLatch(1);PROXY.fault.set(SiloFaultProxy.Fault.BLOCK_COMPLETE_RESPONSE);
        try {
            var completing=owner.client.sendAsync(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/uploads/"+id+"/complete")).header("Content-Type","application/json").header("X-XSRF-TOKEN",csrf).POST(HttpRequest.BodyPublishers.ofString(credential)).build(),HttpResponse.BodyHandlers.ofString());
            org.junit.jupiter.api.Assertions.assertTrue(PROXY.blocked.await(2,java.util.concurrent.TimeUnit.SECONDS));
            Thread.sleep(3500);
            assertEquals("COMPLETING",json(owner.get("/uploads/"+id)).path("data").path("status").asText(),"An active valid completion lease must protect its object from automatic expiry");
            PROXY.fault.set(SiloFaultProxy.Fault.NONE);PROXY.release.countDown();
            var result=completing.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(202,result.statusCode(),result.body());assertEquals("COMPLETED",json(result).path("data").path("status").asText());
            assertEquals(409,owner.post("/uploads/"+id+"/cancel","{}").statusCode());
            assertEquals(1,json(owner.get("/projects/"+project+"/assets?includePending=true")).path("data").path("items").size());
        }finally{PROXY.fault.set(SiloFaultProxy.Fault.NONE);PROXY.release.countDown();}
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
        final java.util.UUID writerId = java.util.UUID.randomUUID();
        final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        HttpResponse<String> get(String path) throws Exception {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> post(String path, String body) throws Exception {
            String token = json(get("/auth/csrf")).path("data").path("csrfToken").asText();
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                    .header("Content-Type", "application/json").header("X-XSRF-TOKEN", token)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
