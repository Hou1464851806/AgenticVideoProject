package com.agenticvideo.business.task;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.test.context.TestPropertySource(properties={"legacy.worker-enabled=true","legacy.agent-enabled=true","analysis.execution-enabled=true","legacy.inspection-enabled=true"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OutboxPublishingIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6-bookworm");
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
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
    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry properties) {
        properties.add("storage.endpoint", () -> "http://localhost:" + SILO.getMappedPort(9000));
        properties.add("storage.public-endpoint", () -> "http://" + SILO.getHost() + ":" + SILO.getMappedPort(9000));
        properties.add("internal.worker-token", () -> "internal-integration-worker-token");
        properties.add("storage.access-key", () -> "integration-user");
        properties.add("storage.secret-key", () -> "integration-password-strong");
    }
    @Container
    static final org.testcontainers.containers.GenericContainer<?> BROKER = new org.testcontainers.containers.GenericContainer<>(
            "rabbitmq:4.3.6-management@sha256:8dd6e3570ddaa2ef82a6c3a8950e79c1f73893adfbdddbc374fc1f1ac8a0f5dd")
            .withEnv("RABBITMQ_DEFAULT_USER", "integration-worker")
            .withEnv("RABBITMQ_DEFAULT_PASS", "integration-broker-password")
            .withEnv("RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS", "+S 2:2")
            .withExposedPorts(5672).waitingFor(org.testcontainers.containers.wait.strategy.Wait.forLogMessage(".*Server startup complete.*", 1));
    @DynamicPropertySource
    static void broker(DynamicPropertyRegistry properties) {
        properties.add("spring.rabbitmq.host", BROKER::getHost);
        properties.add("spring.rabbitmq.port", () -> BROKER.getMappedPort(5672));
        properties.add("spring.rabbitmq.username", () -> "integration-worker");
        properties.add("spring.rabbitmq.password", () -> "integration-broker-password");
        properties.add("tasks.dispatch-enabled", () -> true);
        properties.add("tasks.consume-enabled", () -> false);
        properties.add("tasks.pending-renotify-seconds", () -> 2);
    }
    @org.springframework.beans.factory.annotation.Autowired org.springframework.amqp.rabbit.core.RabbitTemplate broker;
    @LocalServerPort int port;

    @org.springframework.beans.factory.annotation.Autowired org.springframework.jdbc.core.JdbcTemplate db;
    @Test
    void unroutableConfirmDoesNotMarkOutboxPublished() throws Exception {
        BrowserApi owner=signedIn("outbox-route-owner");
        var project=java.util.UUID.fromString(json(owner.post("/projects","{\"name\":\"未路由确认\"}")).path("data").path("id").asText());
        java.util.UUID task=java.util.UUID.randomUUID(),event=java.util.UUID.randomUUID();
        broker.execute(channel->{channel.queueDelete("media.v1");return null;});
        try {
            db.update("INSERT INTO tasks(id,project_id,type,input_json) VALUES (?,?,'MEDIA_PREPARE','{}')",task,project);
            db.update("INSERT INTO outbox_events(id,task_id,type,payload_json) VALUES (?,?,'MEDIA_PREPARE',jsonb_build_object('schemaVersion','1.0','eventId',?::text,'taskId',?::text))",event,task,event,task);
            await("SELECT count(*) FROM outbox_events WHERE id=? AND status='PENDING' AND last_error_code='BROKER_UNCONFIRMED'",event);
            assertEquals(0,db.queryForObject("SELECT count(*) FROM outbox_events WHERE id=? AND status='PUBLISHED'",Integer.class,event));
        }finally {
            broker.execute(channel->{channel.queueDeclare("media.v1",true,false,false,java.util.Map.of("x-dead-letter-exchange","","x-dead-letter-routing-key","media.dead.v1"));return null;});
        }
        await("SELECT count(*) FROM outbox_events WHERE id=? AND status='PUBLISHED'",event);
        var message=broker.receive("media.v1",1000);
        org.junit.jupiter.api.Assertions.assertNotNull(message);
        assertEquals(task.toString(),new ObjectMapper().readTree(message.getBody()).path("taskId").asText());
        assertEquals(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT,message.getMessageProperties().getReceivedDeliveryMode());
    }
    private void await(String sql,java.util.UUID event) throws Exception {
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(12).toNanos();
        while(System.nanoTime()<deadline){if(db.queryForObject(sql,Integer.class,event)==1)return;Thread.sleep(100);}
        org.junit.jupiter.api.Assertions.fail("Outbox did not reach expected durable state");
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
