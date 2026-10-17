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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties={"upload.admission.test-capacity=3", "upload.max-file-bytes=1024", "upload.max-project-bytes=2048", "upload.max-project-assets=3"})
class UploadAdmissionApiIntegrationTest {
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
    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry properties) {
        properties.add("storage.endpoint", () -> "http://" + SILO.getHost() + ":" + SILO.getMappedPort(9000));
        properties.add("storage.public-endpoint", () -> "http://" + SILO.getHost() + ":" + SILO.getMappedPort(9000));
        properties.add("storage.access-key", () -> "integration-user");
        properties.add("storage.secret-key", () -> "integration-password-strong");
    }
    @LocalServerPort int port;
    private final ObjectMapper mapper = new ObjectMapper();
    @org.springframework.beans.factory.annotation.Autowired org.springframework.data.redis.core.StringRedisTemplate admissionRedis;
    @org.springframework.beans.factory.annotation.Autowired org.springframework.jdbc.core.JdbcTemplate database;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean MultipartStorage storage;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean com.agenticvideo.business.storage.StorageCapacity capacity;

    @org.junit.jupiter.api.AfterEach void restoreFaults() {
        org.mockito.Mockito.reset(storage, capacity);
    }
    private UploadTestApi owner() throws Exception { return UploadTestApi.signedIn(port, "admit-" + java.util.UUID.randomUUID()); }
    private String project(UploadTestApi owner) throws Exception {
        return mapper.readTree(owner.post("/projects", "{\"name\":\"准入测试\"}").body()).path("data").path("id").asText();
    }
    private String body(UploadTestApi owner, String key, long bytes) throws Exception {
        return mapper.writeValueAsString(java.util.Map.of("filename", "admission.mp4", "sizeBytes", bytes,
                "sha256", "a".repeat(64), "idempotencyKey", key, "writerId", owner.writerId));
    }
    private void create(UploadTestApi owner, String project, String key) throws Exception {
        var response = owner.post("/projects/" + project + "/uploads", body(owner, key, 1));
        assertEquals(201, response.statusCode(), response.body());
    }

    @Test void userSharesBucketAcrossProjectsAndIdempotencyAndRejectionsHaveNoSideEffects() throws Exception {
        var owner = owner(); var first = project(owner); var second = project(owner);
        create(owner, first, "first");
        assertEquals(409, owner.post("/projects/"+first+"/uploads", body(owner,"first",2)).statusCode());
        assertEquals(409, owner.post("/projects/"+first+"/uploads", body(owner,"first",1)
                .replace(owner.writerId.toString(), java.util.UUID.randomUUID().toString())).statusCode());
        assertEquals(409, owner.post("/projects/"+second+"/uploads", body(owner,"first",1)).statusCode());
        create(owner, second, "second"); create(owner, first, "third");
        var sessions = database.queryForObject("SELECT count(*) FROM upload_sessions", Long.class);
        var reservations = database.queryForObject("SELECT count(*) FROM storage_reservations", Long.class);
        org.mockito.Mockito.clearInvocations(storage);
        var denied = owner.post("/projects/" + second + "/uploads", body(owner, "fourth", 1));
        assertEquals(429, denied.statusCode(), denied.body());
        var error = mapper.readTree(denied.body()).path("error");
        assertEquals("RATE_LIMITED", error.path("code").asText());
        org.junit.jupiter.api.Assertions.assertTrue(error.path("retryable").asBoolean());
        long wait = error.path("details").path("retryAfterMs").asLong();
        org.junit.jupiter.api.Assertions.assertTrue(wait > 0 && wait <= 60000);
        assertEquals(Long.toString((wait+999)/1000), denied.headers().firstValue("Retry-After").orElseThrow());
        assertEquals(sessions, database.queryForObject("SELECT count(*) FROM upload_sessions", Long.class));
        assertEquals(reservations, database.queryForObject("SELECT count(*) FROM storage_reservations", Long.class));
        org.mockito.Mockito.verify(storage, org.mockito.Mockito.never()).initializeUpload(org.mockito.ArgumentMatchers.anyString());
        assertEquals(0, database.queryForObject("SELECT count(*) FROM tasks", Integer.class));
        create(owner, first, "first"); // Empty bucket still permits a legal replay.
        assertEquals(409, owner.post("/projects/"+first+"/uploads", body(owner,"first",2)).statusCode());
        var other = owner(); create(other, project(other), "isolated");
    }

    @Test void concurrentNewKeysAcrossProjectsCannotExceedUserCapacity() throws Exception {
        var owner = owner();
        var projects = new java.util.ArrayList<String>();
        for (int i=0;i<6;i++) projects.add(project(owner));
        var executor = java.util.concurrent.Executors.newFixedThreadPool(6);
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (var project : projects) futures.add(executor.submit(() -> {
                start.await();
                return owner.post("/projects/"+project+"/uploads",body(owner,project,1)).statusCode();
            }));
            start.countDown();
            int allowed=0, denied=0;
            for(var future:futures) {
                int status=future.get();
                if(status==201) allowed++;
                else { assertEquals(429,status); denied++; }
            }
            assertEquals(3,allowed); assertEquals(3,denied);
        } finally { executor.shutdownNow(); }
    }

    @Test void projectByteAndSlotRejectionsDoNotSpendAdmission() throws Exception {
        var owner=owner(); var full=project(owner);
        var response=owner.post("/projects/"+full+"/uploads",body(owner,"large-one",1024));
        assertEquals(201,response.statusCode(),response.body());
        response=owner.post("/projects/"+full+"/uploads",body(owner,"large-two",1024));
        assertEquals(201,response.statusCode(),response.body());
        assertEquals(409,owner.post("/projects/"+full+"/uploads",body(owner,"byte-rejected",1)).statusCode());
        create(owner,project(owner),"remaining");
        assertEquals(429,owner.post("/projects/"+project(owner)+"/uploads",body(owner,"empty",1)).statusCode());
        var slots=owner(); var slotProject=project(slots);
        for(int i=0;i<3;i++) create(slots,slotProject,"slot-"+i);
        var userId=database.queryForObject("SELECT owner_user_id FROM projects WHERE id=?::uuid",java.util.UUID.class,slotProject);
        admissionRedis.delete("upload:admission:"+userId); // A fresh bucket isolates the quota check from exhaustion.
        assertEquals(409,slots.post("/projects/"+slotProject+"/uploads",body(slots,"slot-rejected",1)).statusCode());
        var next=project(slots);
        for(int i=0;i<3;i++) create(slots,next,"new-slot-"+i);
    }

    @Test void prechecksDoNotSpendAndCancellationDoesNotRefund() throws Exception {
        var owner = owner(); var project = project(owner);
        assertEquals(409, owner.post("/projects/"+project+"/uploads", body(owner,"large",1025)).statusCode());
        assertEquals(400, owner.post("/projects/"+project+"/uploads", body(owner,"bad",0)).statusCode());
        var other = owner(); var foreign = project(other);
        assertEquals(404, owner.post("/projects/"+foreign+"/uploads",body(owner,"foreign",1)).statusCode());
        for (int i=0;i<3;i++) create(owner,project,"allowed-"+i);
        assertEquals(409, owner.post("/projects/"+project+"/uploads",body(owner,"quota",1)).statusCode());
        var uploads = mapper.readTree(owner.get("/projects/"+project+"/uploads").body()).path("data").path("items");
        for (var upload:uploads) assertEquals(200,owner.post("/uploads/"+upload.path("uploadId").asText()+"/cancel","{}").statusCode());
        assertEquals(429,owner.post("/projects/"+project+"/uploads",body(owner,"after-cancel",1)).statusCode());
    }

    @Test void sqlRollbackAndGlobalCapacityFailureDoNotRefund() throws Exception {
        var owner=owner(); var project=project(owner);
        org.mockito.Mockito.doThrow(new com.agenticvideo.business.storage.StorageCapacity.SpaceUnavailable("test capacity failure"))
                .when(capacity).require(org.mockito.ArgumentMatchers.anyLong());
        assertEquals(409,owner.post("/projects/"+project+"/uploads",body(owner,"capacity",1)).statusCode());
        org.mockito.Mockito.reset(capacity);
        database.execute("CREATE FUNCTION reject_admission_insert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled insert failure'; END $$");
        database.execute("CREATE TRIGGER admission_insert_fault BEFORE INSERT ON upload_sessions FOR EACH ROW EXECUTE FUNCTION reject_admission_insert()");
        try { assertEquals(500,owner.post("/projects/"+project+"/uploads",body(owner,"sql",1)).statusCode()); }
        finally { database.execute("DROP TRIGGER admission_insert_fault ON upload_sessions"); database.execute("DROP FUNCTION reject_admission_insert()"); }
        assertEquals(0,mapper.readTree(owner.get("/projects/"+project+"/uploads").body()).path("data").path("items").size());
        create(owner,project,"last");
        assertEquals(429,owner.post("/projects/"+project+"/uploads",body(owner,"empty",1)).statusCode());
    }

    @Test void redisTimeoutAndRefusalReturn503WithoutSideEffectsButAllowReplayAndRecovery() throws Exception {
        var owner=owner(); var project=project(owner); create(owner,project,"replay");
        var redis=com.agenticvideo.business.support.RedisTestInitializer.REDIS;
        for (boolean timeout : new boolean[]{true,false}) {
            if(timeout) redis.getDockerClient().pauseContainerCmd(redis.getContainerId()).exec();
            else redis.execInContainer("redis-cli", "CONFIG", "SET", "port", "6380");
            try {
                org.mockito.Mockito.clearInvocations(storage);
                var reservations=database.queryForObject("SELECT count(*) FROM storage_reservations",Long.class);
                var tasks=database.queryForObject("SELECT count(*) FROM tasks",Long.class);
                var denied=owner.post("/projects/"+project+"/uploads",body(owner,"unavailable",1));
                assertEquals(503,denied.statusCode(),denied.body());
                assertEquals(reservations,database.queryForObject("SELECT count(*) FROM storage_reservations",Long.class));
                assertEquals(tasks,database.queryForObject("SELECT count(*) FROM tasks",Long.class));
                var error=mapper.readTree(denied.body()).path("error");
                assertEquals("UPLOAD_ADMISSION_UNAVAILABLE",error.path("code").asText());
                org.junit.jupiter.api.Assertions.assertTrue(error.path("retryable").asBoolean());
                org.junit.jupiter.api.Assertions.assertFalse(error.path("details").has("retryAfterMs"));
                org.junit.jupiter.api.Assertions.assertTrue(denied.headers().firstValue("Retry-After").isEmpty());
                assertEquals(1,mapper.readTree(owner.get("/projects/"+project+"/uploads").body()).path("data").path("items").size());
                org.mockito.Mockito.verify(storage,org.mockito.Mockito.never()).initializeUpload(org.mockito.ArgumentMatchers.anyString());
                create(owner,project,"replay");
            } finally {
                if(timeout) redis.getDockerClient().unpauseContainerCmd(redis.getContainerId()).exec();
                else {
                    redis.execInContainer("redis-cli", "-p", "6380", "CONFIG", "SET", "port", "6379");
                }
            }
        }
        create(owner,project,"recovered");
    }

    @Test void objectStoreInitializationRecoveryReusesAdmission() throws Exception {
        var owner=owner(); var project=project(owner);
        org.mockito.Mockito.doThrow(software.amazon.awssdk.core.exception.SdkClientException.create("controlled initialization failure"))
                .when(storage).initializeUpload(org.mockito.ArgumentMatchers.anyString());
        assertEquals(503,owner.post("/projects/"+project+"/uploads",body(owner,"recover",1)).statusCode());
        org.mockito.Mockito.reset(storage);
        var redis=com.agenticvideo.business.support.RedisTestInitializer.REDIS;
        redis.getDockerClient().pauseContainerCmd(redis.getContainerId()).exec();
        try { create(owner,project,"recover"); }
        finally { redis.getDockerClient().unpauseContainerCmd(redis.getContainerId()).exec(); }
        create(owner,project,"second"); create(owner,project(owner),"third");
        assertEquals(429,owner.post("/projects/"+project(owner)+"/uploads",body(owner,"fourth",1)).statusCode());
    }
}
