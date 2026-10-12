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
    @DynamicPropertySource static void vectors(DynamicPropertyRegistry properties){properties.add("QDRANT_URL",()->"http://localhost:"+PROXY.getAddress().getPort());properties.add("DASHSCOPE_BASE_URL",()->"http://localhost:"+PROXY.getAddress().getPort()+"/embedding");properties.add("DASHSCOPE_API_KEY",()->"external-query-vector-fixture");}
    private static final java.util.concurrent.atomic.AtomicBoolean DELAY_COUNT=new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.ExecutorService PROXY_THREADS=java.util.concurrent.Executors.newCachedThreadPool();
    private static final com.sun.net.httpserver.HttpServer PROXY=proxy();
    private static com.sun.net.httpserver.HttpServer proxy() {
        try {
            var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
            server.setExecutor(PROXY_THREADS);
            server.createContext("/",exchange->{
                try {
                    if(exchange.getRequestURI().getPath().startsWith("/embedding/")) {
                        // External query embedding fixture; index storage and ranking use real Qdrant.
                        var input=new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes());
                        var body=new ObjectMapper().writeValueAsBytes(java.util.Map.of("output",java.util.Map.of("embeddings",java.util.List.of(java.util.Map.of("embedding",java.util.Collections.nCopies(input.path("parameters").path("dimension").asInt(),0.5))))));
                        exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);return;
                    }
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
    void embeddingFailureIsVisibleSeparatelyAndRetryPreservesSpeechEvidence() throws Exception {
        BrowserApi owner=signedIn("embedding-stage-owner");
        String project=json(owner.post("/projects","{\"name\":\"阶段恢复\"}")).path("data").path("id").asText();
        var speech=speechFixture(project);
        String task=json(owner.post("/assets/"+speech.asset()+"/search-index","{}")).path("data").path("taskId").asText();
        assertEquals("PENDING",json(owner.get("/assets/"+speech.asset())).path("data").path("analysis").path("stages").path("EMBEDDING").asText());
        var claim=json(internal(task,"claim",java.util.Map.of("executorId","embedding-stages","claimRequestId",java.util.UUID.randomUUID()))).path("data");
        var credential=new java.util.LinkedHashMap<String,Object>();credential.put("attemptId",claim.path("attemptId").asText());credential.put("leaseToken",claim.path("leaseToken").asText());
        var failure=new java.util.LinkedHashMap<>(credential);failure.put("producerKey","embedding-unavailable");failure.put("code","EMBEDDING_UNVERIFIED");failure.put("message","Embedding 尚未提交，连接不可用");
        assertEquals(200,internal(task,"fail",failure).statusCode());
        var stages=json(owner.get("/assets/"+speech.asset())).path("data").path("analysis").path("stages");
        assertEquals("FAILED",stages.path("EMBEDDING").asText());assertEquals("NOT_STARTED",stages.path("INDEX").asText());
        var retry=owner.post("/assets/"+speech.asset()+"/analysis-stages/EMBEDDING/retry","{}");
        assertEquals(202,retry.statusCode(),retry.body());
        String next=json(retry).path("data").path("taskId").asText();
        assertEquals(next,json(owner.post("/assets/"+speech.asset()+"/analysis-stages/EMBEDDING/retry","{}")).path("data").path("taskId").asText());
        var observations=json(owner.get("/assets/"+speech.asset()+"/evidence")).path("data").path("observations");
        assertEquals(2,observations.size());assertEquals(speech.first().toString(),observations.get(0).path("id").asText());
        assertEquals(409,owner.post("/assets/"+speech.asset()+"/analysis-stages/ASR/retry","{}").statusCode());
    }

    @Test
    void incompatibleDimensionBuildsASeparateCollectionAndConfigurationIsValidated() throws Exception {
        BrowserApi owner=signedIn("dimension-owner"),outsider=signedIn("dimension-other");
        String project=json(owner.post("/projects","{\"name\":\"索引配置\"}")).path("data").path("id").asText();
        var speech=speechFixture(project);
        String config="{\"model\":\"text-embedding-v4\",\"dimension\":512,\"bm25AvgLength\":128}";
        assertEquals(404,outsider.post("/assets/"+speech.asset()+"/search-index",config).statusCode());
        var accepted=owner.post("/assets/"+speech.asset()+"/search-index",config);assertEquals(202,accepted.statusCode(),accepted.body());
        String task=json(accepted).path("data").path("taskId").asText();
        var claim=json(internal(task,"claim",java.util.Map.of("executorId","dimension-test","claimRequestId",java.util.UUID.randomUUID()))).path("data");
        var lookup=java.util.Map.of("attemptId",claim.path("attemptId").asText(),"leaseToken",claim.path("leaseToken").asText(),"action","LOOKUP","data",java.util.Map.of());
        var profile=json(internal(task,"provider-call",lookup)).path("data");
        assertEquals(512,profile.path("dimension").asInt());assertEquals(128,profile.path("bm25").path("avg_len").asInt());
        assertEquals(false,SearchVectors.COLLECTION.equals(profile.path("collection").asText()));
        assertEquals(task,json(owner.post("/assets/"+speech.asset()+"/search-index",config)).path("data").path("taskId").asText());
        assertEquals(409,owner.post("/assets/"+speech.asset()+"/search-index","{\"dimension\":1024}").statusCode());
        assertEquals(400,owner.post("/assets/"+speech.asset()+"/search-index","{\"dimension\":7}").statusCode());
        assertEquals(400,owner.post("/assets/"+speech.asset()+"/search-index","{\"model\":\"unknown-provider-model\"}").statusCode());
    }

    @Test
    void speechRetryResumesTheKnownProviderTaskAndOnlyTheFailedStage() throws Exception {
        BrowserApi owner=signedIn("speech-stage-owner");
        String project=json(owner.post("/projects","{\"name\":\"语音恢复\"}")).path("data").path("id").asText();
        var speech=speechFixture(project);
        var provider=java.util.UUID.randomUUID();
        var source=fixtures.queryForMap("SELECT c.task_id,c.analysis_id FROM model_calls c JOIN asset_analysis_heads h ON h.analysis_id=c.analysis_id WHERE h.asset_id=?",speech.asset());
        var task=source.get("task_id");
        fixtures.update("UPDATE tasks SET status='FAILED',input_json=jsonb_build_object('assetId',?::text,'analysisId',?::text,'model','qwen3-asr-flash-filetrans') WHERE id=?",speech.asset(),source.get("analysis_id"),task);
        fixtures.update("UPDATE model_calls SET status='SUBMITTED',provider_task_id=? WHERE task_id=?",provider,task);
        fixtures.update("INSERT INTO analysis_stages(asset_id,analysis_id,stage_key,status,task_id) VALUES (?,?,'ASR','FAILED',?)",speech.asset(),source.get("analysis_id"),task);
        var retry=owner.post("/assets/"+speech.asset()+"/analysis-stages/ASR/retry","{}");assertEquals(202,retry.statusCode(),retry.body());
        assertEquals(task.toString(),json(retry).path("data").path("taskId").asText());
        assertEquals(task.toString(),json(owner.post("/assets/"+speech.asset()+"/analysis-stages/ASR/retry","{}")).path("data").path("taskId").asText());
        var claim=json(internal(task.toString(),"claim",java.util.Map.of("executorId","speech-recovery","claimRequestId",java.util.UUID.randomUUID()))).path("data");
        var lookup=java.util.Map.of("attemptId",claim.path("attemptId").asText(),"leaseToken",claim.path("leaseToken").asText(),"action","LOOKUP","data",java.util.Map.of());
        var response=internal(task.toString(),"provider-call",lookup);assertEquals(200,response.statusCode(),response.body());
        assertEquals(provider.toString(),json(response).path("data").path("providerTaskId").asText());assertEquals(false,json(response).path("data").path("canSubmit").asBoolean());
        assertEquals(2,json(owner.get("/assets/"+speech.asset()+"/evidence")).path("data").path("observations").size());
    }

    @Test
    void explicitSpeechRetryCanReplaceAConfirmedFailedProviderCallButKeepsItsReceipt() throws Exception {
        BrowserApi owner=signedIn("speech-confirmed-failure-owner");
        String project=json(owner.post("/projects","{\"name\":\"提供方失败\"}")).path("data").path("id").asText();
        var speech=speechFixture(project);
        var source=fixtures.queryForMap("SELECT c.task_id,c.analysis_id FROM model_calls c JOIN asset_analysis_heads h ON h.analysis_id=c.analysis_id WHERE h.asset_id=?",speech.asset());
        var task=source.get("task_id");var provider=java.util.UUID.randomUUID();
        fixtures.update("UPDATE tasks SET status='FAILED',input_json=jsonb_build_object('assetId',?::text,'analysisId',?::text,'model','qwen3-asr-flash-filetrans') WHERE id=?",speech.asset(),source.get("analysis_id"),task);
        fixtures.update("UPDATE model_calls SET model='qwen3-asr-flash-filetrans',status='FAILED',provider_task_id=?,result_json='{\"status\":\"FAILED\",\"code\":\"INVALID_AUDIO\"}' WHERE task_id=?",provider,task);
        fixtures.update("INSERT INTO analysis_stages(asset_id,analysis_id,stage_key,status,task_id) VALUES (?,?,'ASR','FAILED',?)",speech.asset(),source.get("analysis_id"),task);
        fixtures.update("UPDATE model_calls SET result_json='{\"status\":\"UNKNOWN\",\"code\":\"FAILED\"}' WHERE task_id=?",task);
        assertEquals(409,owner.post("/assets/"+speech.asset()+"/analysis-stages/ASR/retry","{}").statusCode());
        fixtures.update("UPDATE model_calls SET result_json='{\"status\":\"FAILED\",\"code\":\"INVALID_AUDIO\"}' WHERE task_id=?",task);
        var retry=owner.post("/assets/"+speech.asset()+"/analysis-stages/ASR/retry","{}");assertEquals(202,retry.statusCode(),retry.body());
        var claim=json(internal(task.toString(),"claim",java.util.Map.of("executorId","speech-explicit-retry","claimRequestId",java.util.UUID.randomUUID()))).path("data");
        var lookup=java.util.Map.of("attemptId",claim.path("attemptId").asText(),"leaseToken",claim.path("leaseToken").asText(),"action","LOOKUP","data",java.util.Map.of());
        assertEquals("READY",json(internal(task.toString(),"provider-call",lookup)).path("data").path("status").asText());
        var calls=json(owner.get("/assets/"+speech.asset()+"/evidence")).path("data").path("providerCalls");
        assertEquals(2,calls.size());assertEquals(true,java.util.stream.StreamSupport.stream(calls.spliterator(),false).anyMatch(call->provider.toString().equals(call.path("providerTaskId").asText())&&"FAILED".equals(call.path("status").asText())));
    }

    @Test
    void temporaryMediaFailureHasIndependentStatusAndSafeOwnerRetry() throws Exception {
        BrowserApi owner=signedIn("media-stage-owner"),outsider=signedIn("media-stage-outsider");
        String project=json(owner.post("/projects","{\"name\":\"媒体验证恢复\"}")).path("data").path("id").asText();
        var speech=speechFixture(project);var task=java.util.UUID.randomUUID();
        fixtures.update("DELETE FROM asset_analysis_heads WHERE asset_id=?",speech.asset());
        fixtures.update("UPDATE assets SET validation_status='INVALID',error_json='{\"code\":\"MEDIA_EXECUTION_FAILED\",\"message\":\"存储连接中断\"}' WHERE id=?",speech.asset());
        fixtures.update("INSERT INTO tasks(id,project_id,type,status,input_json) VALUES (?,?,'MEDIA_PREPARE','FAILED',jsonb_build_object('assetId',?::text))",task,java.util.UUID.fromString(project),speech.asset());
        var state=json(owner.get("/assets/"+speech.asset())).path("data").path("analysis");
        assertEquals("FAILED",state.path("stages").path("MEDIA").asText());assertEquals(true,state.path("mediaRetryable").asBoolean());
        assertEquals(404,outsider.post("/assets/"+speech.asset()+"/analysis-stages/MEDIA/retry","{}").statusCode());
        var retry=owner.post("/assets/"+speech.asset()+"/analysis-stages/MEDIA/retry","{}");assertEquals(202,retry.statusCode(),retry.body());
        assertEquals(task.toString(),json(retry).path("data").path("taskId").asText());
        assertEquals(task.toString(),json(owner.post("/assets/"+speech.asset()+"/analysis-stages/MEDIA/retry","{}")).path("data").path("taskId").asText());
        assertEquals("VALIDATING",json(owner.get("/assets/"+speech.asset())).path("data").path("validationStatus").asText());
        fixtures.update("UPDATE tasks SET status='FAILED' WHERE id=?",task);
        fixtures.update("UPDATE assets SET validation_status='INVALID',error_json='{\"code\":\"MEDIA_INVALID\",\"message\":\"不能解码\"}' WHERE id=?",speech.asset());
        assertEquals(409,owner.post("/assets/"+speech.asset()+"/analysis-stages/MEDIA/retry","{}").statusCode());
    }

    @Test
    void independentCollectionRanksContributeEquallyWhenDimensionsCoexist() throws Exception {
        BrowserApi owner=signedIn("collection-ranking-owner");
        String project=json(owner.post("/projects","{\"name\":\"并存检索\"}")).path("data").path("id").asText();
        var first=speechFixture(project);var second=speechFixture(project);
        for(var speech:java.util.List.of(first,second))fixtures.update("DELETE FROM retrieval_units WHERE asset_id=? AND text LIKE '第二步%'",speech.asset());
        publishFixtureIndex(owner,first,512);publishFixtureIndex(owner,second,1024);
        var initialUsage=owner.get("/projects/"+project+"/usage/models");assertEquals(200,initialUsage.statusCode(),initialUsage.body());
        int before=json(initialUsage).path("data").path("callCount").asInt();
        var response=owner.post("/projects/"+project+"/search","{\"query\":\"上传素材\",\"literalPhrase\":false,\"limit\":20}");
        assertEquals(200,response.statusCode(),response.body());
        var hits=json(response).path("data").path("hits");assertEquals(2,hits.size());
        for(var hit:hits){assertEquals(1,hit.path("channelRanks").path("DENSE").asInt());assertEquals(1,hit.path("channelRanks").path("BM25").asInt());}
        var usage=json(owner.get("/projects/"+project+"/usage/models")).path("data");assertEquals(before+2,usage.path("callCount").asInt());
        long queries=java.util.stream.StreamSupport.stream(usage.path("items").spliterator(),false).filter(row->"QUERY_EMBEDDING".equals(row.path("purpose").asText())).count();assertEquals(2,queries);
        owner.post("/projects/"+project+"/search","{\"query\":\"上传素材\",\"literalPhrase\":true}");
        assertEquals(before+2,json(owner.get("/projects/"+project+"/usage/models")).path("data").path("callCount").asInt());
    }
    @Test void collectionsWithTheSameEmbeddingConfigurationShareOneQueryCall() throws Exception {
        var owner=signedIn("query-reuse-owner");String project=json(owner.post("/projects","{\"name\":\"同配置查询复用\"}")).path("data").path("id").asText();
        var first=speechFixture(project);var second=speechFixture(project);
        for(var speech:java.util.List.of(first,second))fixtures.update("DELETE FROM retrieval_units WHERE asset_id=? AND text LIKE '第二步%'",speech.asset());
        publishFixtureIndex(owner,first,512,256);publishFixtureIndex(owner,second,512,128);
        int before=json(owner.get("/projects/"+project+"/usage/models")).path("data").path("callCount").asInt();
        var response=owner.post("/projects/"+project+"/search","{\"query\":\"上传素材\",\"literalPhrase\":false}");assertEquals(200,response.statusCode(),response.body());
        assertEquals(2,json(response).path("data").path("hits").size());
        assertEquals(before+1,json(owner.get("/projects/"+project+"/usage/models")).path("data").path("callCount").asInt());
    }
    private void publishFixtureIndex(BrowserApi owner,Speech speech,int dimension) throws Exception {publishFixtureIndex(owner,speech,dimension,256);}
    private void publishFixtureIndex(BrowserApi owner,Speech speech,int dimension,int average) throws Exception {
        String task=json(owner.post("/assets/"+speech.asset()+"/search-index","{\"dimension\":"+dimension+",\"bm25AvgLength\":"+average+"}")).path("data").path("taskId").asText();
        var claim=json(internal(task,"claim",java.util.Map.of("executorId","ranking-index","claimRequestId",java.util.UUID.randomUUID()))).path("data");
        var call=new java.util.LinkedHashMap<String,Object>();call.put("attemptId",claim.path("attemptId").asText());call.put("leaseToken",claim.path("leaseToken").asText());call.put("action","LOOKUP");call.put("data",java.util.Map.of());
        var profile=json(internal(task,"provider-call",call)).path("data");var unit=profile.path("batches").get(0).path("units").get(0);
        call.put("action","PREPARE");call.put("data",java.util.Map.of("ordinal",0));assertEquals(200,internal(task,"provider-call",call).statusCode());
        var vector=java.util.Collections.nCopies(dimension,0.5);
        call.put("action","SUCCEEDED");call.put("data",java.util.Map.of("ordinal",0,"vectors",java.util.List.of(vector),"requestId","ranking-fixture","usage",java.util.Map.of()));assertEquals(200,internal(task,"provider-call",call).statusCode());
        String collection=profile.path("collection").asText();
        qdrant("PUT","/collections/"+collection,java.util.Map.of("vectors",java.util.Map.of("dense",java.util.Map.of("size",dimension,"distance","Cosine")),"sparse_vectors",java.util.Map.of("bm25",java.util.Map.of("modifier","idf"))));
        var point=java.util.Map.of("id",unit.path("id").asText(),"vector",java.util.Map.of("dense",vector,"bm25",java.util.Map.of("text",unit.path("text").asText(),"model","qdrant/bm25","options",new ObjectMapper().convertValue(profile.path("bm25"),java.util.Map.class))),"payload",java.util.Map.of("unitId",unit.path("unitId").asText(),"generationId",profile.path("generationId").asText(),"projectId",profile.path("projectId").asText(),"sourceKind","SPEECH"));
        qdrant("PUT","/collections/"+collection+"/points?wait=true",java.util.Map.of("points",java.util.List.of(point)));
        var complete=new java.util.LinkedHashMap<String,Object>();complete.put("attemptId",claim.path("attemptId").asText());complete.put("leaseToken",claim.path("leaseToken").asText());complete.put("producerKey","ranking-built");complete.put("resultType","INDEX_BUILT");complete.put("result",java.util.Map.of());
        var published=internal(task,"complete",complete);assertEquals(200,published.statusCode(),published.body());
    }

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
        String task=json(owner.post("/assets/"+speech.asset()+"/search-index","{\"dimension\":512}")).path("data").path("taskId").asText();
        var claim=json(internal(task,"claim",java.util.Map.of("executorId","native-index","claimRequestId",java.util.UUID.randomUUID()))).path("data");
        var credential=new java.util.LinkedHashMap<String,Object>();credential.put("attemptId",claim.path("attemptId").asText());credential.put("leaseToken",claim.path("leaseToken").asText());
        var lookup=new java.util.LinkedHashMap<>(credential);lookup.put("action","LOOKUP");lookup.put("data",java.util.Map.of());
        var profile=json(internal(task,"provider-call",lookup)).path("data");
        assertEquals("text-embedding-v4",profile.path("model").asText());assertEquals(512,profile.path("dimension").asInt());
        var batch=profile.path("batches").get(0);
        var call=new java.util.LinkedHashMap<>(credential);call.put("action","PREPARE");call.put("data",java.util.Map.of("ordinal",0));assertEquals(200,internal(task,"provider-call",call).statusCode());
        var vector=java.util.Collections.nCopies(512,0.5);
        call.put("action","SUCCEEDED");call.put("data",java.util.Map.of("ordinal",0,"vectors",java.util.List.of(vector,vector),"requestId","publication-vector-fixture","usage",java.util.Map.of()));assertEquals(200,internal(task,"provider-call",call).statusCode());
        call.put("action","INDEXING");call.put("data",java.util.Map.of());
        assertEquals(200,internal(task,"provider-call",call).statusCode());
        assertEquals("RUNNING",json(owner.get("/assets/"+speech.asset())).path("data").path("analysis").path("stages").path("INDEX").asText());
        qdrant("PUT","/collections/"+profile.path("collection").asText(),java.util.Map.of("vectors",java.util.Map.of("dense",java.util.Map.of("size",512,"distance","Cosine")),"sparse_vectors",java.util.Map.of("bm25",java.util.Map.of("modifier","idf"))));
        var complete=new java.util.LinkedHashMap<>(credential);complete.put("producerKey","native-index-built");complete.put("resultType","INDEX_BUILT");complete.put("result",java.util.Map.of("generationId",profile.path("generationId").asText()));
        assertEquals(409,internal(task,"complete",complete).statusCode());
        var points=new java.util.ArrayList<Object>();
        for(var unit:batch.path("units"))points.add(java.util.Map.of("id",unit.path("id").asText(),"vector",java.util.Map.of("dense",vector,"bm25",java.util.Map.of("text",unit.path("text").asText(),"model","qdrant/bm25","options",SearchVectors.BM25)),"payload",java.util.Map.of("unitId",unit.path("unitId").asText(),"generationId",profile.path("generationId").asText(),"projectId",project)));
        var incorrect=new java.util.ArrayList<Object>();
        for(var point:points) {
            var node=new ObjectMapper().valueToTree(point);var wrong=new java.util.ArrayList<Double>(java.util.Collections.nCopies(512,0.0));wrong.set(0,1.0);
            ((com.fasterxml.jackson.databind.node.ObjectNode)node.path("vector")).set("dense",new ObjectMapper().valueToTree(wrong));incorrect.add(node);
        }
        qdrant("PUT","/collections/"+profile.path("collection").asText()+"/points?wait=true",java.util.Map.of("points",incorrect));
        assertEquals(409,internal(task,"complete",complete).statusCode());
        qdrant("PUT","/collections/"+profile.path("collection").asText()+"/points?wait=true",java.util.Map.of("points",points));
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
        if("PUT".equals(method)&&path.startsWith("/collections/")&&!path.substring("/collections/".length()).contains("/")) {
            var existing=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+QDRANT.getMappedPort(6333)+path)).GET().build(),HttpResponse.BodyHandlers.ofString());
            if(existing.statusCode()==200)return;
        }
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
