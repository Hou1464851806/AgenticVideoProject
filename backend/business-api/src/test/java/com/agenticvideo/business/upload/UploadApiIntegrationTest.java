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
class UploadApiIntegrationTest {
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
        properties.add("storage.part-url-seconds", () -> 3);
        properties.add("storage.access-key", () -> "integration-user");
        properties.add("storage.secret-key", () -> "integration-password-strong");
    }
    @LocalServerPort int port;

    private final java.util.List<BrowserApi> owners = new java.util.ArrayList<>();
    @org.junit.jupiter.api.AfterEach
    void releaseThisTestsFutureReservationsThroughPublicCancellation() throws Exception {
        PROXY.fault.set(SiloFaultProxy.Fault.NONE);
        PROXY.release.countDown();
        for(var owner:owners) {
            for(var project:json(owner.get("/projects")).path("data").path("items")) {
                for(var upload:json(owner.get("/projects/"+project.path("id").asText()+"/uploads")).path("data").path("items")) {
                    String id=upload.path("uploadId").asText();
                    if("COMPLETED".equals(upload.path("status").asText())) {
                        String task=upload.path("validationTaskId").asText();
                        assertEquals(202,owner.post("/tasks/"+task+"/cancel","{}").statusCode());
                    } else {
                        assertEquals(200,owner.post("/uploads/"+id+"/cancel","{}").statusCode());
                        awaitClean(owner,id,45);
                    }
                }
            }
        }
    }

    @Test
    void anOversizedRealSignedPartIsRejectedAndReconciledBeforeItCanPublishAnAsset() throws Exception {
        var owner = signedIn("oversized-real-part-owner");
        String project = json(owner.post("/projects", "{\"name\":\"实际超额分片\"}")).path("data").path("id").asText();
        var upload = json(owner.post("/projects/" + project + "/uploads", new ObjectMapper().writeValueAsString(java.util.Map.of(
                "filename", "oversized.mp4", "sizeBytes", 19, "sha256", "a".repeat(64), "idempotencyKey", "oversized-real", "writerId", owner.writerId)))).path("data");
        String id = upload.path("uploadId").asText(), writer = upload.path("writerToken").asText();
        String url = json(owner.post("/uploads/" + id + "/part-urls", new ObjectMapper().writeValueAsString(java.util.Map.of("partNumbers", java.util.List.of(1), "writerToken", writer))))
                .path("data").path("parts").get(0).path("url").asText();
        var stored = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[20])).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, stored.statusCode(), stored.body());
        var rejected = owner.post("/uploads/" + id + "/complete", new ObjectMapper().writeValueAsString(java.util.Map.of("writerToken", writer)));
        assertEquals(409, rejected.statusCode(), rejected.body());
        assertEquals("CANCELLED", json(owner.get("/uploads/" + id)).path("data").path("status").asText());
        assertEquals(0, json(owner.get("/projects/" + project + "/assets?includePending=true")).path("data").path("items").size());
        awaitClean(owner, id, 40);
        assertEquals(0, json(owner.get("/uploads/" + id)).path("data").path("reservedBytes").asLong());
    }

    @Test
    void createUploadPersistsOneIdentityAndReservationAcrossDuplicateRequests() throws Exception {
        BrowserApi browser = signedIn("upload-owner");
        String projectId = json(browser.post("/projects", "{\"name\":\"视频上传\"}")).path("data").path("id").asText();
        String body = "{\"filename\":\"talk.mp4\",\"sizeBytes\":1024,\"sha256\":\"" + "a".repeat(64) + "\",\"idempotencyKey\":\"upload-create-1\",\"writerId\":\"" + browser.writerId + "\"}";
        var created = browser.post("/projects/" + projectId + "/uploads", body);
        assertEquals(201, created.statusCode(), created.body());
        String id = json(created).path("data").path("uploadId").asText();
        var duplicate = browser.post("/projects/" + projectId + "/uploads", body);
        assertEquals(201, duplicate.statusCode(), duplicate.body());
        assertEquals(id, json(duplicate).path("data").path("uploadId").asText());
        assertEquals(409, browser.post("/projects/" + projectId + "/uploads", body.replace("talk.mp4", "other.mp4")).statusCode());
        var status = browser.get("/uploads/" + id);
        assertEquals(200, status.statusCode());
        assertEquals("UPLOADING", json(status).path("data").path("status").asText());
        assertEquals(1024, json(status).path("data").path("reservedBytes").asLong());
        assertEquals(1, json(browser.get("/projects/" + projectId + "/uploads")).path("data").path("items").size());
    }

    @Test
    void directMultipartCompletionPublishesOnlyOnePendingAssetAndTask() throws Exception {
        BrowserApi browser = signedIn("upload-complete-owner");
        String projectId = json(browser.post("/projects", "{\"name\":\"分片上传\"}")).path("data").path("id").asText();
        byte[] bytes = "real-multipart-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        PROXY.fault.set(SiloFaultProxy.Fault.DROP_CREATE_RESPONSE);
        var created = browser.post("/projects/" + projectId + "/uploads", new ObjectMapper().writeValueAsString(java.util.Map.of(
                "filename", "talk.mp4", "sizeBytes", bytes.length, "sha256", hash, "idempotencyKey", "multipart-one", "writerId", browser.writerId)));
        assertEquals(201, created.statusCode(), created.body());
        var data = json(created).path("data");
        String id = data.path("uploadId").asText();
        String writer = data.path("writerToken").asText();
        try (var storageClient = software.amazon.awssdk.services.s3.S3Client.builder()
                .endpointOverride(URI.create("http://localhost:" + SILO.getMappedPort(9000)))
                .region(software.amazon.awssdk.regions.Region.US_EAST_1).forcePathStyle(true)
                .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                        software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("integration-user", "integration-password-strong"))).build()) {
            assertEquals(1, storageClient.listMultipartUploads(b -> b.bucket("originals").prefix("originals/" + id)).uploads().size());
        }
        var authorization = browser.post("/uploads/" + id + "/part-urls", "{\"partNumbers\":[1],\"writerToken\":\"" + writer + "\"}");
        assertEquals(200, authorization.statusCode(), authorization.body());
        String url = json(authorization).path("data").path("parts").get(0).path("url").asText();
        org.junit.jupiter.api.Assertions.assertTrue(url.contains("X-Amz-Expires=3&") || url.endsWith("X-Amz-Expires=3"),
                "The test must expire a real signed URL, without altering its signature");
        Thread.sleep(4000);
        var expired = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, expired.statusCode(), expired.body());
        var renewed = browser.post("/uploads/" + id + "/part-urls", "{\"partNumbers\":[1],\"writerToken\":\"" + writer + "\"}");
        assertEquals(200, renewed.statusCode(), renewed.body());
        url = json(renewed).path("data").path("parts").get(0).path("url").asText();
        var uploaded = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, uploaded.statusCode(), uploaded.body());
        org.junit.jupiter.api.Assertions.assertTrue(uploaded.headers().firstValue("ETag").isPresent());
        String input = "{\"writerToken\":\"" + writer + "\"}";
        PROXY.fault.set(SiloFaultProxy.Fault.COMPLETE_EMBEDDED_ERROR);
        var embeddedError = browser.post("/uploads/" + id + "/complete", input);
        assertEquals(503, embeddedError.statusCode());
        assertEquals("STORAGE_UNAVAILABLE", json(embeddedError).path("error").path("code").asText());
        org.junit.jupiter.api.Assertions.assertTrue(json(embeddedError).path("error").path("retryable").asBoolean());
        assertEquals(0, json(browser.get("/projects/" + projectId + "/assets?includePending=true")).path("data").path("items").size());
        PROXY.fault.set(SiloFaultProxy.Fault.DROP_COMPLETE_RESPONSE);
        var completed = browser.post("/uploads/" + id + "/complete", input);
        assertEquals(202, completed.statusCode(), completed.body());
        var duplicate = browser.post("/uploads/" + id + "/complete", input);
        assertEquals(202, duplicate.statusCode(), duplicate.body());
        assertEquals(json(completed).path("data").path("assetId").asText(), json(duplicate).path("data").path("assetId").asText());
        assertEquals(json(completed).path("data").path("validationTaskId").asText(), json(duplicate).path("data").path("validationTaskId").asText());
        assertEquals("VALIDATING", json(browser.get("/uploads/" + id)).path("data").path("validationStatus").asText());
        var assets = browser.get("/projects/" + projectId + "/assets?includePending=true");
        assertEquals(200, assets.statusCode(), assets.body());
        assertEquals(1, json(assets).path("data").path("items").size());
    }

    @Test
    void anotherAccountCannotSignRecordOrCompleteUploadAndIncompleteFileStaysUnpublished() throws Exception {
        BrowserApi owner = signedIn("upload-private-owner");
        BrowserApi outsider = signedIn("upload-private-outsider");
        String projectId = json(owner.post("/projects", "{\"name\":\"私有上传\"}")).path("data").path("id").asText();
        String body = "{\"filename\":\"private.mp4\",\"sizeBytes\":1024,\"sha256\":\"" + "b".repeat(64) + "\",\"idempotencyKey\":\"private-one\",\"writerId\":\"" + owner.writerId + "\"}";
        var data = json(owner.post("/projects/" + projectId + "/uploads", body)).path("data");
        String id = data.path("uploadId").asText();
        String writer = data.path("writerToken").asText();
        assertEquals(404, outsider.post("/projects/" + projectId + "/uploads", body).statusCode());
        assertEquals(404, outsider.get("/uploads/" + id).statusCode());
        assertEquals(404, outsider.post("/uploads/" + id + "/part-urls", "{\"partNumbers\":[1],\"writerToken\":\"" + writer + "\"}").statusCode());
        assertEquals(404, outsider.post("/uploads/" + id + "/complete", "{\"writerToken\":\"" + writer + "\"}").statusCode());
        assertEquals(409, owner.post("/uploads/" + id + "/complete", "{\"writerToken\":\"" + writer + "\"}").statusCode());
        assertEquals(0, json(owner.get("/projects/" + projectId + "/assets?includePending=true")).path("data").path("items").size());
        assertEquals("UPLOADING", json(owner.get("/uploads/" + id)).path("data").path("status").asText());
    }

    @Test
    void onlyOneBrowserWriterCanResumeAndReadOnlyStatusCannotGrantWritingRights() throws Exception {
        BrowserApi browser = signedIn("upload-writer-owner");
        String projectId = json(browser.post("/projects", "{\"name\":\"独占续传\"}")).path("data").path("id").asText();
        String writerId = java.util.UUID.randomUUID().toString();
        String hash = "c".repeat(64);
        var created = json(browser.post("/projects/" + projectId + "/uploads", new ObjectMapper().writeValueAsString(java.util.Map.of(
                "filename", "same.mp4", "sizeBytes", 1024, "sha256", hash, "idempotencyKey", "writer-one", "writerId", writerId)))).path("data");
        String id = created.path("uploadId").asText();
        org.junit.jupiter.api.Assertions.assertFalse(json(browser.get("/uploads/" + id)).path("data").has("writerToken"));
        assertEquals(400, browser.post("/projects/" + projectId + "/uploads", new ObjectMapper().writeValueAsString(java.util.Map.of(
                "filename", "same.mp4", "sizeBytes", 1024, "sha256", hash, "idempotencyKey", "writer-one"))).statusCode(),
                "A creation replay without the original browser identity must not disclose its writer token");
        assertEquals(400, browser.post("/uploads/" + id + "/resume", new ObjectMapper().writeValueAsString(java.util.Map.of(
                "sizeBytes", 1024, "sha256", hash, "writerToken", created.path("writerToken").asText()))).statusCode());
        String otherWriter = new ObjectMapper().writeValueAsString(java.util.Map.of("sizeBytes", 1024, "sha256", hash,
                "writerId", java.util.UUID.randomUUID(), "writerToken", created.path("writerToken").asText()));
        assertEquals(409, browser.post("/uploads/" + id + "/resume", otherWriter).statusCode());
        var resumed = browser.post("/uploads/" + id + "/resume", otherWriter.replace(new ObjectMapper().readTree(otherWriter).path("writerId").asText(), writerId));
        assertEquals(200, resumed.statusCode(), resumed.body());
        assertEquals(created.path("writerToken").asText(), json(resumed).path("data").path("writerToken").asText());
        String credential = new ObjectMapper().writeValueAsString(java.util.Map.of("writerToken", created.path("writerToken").asText()));
        var paused = browser.post("/uploads/" + id + "/pause", credential);
        assertEquals(200, paused.statusCode(), paused.body());
        assertEquals("PAUSED", json(browser.get("/uploads/" + id)).path("data").path("status").asText());
        assertEquals(409, browser.post("/uploads/" + id + "/part-urls", credential.substring(0, credential.length()-1) + ",\"partNumbers\":[1]}").statusCode());
        var continued = browser.post("/uploads/" + id + "/resume", otherWriter.replace(new ObjectMapper().readTree(otherWriter).path("writerId").asText(), writerId));
        assertEquals(200, continued.statusCode(), continued.body());
        assertEquals("UPLOADING", json(continued).path("data").path("status").asText());
    }

    @Test
    void resumingSameFileReconcilesBothRealSiloPagesWithoutDependingOnClientPartReports() throws Exception {
        BrowserApi browser = signedIn("upload-paged-owner");
        String projectId = json(browser.post("/projects", "{\"name\":\"分页列片\"}")).path("data").path("id").asText();
        byte[] bytes = new byte[8 * 1024 * 1024 + 19];
        java.util.Arrays.fill(bytes, (byte) 37);
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        var data = json(browser.post("/projects/" + projectId + "/uploads", new ObjectMapper().writeValueAsString(java.util.Map.of(
                "filename", "paged.mp4", "sizeBytes", bytes.length, "sha256", hash, "idempotencyKey", "paged-upload", "writerId", browser.writerId)))).path("data");
        String id = data.path("uploadId").asText();
        String writer = data.path("writerToken").asText();
        var authorization = browser.post("/uploads/" + id + "/part-urls", "{\"partNumbers\":[1,2],\"writerToken\":\"" + writer + "\"}");
        assertEquals(200, authorization.statusCode(), authorization.body());
        var parts = json(authorization).path("data").path("parts");
        for (int i=0; i<2; i++) {
            int offset = i * 8 * 1024 * 1024;
            byte[] part = java.util.Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + 8 * 1024 * 1024));
            var uploaded = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(parts.get(i).path("url").asText()))
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(part)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, uploaded.statusCode(), uploaded.body());
        }
        String resumeBody = new ObjectMapper().writeValueAsString(java.util.Map.of(
                "sizeBytes", bytes.length, "sha256", hash, "writerId", browser.writerId));
        assertEquals(409, browser.post("/uploads/" + id + "/resume", resumeBody.replace(hash, "0".repeat(64))).statusCode());
        PROXY.partPages.set(0);
        var resumed = browser.post("/uploads/" + id + "/resume", resumeBody);
        assertEquals(200, resumed.statusCode(), resumed.body());
        assertEquals(id, json(resumed).path("data").path("uploadId").asText());
        assertEquals(2, json(resumed).path("data").path("confirmedParts").size());
        assertEquals(bytes.length, json(resumed).path("data").path("confirmedBytes").asLong());
        assertEquals(2, PROXY.partPages.get(), "Resume must reconcile every actual Silo page");
        assertEquals(1, json(browser.get("/projects/" + projectId + "/uploads")).path("data").path("items").size());
        PROXY.partPages.set(0);
        var completed = browser.post("/uploads/" + id + "/complete", "{\"writerToken\":\"" + writer + "\"}");
        assertEquals(202, completed.statusCode(), completed.body());
        assertEquals(2, PROXY.partPages.get(), "Both pages of actual Silo parts must be read");
    }

    @Test
    void cancellationRevokesWritingAndReclaimsRealMultipartWithoutPublishingAnAsset() throws Exception {
        BrowserApi owner = signedIn("upload-cancel-owner");
        BrowserApi outsider = signedIn("upload-cancel-outsider");
        String project = json(owner.post("/projects", "{\"name\":\"取消上传\"}")).path("data").path("id").asText();
        var created = json(owner.post("/projects/"+project+"/uploads", new ObjectMapper().writeValueAsString(java.util.Map.of(
                "filename", "cancel.mp4", "sizeBytes", 19, "sha256", "d".repeat(64), "idempotencyKey", "cancel-one", "writerId", owner.writerId)))).path("data");
        String id=created.path("uploadId").asText();
        String writer=created.path("writerToken").asText();
        String credential=new ObjectMapper().writeValueAsString(java.util.Map.of("writerToken",writer));
        var urls=json(owner.post("/uploads/"+id+"/part-urls", "{\"partNumbers\":[1],\"writerToken\":\""+writer+"\"}")).path("data");
        var put=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(urls.path("parts").get(0).path("url").asText()))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[19])).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,put.statusCode());
        assertEquals(404,outsider.post("/uploads/"+id+"/cancel","{}").statusCode());
        var cancelled=owner.post("/uploads/"+id+"/cancel","{}");
        assertEquals(200,cancelled.statusCode(),cancelled.body());
        assertEquals("CANCELLED",json(cancelled).path("data").path("status").asText());
        assertEquals(0,json(cancelled).path("data").path("reservedSlots").asInt());
        assertEquals(409,owner.post("/uploads/"+id+"/complete",credential).statusCode());
        assertEquals(409,owner.post("/uploads/"+id+"/part-urls","{\"partNumbers\":[1],\"writerToken\":\""+writer+"\"}").statusCode());
        assertEquals(200,owner.post("/uploads/"+id+"/cancel","{}").statusCode());
        Thread.sleep(1500);
        assertEquals("PENDING",json(owner.get("/uploads/"+id)).path("data").path("cleanupStatus").asText(),
                "Cleanup cannot release bytes while an already issued signed URL remains valid");
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(20).toNanos();
        JsonNode state;
        do {state=json(owner.get("/uploads/"+id)).path("data");if("CLEANED".equals(state.path("cleanupStatus").asText()))break;Thread.sleep(100);}while(System.nanoTime()<deadline);
        assertEquals("CLEANED",state.path("cleanupStatus").asText(),state.toString());
        assertEquals(0,state.path("reservedBytes").asLong());
        try(var silo=software.amazon.awssdk.services.s3.S3Client.builder().endpointOverride(URI.create("http://localhost:"+SILO.getMappedPort(9000)))
                .region(software.amazon.awssdk.regions.Region.US_EAST_1).forcePathStyle(true)
                .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("integration-user","integration-password-strong"))).build()) {
            assertEquals(0,silo.listMultipartUploads(b->b.bucket("originals").prefix("originals/"+id)).uploads().size());
            assertEquals(0,silo.listObjectsV2(b->b.bucket("originals").prefix("originals/"+id)).contents().size());
        }
        assertEquals(0,json(owner.get("/projects/"+project+"/assets?includePending=true")).path("data").path("items").size());
    }

    @Test
    void cancellationFencesAnInFlightPartReportAfterStorageHasReturnedItsFacts() throws Exception {
        var owner=signedIn("part-report-cancel-owner");
        String project=json(owner.post("/projects","{\"name\":\"登记竞争\"}")).path("data").path("id").asText();
        var created=json(owner.post("/projects/"+project+"/uploads",new ObjectMapper().writeValueAsString(java.util.Map.of(
                "filename","race.mp4","sizeBytes",19,"sha256","f".repeat(64),"idempotencyKey","report-race","writerId",owner.writerId)))).path("data");
        String id=created.path("uploadId").asText(),writer=created.path("writerToken").asText();
        var parts=json(owner.post("/uploads/"+id+"/part-urls","{\"partNumbers\":[1],\"writerToken\":\""+writer+"\"}")).path("data").path("parts");
        var put=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(parts.get(0).path("url").asText())).PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[19])).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,put.statusCode());
        String token=json(owner.get("/auth/csrf")).path("data").path("csrfToken").asText();
        String report=new ObjectMapper().writeValueAsString(java.util.Map.of("writerToken",writer,"etag",put.headers().firstValue("ETag").orElseThrow(),"sizeBytes",19));
        PROXY.blocked=new java.util.concurrent.CountDownLatch(1);PROXY.release=new java.util.concurrent.CountDownLatch(1);PROXY.fault.set(SiloFaultProxy.Fault.BLOCK_PARTS_RESPONSE);
        try {
            var inFlight=owner.client.sendAsync(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/uploads/"+id+"/parts/1")).header("Content-Type","application/json").header("X-XSRF-TOKEN",token).PUT(HttpRequest.BodyPublishers.ofString(report)).build(),HttpResponse.BodyHandlers.ofString());
            org.junit.jupiter.api.Assertions.assertTrue(PROXY.blocked.await(3,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(200,owner.post("/uploads/"+id+"/cancel","{}").statusCode());
            PROXY.fault.set(SiloFaultProxy.Fault.NONE);PROXY.release.countDown();
            assertEquals(409,inFlight.get(5,java.util.concurrent.TimeUnit.SECONDS).statusCode(),"Cancelled uploads must reject a stale storage part report");
            assertEquals("CANCELLED",json(owner.get("/uploads/"+id)).path("data").path("status").asText());
        }finally{PROXY.fault.set(SiloFaultProxy.Fault.NONE);PROXY.release.countDown();}
    }

    @Test
    void abortFailureRetainsBytesAndReconcilesALateRealPutBeforeReleasingCapacity() throws Exception {
        var owner=signedIn("abort-failure-owner");var upload=uploadedPart(owner,"abort-failure");
        String id=upload.path("uploadId").asText();
        PROXY.abortAttempts.set(0);PROXY.fault.set(SiloFaultProxy.Fault.ABORT_UNAVAILABLE);
        try {
            var cancelled=json(owner.post("/uploads/"+id+"/cancel","{}")).path("data");
            assertEquals("CANCELLED",cancelled.path("status").asText());
            long until=System.nanoTime()+java.time.Duration.ofSeconds(2).toNanos();
            while(PROXY.abortAttempts.get()==0&&System.nanoTime()<until)Thread.sleep(25);
            org.junit.jupiter.api.Assertions.assertTrue(PROXY.abortAttempts.get()>0);
            var late=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(upload.path("url").asText())).PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[19])).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,late.statusCode(),"The actual still-valid URL must create the late part during the injected abort failure");
            var state=json(owner.get("/uploads/"+id)).path("data");
            assertEquals("PENDING",state.path("cleanupStatus").asText());assertEquals(19,state.path("reservedBytes").asLong());assertEquals(0,state.path("reservedSlots").asInt());
        }finally{PROXY.fault.set(SiloFaultProxy.Fault.NONE);}
        awaitClean(owner,id,20);
    }

    @Test
    void cancellationWinsAgainstAnActuallyCompletedObjectBeforeAssetPublication() throws Exception {
        var owner=signedIn("complete-cancel-owner");var upload=uploadedPart(owner,"complete-cancel");
        String id=upload.path("uploadId").asText();
        String csrf=json(owner.get("/auth/csrf")).path("data").path("csrfToken").asText();
        String body=new ObjectMapper().writeValueAsString(java.util.Map.of("writerToken",upload.path("writerToken").asText()));
        PROXY.blocked=new java.util.concurrent.CountDownLatch(1);PROXY.release=new java.util.concurrent.CountDownLatch(1);PROXY.fault.set(SiloFaultProxy.Fault.BLOCK_COMPLETE_RESPONSE);
        try {
            var completion=owner.client.sendAsync(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/uploads/"+id+"/complete")).header("Content-Type","application/json").header("X-XSRF-TOKEN",csrf).POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
            org.junit.jupiter.api.Assertions.assertTrue(PROXY.blocked.await(3,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(200,owner.post("/uploads/"+id+"/cancel","{}").statusCode());
            PROXY.fault.set(SiloFaultProxy.Fault.NONE);PROXY.release.countDown();
            int status=completion.get(10,java.util.concurrent.TimeUnit.SECONDS).statusCode();
            org.junit.jupiter.api.Assertions.assertTrue(status==409||status==503,"A revoked completion cannot publish an asset");
            assertEquals("CANCELLED",json(owner.get("/uploads/"+id)).path("data").path("status").asText());
            assertEquals(0,json(owner.get("/projects/"+upload.path("projectId").asText()+"/assets?includePending=true")).path("data").path("items").size());
        }finally{PROXY.fault.set(SiloFaultProxy.Fault.NONE);PROXY.release.countDown();}
        awaitClean(owner,id,40);
    }

    @Test
    void replayingCreationCannotReviveCancellationDuringActualMultipartInitialization() throws Exception {
        var owner=signedIn("initialization-cancel-owner");
        String project=json(owner.post("/projects","{\"name\":\"初始化竞争\"}")).path("data").path("id").asText();
        String csrf=json(owner.get("/auth/csrf")).path("data").path("csrfToken").asText();
        String body=new ObjectMapper().writeValueAsString(java.util.Map.of("filename","initialize.mp4","sizeBytes",19,"sha256","f".repeat(64),"idempotencyKey","initialize-cancel","writerId",owner.writerId));
        PROXY.blocked=new java.util.concurrent.CountDownLatch(1);PROXY.release=new java.util.concurrent.CountDownLatch(1);PROXY.fault.set(SiloFaultProxy.Fault.BLOCK_CREATE_RESPONSE);
        try {
            var creation=owner.client.sendAsync(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/projects/"+project+"/uploads")).header("Content-Type","application/json").header("X-XSRF-TOKEN",csrf).POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
            org.junit.jupiter.api.Assertions.assertTrue(PROXY.blocked.await(3,java.util.concurrent.TimeUnit.SECONDS));
            String id=json(owner.get("/projects/"+project+"/uploads")).path("data").path("items").get(0).path("uploadId").asText();
            assertEquals(200,owner.post("/uploads/"+id+"/cancel","{}").statusCode());
            PROXY.fault.set(SiloFaultProxy.Fault.NONE);PROXY.release.countDown();
            assertEquals(201,creation.get(5,java.util.concurrent.TimeUnit.SECONDS).statusCode());
            var replay=owner.post("/projects/"+project+"/uploads",body);
            assertEquals(201,replay.statusCode(),replay.body());
            assertEquals("CANCELLED",json(replay).path("data").path("status").asText(),"Creation replay must retain revocation even when initialization lost its publication lease");
        }finally{PROXY.fault.set(SiloFaultProxy.Fault.NONE);PROXY.release.countDown();}
    }

    @Test
    void cleanedCancellationRechecksLatePhysicalResidueAndRetainsCapacityDuringAbortFailure() throws Exception {
        var owner=signedIn("late-residue-owner");var upload=uploadedPart(owner,"late-residue");String id=upload.path("uploadId").asText();
        assertEquals(200,owner.post("/uploads/"+id+"/cancel","{}").statusCode());awaitClean(owner,id,20);
        PROXY.fault.set(SiloFaultProxy.Fault.ABORT_UNAVAILABLE);
        try(var silo=software.amazon.awssdk.services.s3.S3Client.builder().endpointOverride(URI.create("http://localhost:"+SILO.getMappedPort(9000)))
                .region(software.amazon.awssdk.regions.Region.US_EAST_1).forcePathStyle(true).credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("integration-user","integration-password-strong"))).build()) {
            // External late materialization: an object-store operation outlives the first empty verification.
            String key="originals/"+id;String multipart=silo.createMultipartUpload(b->b.bucket("originals").key(key)).uploadId();
            silo.uploadPart(b->b.bucket("originals").key(key).uploadId(multipart).partNumber(1),software.amazon.awssdk.core.sync.RequestBody.fromBytes(new byte[19]));
            long until=System.nanoTime()+java.time.Duration.ofSeconds(6).toNanos();JsonNode state;
            do{state=json(owner.get("/uploads/"+id)).path("data");if("PENDING".equals(state.path("cleanupStatus").asText()))break;Thread.sleep(100);}while(System.nanoTime()<until);
            assertEquals("PENDING",state.path("cleanupStatus").asText(),"A first empty storage check must not permanently stop residue reconciliation");
            assertEquals(19,state.path("reservedBytes").asLong());assertEquals(0,state.path("reservedSlots").asInt());
        }finally{PROXY.fault.set(SiloFaultProxy.Fault.NONE);}
        awaitClean(owner,id,20);
    }

    @Test
    void rejectedPartFactsDoNotExtendTheLastLegalActivityDeadline() throws Exception {
        var owner=signedIn("invalid-activity-owner");var upload=uploadedPart(owner,"invalid-activity");String id=upload.path("uploadId").asText();
        String deadline=json(owner.get("/uploads/"+id)).path("data").path("expiresAt").asText();
        String csrf=json(owner.get("/auth/csrf")).path("data").path("csrfToken").asText();
        String report=new ObjectMapper().writeValueAsString(java.util.Map.of("writerToken",upload.path("writerToken").asText(),"etag","wrong-etag","sizeBytes",19));
        var rejected=owner.client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/uploads/"+id+"/parts/1")).header("Content-Type","application/json").header("X-XSRF-TOKEN",csrf).PUT(HttpRequest.BodyPublishers.ofString(report)).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(409,rejected.statusCode());
        assertEquals(deadline,json(owner.get("/uploads/"+id)).path("data").path("expiresAt").asText(),"Rejected part facts must not renew the upload recovery deadline");
    }

    @Test
    void aRealStreamingPutFinishingAfterTheDrainWindowCannotLeaveAnUnaccountedUpload() throws Exception {
        var owner=signedIn("streaming-cancel-owner");var upload=uploadedPart(owner,"streaming-cancel");String id=upload.path("uploadId").asText();
        var address=URI.create(upload.path("url").asText());
        try(var socket=new java.net.Socket(address.getHost(),address.getPort())) {
            socket.setSoTimeout(10000);
            String headers="PUT "+address.getRawPath()+"?"+address.getRawQuery()+" HTTP/1.1\r\nHost: "+address.getHost()+":"+address.getPort()+"\r\nContent-Length: 19\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(headers.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            socket.getOutputStream().write(37);socket.getOutputStream().flush();
            Thread.sleep(100);
            assertEquals(200,owner.post("/uploads/"+id+"/cancel","{}").statusCode());
            awaitClean(owner,id,20);
            Thread.sleep(2000); // The real request remains open beyond URL expiry and the first empty check.
            socket.getOutputStream().write(new byte[18]);socket.getOutputStream().flush();
            String reply=new String(socket.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            org.junit.jupiter.api.Assertions.assertTrue(reply.startsWith("HTTP/1.1 200")||reply.startsWith("HTTP/1.1 404"),"Silo must settle the accepted streaming request");
        }
        Thread.sleep(1500);awaitClean(owner,id,20);
        assertEquals("CANCELLED",json(owner.get("/uploads/"+id)).path("data").path("status").asText());
        try(var silo=software.amazon.awssdk.services.s3.S3Client.builder().endpointOverride(URI.create("http://localhost:"+SILO.getMappedPort(9000)))
                .region(software.amazon.awssdk.regions.Region.US_EAST_1).forcePathStyle(true).credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("integration-user","integration-password-strong"))).build()) {
            assertEquals(0,silo.listMultipartUploads(b->b.bucket("originals").prefix("originals/"+id)).uploads().size());
            assertEquals(0,silo.listObjectsV2(b->b.bucket("originals").prefix("originals/"+id)).contents().size());
        }
    }

    private JsonNode uploadedPart(BrowserApi owner,String key) throws Exception {
        String project=json(owner.post("/projects","{\"name\":\"存储故障竞争\"}")).path("data").path("id").asText();
        var upload=(com.fasterxml.jackson.databind.node.ObjectNode)json(owner.post("/projects/"+project+"/uploads",new ObjectMapper().writeValueAsString(java.util.Map.of(
                "filename","race.mp4","sizeBytes",19,"sha256","f".repeat(64),"idempotencyKey",key,"writerId",owner.writerId)))).path("data");
        String id=upload.path("uploadId").asText(),writer=upload.path("writerToken").asText();
        String url=json(owner.post("/uploads/"+id+"/part-urls","{\"partNumbers\":[1],\"writerToken\":\""+writer+"\"}")).path("data").path("parts").get(0).path("url").asText();
        assertEquals(200,HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[19])).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        upload.put("url",url);upload.put("projectId",project);return upload;
    }
    private void awaitClean(BrowserApi owner,String id,int seconds) throws Exception {
        long until=System.nanoTime()+java.time.Duration.ofSeconds(seconds).toNanos();JsonNode state;
        do{state=json(owner.get("/uploads/"+id)).path("data");if("CLEANED".equals(state.path("cleanupStatus").asText()))break;Thread.sleep(100);}while(System.nanoTime()<until);
        assertEquals("CLEANED",state.path("cleanupStatus").asText(),state.toString());assertEquals(0,state.path("reservedBytes").asLong());
    }

    private BrowserApi signedIn(String username) throws Exception {
        BrowserApi browser = new BrowserApi();
        String credentials = new ObjectMapper().writeValueAsString(java.util.Map.of("username", username, "password", "project-strong-password"));
        assertEquals(201, browser.post("/auth/register", credentials).statusCode());
        assertEquals(200, browser.post("/auth/login", credentials).statusCode());
        owners.add(browser);
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
