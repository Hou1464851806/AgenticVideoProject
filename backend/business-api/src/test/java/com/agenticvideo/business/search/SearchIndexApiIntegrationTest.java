package com.agenticvideo.business.search;

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
class SearchIndexApiIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6-bookworm");
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("internal.worker-token", () -> "conversation-integration-worker-token");
    }
    @Container static final org.testcontainers.containers.GenericContainer<?> QDRANT=new org.testcontainers.containers.GenericContainer<>("qdrant/qdrant:v1.19.2@sha256:b7b0444c4c351c970b98e90a6f89c2ee4287c65b44e52b4cb503fa5b2aa927ad")
        .withExposedPorts(6333).waitingFor(org.testcontainers.containers.wait.strategy.Wait.forHttp("/readyz").forPort(6333));
    @DynamicPropertySource static void vectors(DynamicPropertyRegistry properties){properties.add("QDRANT_URL",()->"http://localhost:"+PROXY.getAddress().getPort());}
    private static final java.util.concurrent.atomic.AtomicBoolean DELAY_COUNT=new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.ExecutorService PROXY_THREADS=java.util.concurrent.Executors.newCachedThreadPool();
    private static final com.sun.net.httpserver.HttpServer PROXY=proxy();
    private static com.sun.net.httpserver.HttpServer proxy() {
        try {
            var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
            server.setExecutor(PROXY_THREADS);
            server.createContext("/",exchange->{
                try {
                    if(exchange.getRequestURI().getPath().endsWith("/points/count")&&DELAY_COUNT.getAndSet(false))Thread.sleep(3000);
                    var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+QDRANT.getMappedPort(6333)+exchange.getRequestURI())).header("Content-Type","application/json").method(exchange.getRequestMethod(),HttpRequest.BodyPublishers.ofByteArray(exchange.getRequestBody().readAllBytes())).build(),HttpResponse.BodyHandlers.ofByteArray());
                    exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(response.statusCode(),response.body().length);exchange.getResponseBody().write(response.body());
                } catch(Exception failure){exchange.sendResponseHeaders(502,-1);}finally{exchange.close();}
            });server.start();return server;
        }catch(java.io.IOException failure){throw new IllegalStateException(failure);}
    }
    @org.junit.jupiter.api.AfterAll static void closeProxy(){PROXY.stop(0);PROXY_THREADS.shutdownNow();}
    @LocalServerPort int port;
    @org.springframework.beans.factory.annotation.Autowired org.springframework.jdbc.core.JdbcTemplate fixtures;

    @Test
    void rebuildingAfterIndexFailureReusesDurableVectorsWithoutRepeatingPaidCalls() throws Exception {
        BrowserApi owner=signedIn("index-recovery-owner"),outsider=signedIn("index-recovery-other");
        String project=json(owner.post("/projects","{\"name\":\"向量恢复\"}")).path("data").path("id").asText();
        var speech=speechFixture(project);
        assertEquals(404,outsider.post("/assets/"+speech.asset()+"/search-index","{}").statusCode());
        var accepted=owner.post("/assets/"+speech.asset()+"/search-index","{}");
        assertEquals(202,accepted.statusCode(),accepted.body());
        String task=json(accepted).path("data").path("taskId").asText();
        assertEquals(task,json(owner.post("/assets/"+speech.asset()+"/search-index","{}")).path("data").path("taskId").asText());
        var claim=json(internal(task,"claim",java.util.Map.of("executorId","index-test","claimRequestId",java.util.UUID.randomUUID()))).path("data");
        var credential=new java.util.LinkedHashMap<String,Object>();credential.put("attemptId",claim.path("attemptId").asText());credential.put("leaseToken",claim.path("leaseToken").asText());
        var prepare=new java.util.LinkedHashMap<>(credential);prepare.put("action","PREPARE");prepare.put("data",java.util.Map.of("ordinal",0));
        assertEquals(200,internal(task,"provider-call",prepare).statusCode());
        assertEquals(409,internal(task,"provider-call",prepare).statusCode());
        var vector=java.util.Collections.nCopies(1024,0.5);
        var completed=new java.util.LinkedHashMap<>(credential);completed.put("action","SUCCEEDED");completed.put("data",java.util.Map.of("ordinal",0,"vectors",java.util.List.of(vector,vector),"requestId","durable-paid-request","usage",java.util.Map.of("total_tokens",24)));
        assertEquals(200,internal(task,"provider-call",completed).statusCode());
        assertEquals(200,internal(task,"provider-call",completed).statusCode());
        var fail=new java.util.LinkedHashMap<>(credential);fail.put("producerKey","index-unavailable");fail.put("code","INDEX_UNVERIFIED");fail.put("message","索引不可用");
        assertEquals(200,internal(task,"fail",fail).statusCode());
        String next=json(owner.post("/assets/"+speech.asset()+"/search-index","{}")).path("data").path("taskId").asText();
        var nextClaim=json(internal(next,"claim",java.util.Map.of("executorId","index-recovery","claimRequestId",java.util.UUID.randomUUID()))).path("data");
        var lookup=java.util.Map.of("attemptId",nextClaim.path("attemptId").asText(),"leaseToken",nextClaim.path("leaseToken").asText(),"action","LOOKUP","data",java.util.Map.of());
        var response=internal(next,"provider-call",lookup);assertEquals(200,response.statusCode(),response.body());
        var batch=json(response).path("data").path("batches").get(0);
        assertEquals("SUCCEEDED",batch.path("status").asText());
        assertEquals(1024,batch.path("vectors").get(0).size());
        var late=new java.util.LinkedHashMap<>(credential);late.put("producerKey","late-index");late.put("resultType","INDEX_BUILT");late.put("result",java.util.Map.of());
        assertEquals(409,internal(task,"complete",late).statusCode());
        assertEquals(202,owner.post("/tasks/"+next+"/cancel","{}").statusCode());
        assertEquals(409,internal(next,"provider-call",lookup).statusCode());
        var search=owner.post("/projects/"+project+"/search","{\"query\":\"上传素材\",\"literalPhrase\":true}");
        assertEquals(200,search.statusCode());assertEquals(false,json(search).path("data").path("semanticAvailable").asBoolean());
        assertEquals(1,json(search).path("data").path("hits").size());
    }

    @Test
    void nativeIndexMustBeVerifiedBeforePublicationAndCancelledGenerationNeverReplacesHead() throws Exception {
        BrowserApi owner=signedIn("index-publication-owner");
        String project=json(owner.post("/projects","{\"name\":\"原子发布\"}")).path("data").path("id").asText();
        var speech=speechFixture(project);
        var state=owner.get("/assets/"+speech.asset()+"/search-index");assertEquals(200,state.statusCode(),state.body());
        assertEquals(true,json(state).path("data").path("publishedGeneration").isNull());
        String task=json(owner.post("/assets/"+speech.asset()+"/search-index","{}")).path("data").path("taskId").asText();
        var claim=json(internal(task,"claim",java.util.Map.of("executorId","native-index","claimRequestId",java.util.UUID.randomUUID()))).path("data");
        var credential=new java.util.LinkedHashMap<String,Object>();credential.put("attemptId",claim.path("attemptId").asText());credential.put("leaseToken",claim.path("leaseToken").asText());
        var lookup=new java.util.LinkedHashMap<>(credential);lookup.put("action","LOOKUP");lookup.put("data",java.util.Map.of());
        var profile=json(internal(task,"provider-call",lookup)).path("data");
        assertEquals("text-embedding-v4",profile.path("model").asText());assertEquals(1024,profile.path("dimension").asInt());
        var batch=profile.path("batches").get(0);
        var call=new java.util.LinkedHashMap<>(credential);call.put("action","PREPARE");call.put("data",java.util.Map.of("ordinal",0));assertEquals(200,internal(task,"provider-call",call).statusCode());
        var vector=java.util.Collections.nCopies(1024,0.5);
        call.put("action","SUCCEEDED");call.put("data",java.util.Map.of("ordinal",0,"vectors",java.util.List.of(vector,vector),"requestId","publication-vector-fixture","usage",java.util.Map.of()));assertEquals(200,internal(task,"provider-call",call).statusCode());
        qdrant("PUT","/collections/"+SearchVectors.COLLECTION,java.util.Map.of("vectors",java.util.Map.of("dense",java.util.Map.of("size",1024,"distance","Cosine")),"sparse_vectors",java.util.Map.of("bm25",java.util.Map.of("modifier","idf"))));
        var complete=new java.util.LinkedHashMap<>(credential);complete.put("producerKey","native-index-built");complete.put("resultType","INDEX_BUILT");complete.put("result",java.util.Map.of("generationId",profile.path("generationId").asText()));
        assertEquals(409,internal(task,"complete",complete).statusCode());
        var points=new java.util.ArrayList<Object>();
        for(var unit:batch.path("units"))points.add(java.util.Map.of("id",unit.path("id").asText(),"vector",java.util.Map.of("dense",vector,"bm25",java.util.Map.of("text",unit.path("text").asText(),"model","qdrant/bm25","options",SearchVectors.BM25)),"payload",java.util.Map.of("unitId",unit.path("unitId").asText(),"generationId",profile.path("generationId").asText(),"projectId",project)));
        var incorrect=new java.util.ArrayList<Object>();
        for(var point:points) {
            var node=new ObjectMapper().valueToTree(point);var wrong=new java.util.ArrayList<Double>(java.util.Collections.nCopies(1024,0.0));wrong.set(0,1.0);
            ((com.fasterxml.jackson.databind.node.ObjectNode)node.path("vector")).set("dense",new ObjectMapper().valueToTree(wrong));incorrect.add(node);
        }
        qdrant("PUT","/collections/"+SearchVectors.COLLECTION+"/points?wait=true",java.util.Map.of("points",incorrect));
        assertEquals(409,internal(task,"complete",complete).statusCode());
        qdrant("PUT","/collections/"+SearchVectors.COLLECTION+"/points?wait=true",java.util.Map.of("points",points));
        assertEquals(false,json(owner.post("/projects/"+project+"/search","{\"query\":\"上传素材\",\"literalPhrase\":true}")).path("data").path("semanticAvailable").asBoolean());
        fixtures.update("UPDATE task_attempts SET lease_expires_at=clock_timestamp()+interval '1 second' WHERE id=?",java.util.UUID.fromString(claim.path("attemptId").asText()));
        DELAY_COUNT.set(true);
        assertEquals(409,internal(task,"complete",complete).statusCode());
        assertEquals(true,json(owner.get("/assets/"+speech.asset()+"/search-index")).path("data").path("publishedGeneration").isNull());
        fixtures.update("UPDATE task_attempts SET lease_expires_at=clock_timestamp()+interval '60 seconds' WHERE id=?",java.util.UUID.fromString(claim.path("attemptId").asText()));
        var published=internal(task,"complete",complete);assertEquals(200,published.statusCode(),published.body());assertEquals(200,internal(task,"complete",complete).statusCode());
        String generation=profile.path("generationId").asText();
        assertEquals(generation,json(owner.get("/assets/"+speech.asset()+"/search-index")).path("data").path("publishedGeneration").path("id").asText());
        String next=json(owner.post("/assets/"+speech.asset()+"/search-index","{}")).path("data").path("taskId").asText();
        var nextClaim=json(internal(next,"claim",java.util.Map.of("executorId","cancelled-index","claimRequestId",java.util.UUID.randomUUID()))).path("data");
        assertEquals(202,owner.post("/tasks/"+next+"/cancel","{}").statusCode());
        complete.put("attemptId",nextClaim.path("attemptId").asText());complete.put("leaseToken",nextClaim.path("leaseToken").asText());assertEquals(409,internal(next,"complete",complete).statusCode());
        assertEquals(generation,json(owner.get("/assets/"+speech.asset()+"/search-index")).path("data").path("publishedGeneration").path("id").asText());
    }
    private void qdrant(String method,String path,Object body) throws Exception {
        var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+QDRANT.getMappedPort(6333)+path)).header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(new ObjectMapper().writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(true,response.statusCode()/100==2,response.body());
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
