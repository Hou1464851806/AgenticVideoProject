package com.agenticvideo.business.task;

import com.agenticvideo.business.bailian.BailianAsr;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real upload, PostgreSQL, Silo, RabbitMQ and Qdrant; deterministic provider HTTP fault cases. */
class LocalAnalysisIntegrationTest extends AnalysisIntegrationSupport {
    static final ObjectMapper JSON=new ObjectMapper();
    static final AtomicInteger submits=new AtomicInteger(),images=new AtomicInteger(),embeds=new AtomicInteger();
    static volatile boolean loseImageResponse,holdAsrQueries,invalidImageResponse;
    static final AtomicInteger queries=new AtomicInteger();
    static final HttpServer PROVIDER=startProvider();
    static HttpServer startProvider() {
        try {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{
                Object reply;String path=exchange.getRequestURI().getPath();
                if(path.endsWith("/transcription")) {
                    submits.incrementAndGet();reply=Map.of("request_id","submit-request","output",Map.of("task_id",UUID.randomUUID().toString(),"task_status","PENDING"));
                }else if(path.contains("/tasks/")) {
                    queries.incrementAndGet();
                    reply=Map.of("request_id","query-request","usage",Map.of("seconds",2),"output",Map.of("task_status",holdAsrQueries?"RUNNING":"SUCCEEDED","result",Map.of("transcription_url","https://transcript.aliyuncs.com/test")));
                }else if(path.endsWith("/generation")) {
                    images.incrementAndGet();
                    if(loseImageResponse){exchange.close();return;}
                    if(invalidImageResponse) {reply=Map.of("request_id","invalid-image-"+images.get(),"usage",Map.of("input_tokens",30,"output_tokens",10),"output",Map.of("choices",List.of()));}
                    else reply=Map.of("request_id","image-"+images.get(),"usage",Map.of("input_tokens",30,"output_tokens",10),"output",Map.of("choices",List.of(Map.of("message",Map.of("content",List.of(Map.of("text","{\"description\":\"红色矩形\",\"visibleText\":[\"证据42\"]}")))))));
                }else if(path.endsWith("/text-embedding")) {
                    embeds.incrementAndGet();var input=JSON.readTree(exchange.getRequestBody());var result=new ArrayList<Object>();
                    for(int i=0;i<input.path("input").path("texts").size();i++){var vector=new double[input.path("parameters").path("dimension").asInt()];vector[0]=1;result.add(Map.of("text_index",i,"embedding",vector));}
                    reply=Map.of("request_id","embed-"+embeds.get(),"usage",Map.of("total_tokens",10),"output",Map.of("embeddings",result));
                }else{exchange.sendResponseHeaders(404,-1);exchange.close();return;}
                byte[] body=JSON.writeValueAsBytes(reply);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
            });
            server.start();return server;
        }catch(Exception failure){throw new ExceptionInInitializerError(failure);}
    }
    @DynamicPropertySource static void provider(DynamicPropertyRegistry properties) {
        properties.add("DASHSCOPE_BASE_URL",()->"http://127.0.0.1:"+PROVIDER.getAddress().getPort());
        properties.add("DASHSCOPE_API_KEY",()->"integration-fake-key");
    }
    @MockitoSpyBean BailianAsr asr;
    @MockitoSpyBean com.agenticvideo.business.search.SearchIndexBuilder builder;
    @MockitoSpyBean LocalTaskRunner runtime;
    @BeforeEach void transferBoundary() throws Exception {
        // Private provider transfer and transcript downloads are separately exercised by live tests.
        doReturn("oss://integration/audio.wav").when(asr).upload(any(),anyString());
        doReturn(JSON.readTree("{\"transcripts\":[{\"sentences\":[{\"begin_time\":0,\"end_time\":1000,\"text\":\"张明上传素材\",\"words\":[{\"begin_time\":0,\"end_time\":1000,\"text\":\"张明上传素材\"}]}]}]}")).when(asr).transcript(any());
    }
    @MockitoSpyBean com.agenticvideo.business.media.MediaProcessing media;
    @org.springframework.beans.factory.annotation.Autowired org.springframework.amqp.rabbit.connection.CachingConnectionFactory connection;

    @Test void committedResultSurvivesConnectionLossBeforeAckWithoutRepeatingPaidCalls() throws Exception {
        var disconnected=new java.util.concurrent.atomic.AtomicBoolean();
        var deliveries=new AtomicInteger();
        var paidAtDisconnect=new AtomicInteger(-1);
        doAnswer(invocation->{
            UUID id=invocation.getArgument(0);
            Object result=invocation.callRealMethod();
            if("VISION_ANALYZE".equals(tasks.read(id).get("type"))) {
                deliveries.incrementAndGet();
                if("SUCCEEDED".equals(tasks.read(id).get("status")) && disconnected.compareAndSet(false,true)) {
                    // The real listener has not ACKed yet. Close its actual broker connection.
                    paidAtDisconnect.set(images.get());
                    connection.resetConnection();
                }
            }
            return result;
        }).when(runtime).execute(any());
        int before=images.get();
        var uploaded=upload("labelled-vision-vfr.mp4");awaitAnalysis(uploaded);
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(20).toNanos();
        while(deliveries.get()<2 && System.nanoTime()<deadline)Thread.sleep(100);
        assertTrue(disconnected.get());assertTrue(deliveries.get()>=2,"Unacked delivery must actually be redelivered");
        UUID task=db.queryForObject("SELECT id FROM tasks WHERE type='VISION_ANALYZE' AND input_json->>'assetId'=?",UUID.class,uploaded.asset().toString());
        assertEquals(1,tasks.attemptCount(task));assertEquals(paidAtDisconnect.get(),images.get());
        faultReport("ack-loss",Map.of("deliveries",deliveries.get(),"paid_visual_calls",images.get()-before,"attempts",tasks.attemptCount(task)));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM execution_reports WHERE task_id=? AND report_kind='COMPLETE'",Integer.class,task));
    }

    @Test void queuedMediaBacklogKeepsActualExecutionConcurrencyAtOne() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var running=new AtomicInteger();var peak=new AtomicInteger();
        doAnswer(invocation->{
            int current=running.incrementAndGet();peak.accumulateAndGet(current,Math::max);entered.countDown();
            try {assertTrue(release.await(20,java.util.concurrent.TimeUnit.SECONDS));return invocation.callRealMethod();}
            finally {running.decrementAndGet();}
        }).when(media).execute(any(),any(),anyLong(),any());
        var uploads=new ArrayList<Uploaded>();
        try {
            uploads.add(upload("labelled-vision-vfr.mp4"));
            assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS));
            uploads.add(upload("labelled-vision-vfr.mp4"));uploads.add(upload("labelled-vision-vfr.mp4"));
            long deadline=System.nanoTime()+java.time.Duration.ofSeconds(8).toNanos();
            int queued=0;
            while(System.nanoTime()<deadline) {
                queued=(Integer)broker.execute(channel->channel.queueDeclarePassive("media.v1").getMessageCount());
                if(queued>=2)break;Thread.sleep(100);
            }
            assertTrue(queued>=2,"Must measure a real queue backlog");
            assertEquals(1,running.get());assertEquals(1,peak.get());
        }finally {release.countDown();}
        for(var uploaded:uploads)awaitAnalysis(uploaded);
        assertEquals(1,peak.get());
        faultReport("backlog",Map.of("uploads",uploads.size(),"minimum_observed_queue_depth",2,"peak_actual_media_execution",peak.get()));
    }

    private void faultReport(String name,Object value) throws Exception {
        var directory=java.nio.file.Path.of("target/m1-faults");java.nio.file.Files.createDirectories(directory);
        java.nio.file.Files.writeString(directory.resolve(name+".json"),JSON.writeValueAsString(value));
    }

    @AfterAll static void closeProvider(){PROVIDER.stop(0);}
    @Test void uploadPublishesSourcesAndQueryableIndexWithoutRepeatingPaidSuccess() throws Exception {
        var uploaded=upload("labelled-speech.mp4");awaitAnalysis(uploaded);
        assertEquals(1436,db.queryForObject("SELECT min(start_ms) FROM evidence_segments WHERE asset_id=? AND source_kind='SPEECH'",Long.class,uploaded.asset()));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM evidence_segments WHERE asset_id=? AND source_kind<>'SPEECH' AND start_ms<>end_ms",Integer.class,uploaded.asset()));
        assertTrue(db.queryForObject("SELECT count(*) FROM evidence_segments WHERE asset_id=? AND source_kind='VISIBLE_TEXT'",Integer.class,uploaded.asset())>0);
        int beforeSubmit=submits.get(),beforeImages=images.get(),beforeEmbed=embeds.get();
        for(var task:db.queryForList("SELECT id FROM tasks WHERE input_json->>'assetId'=?",uploaded.asset().toString()))broker.convertAndSend("analysis.v1",JSON.writeValueAsBytes(Map.of("taskId",task.get("id"))));
        Thread.sleep(1200);assertEquals(beforeSubmit,submits.get());assertEquals(beforeImages,images.get());assertEquals(beforeEmbed,embeds.get());
        // Force a new generation using saved vectors; rebuilding never reruns ASR/VLM/Embedding.
        var receipt=json(uploaded.browser().post("/assets/"+uploaded.asset()+"/search-index","{}")).path("data");
        assertFalse(receipt.path("taskId").asText().isBlank(),receipt.toString());awaitState(UUID.fromString(receipt.path("taskId").asText()),"SUCCEEDED");
        assertEquals(beforeSubmit,submits.get());assertEquals(beforeImages,images.get());assertEquals(beforeEmbed,embeds.get());
        var generation=db.queryForMap("SELECT g.* FROM search_index_heads h JOIN search_index_generations g ON g.id=h.generation_id WHERE h.asset_id=?",uploaded.asset());
        var points=vectors.query(vectors.document("证据42"),"bm25",com.agenticvideo.business.search.SearchVectors.scope(uploaded.project(),List.of(generation.get("id").toString())),5);
        assertFalse(points.isEmpty());
    }
    @Test void safeIndexWriteFailureRetriesSavedBatchesWithoutRepeatingEmbedding() throws Exception {
        var writes=new AtomicInteger();
        doAnswer(invocation->{if(writes.incrementAndGet()==1)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"Qdrant unavailable");return invocation.callRealMethod();}).when(builder).write(any(),any());
        int before=embeds.get();var uploaded=upload("labelled-vision-vfr.mp4");awaitAnalysis(uploaded);
        UUID task=db.queryForObject("SELECT id FROM tasks WHERE type='INDEX_BUILD' AND input_json->>'assetId'=?",UUID.class,uploaded.asset().toString());
        assertEquals(2,tasks.attemptCount(task));assertEquals(before+1,embeds.get());
        assertEquals(1,db.queryForObject("SELECT count(*) FROM execution_reports WHERE task_id=? AND report_kind='FAIL'",Integer.class,task));
    }
    @Test void malformedPaidObservationPreservesRawResponseAndUsage() throws Exception {
        invalidImageResponse=true;
        try {
            var uploaded=upload("labelled-vision-vfr.mp4");awaitState(uploaded.mediaTask(),"SUCCEEDED");
            UUID task=db.queryForObject("SELECT id FROM tasks WHERE type='VISION_ANALYZE' AND input_json->>'assetId'=?",UUID.class,uploaded.asset().toString());
            long deadline=System.nanoTime()+java.time.Duration.ofSeconds(20).toNanos();
            while(!"FAILED".equals(tasks.read(task).get("status"))&&System.nanoTime()<deadline)Thread.sleep(100);
            assertEquals("FAILED",tasks.read(task).get("status"));
            assertEquals(1,tasks.attemptCount(task));
            assertEquals(1,db.queryForObject("SELECT count(*) FROM vision_model_calls WHERE task_id=? AND response_json IS NOT NULL AND usage_json->>'input_tokens'='30' AND request_id LIKE 'invalid-image-%'",Integer.class,task));
            int before=images.get();
            var receipt=json(uploaded.browser().post("/assets/"+uploaded.asset()+"/analysis-stages/VISION/retry","{}")).path("data");
            UUID retried=UUID.fromString(receipt.path("taskId").asText());
            deadline=System.nanoTime()+java.time.Duration.ofSeconds(20).toNanos();while(!"FAILED".equals(tasks.read(retried).get("status"))&&System.nanoTime()<deadline)Thread.sleep(100);
            assertEquals("FAILED",tasks.read(retried).get("status"));assertEquals(before,images.get());
        }finally{invalidImageResponse=false;}
    }
    @Test void asyncWaitingReleasesConsumerAndAbandonedGenerationKeepsProviderIdentity() throws Exception {
        holdAsrQueries=true;int beforeSubmit=submits.get(),beforeQueries=queries.get();
        try {
            var uploaded=upload("labelled-speech.mp4");awaitState(uploaded.mediaTask(),"SUCCEEDED");
            long deadline=System.nanoTime()+java.time.Duration.ofSeconds(35).toNanos();
            while(queries.get()<beforeQueries+5&&System.nanoTime()<deadline)Thread.sleep(100);
            assertTrue(queries.get()>=beforeQueries+5);assertEquals(beforeSubmit+1,submits.get());
            UUID task=db.queryForObject("SELECT id FROM tasks WHERE type='CONTENT_ANALYZE' AND input_json->>'assetId'=?",UUID.class,uploaded.asset().toString());
            // Model a process that died after claiming its next read-only query.
            var manager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(db.getDataSource());
            UUID abandoned=UUID.randomUUID();
            new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(tx->{
                db.queryForMap("SELECT id FROM tasks WHERE id=? FOR UPDATE",task);
                db.update("INSERT INTO task_attempts(id,task_id,attempt_number,executor_id,claim_request_id,lease_token_hash,lease_expires_at,local_execution) SELECT ?,?,coalesce(max(attempt_number),0)+1,'local',?,'',now(),true FROM task_attempts WHERE task_id=?",abandoned,task,abandoned,task);
                db.update("UPDATE tasks SET status='RUNNING',current_attempt_id=? WHERE id=?",abandoned,task);
            });
            holdAsrQueries=false;awaitAnalysis(uploaded);
            assertEquals(beforeSubmit+1,submits.get());
            assertEquals("EXPIRED",db.queryForObject("SELECT status FROM task_attempts WHERE id=?",String.class,abandoned));
            var late=new TaskExecution.LocalRun(task,abandoned,JSON.readTree(tasks.read(task).get("input_json").toString()));
            assertThrows(org.springframework.web.server.ResponseStatusException.class,()->tasks.completeLocal(late,"SPEECH_ANALYZED",Map.of("modelCallId",UUID.randomUUID())));
            assertTrue(tasks.attemptCount(task)>3); // Successful ASR polling does not exhaust failure retries.
        }finally{holdAsrQueries=false;}
    }
    @Test void lostPaidResponseStopsAutomaticAndManualResubmission() throws Exception {
        loseImageResponse=true;
        try {
            var uploaded=upload("labelled-vision-vfr.mp4");awaitState(uploaded.mediaTask(),"SUCCEEDED");
            UUID task=db.queryForObject("SELECT id FROM tasks WHERE type='VISION_ANALYZE' AND input_json->>'assetId'=?",UUID.class,uploaded.asset().toString());
            long deadline=System.nanoTime()+java.time.Duration.ofSeconds(20).toNanos();
            while(!"FAILED".equals(tasks.read(task).get("status"))&&System.nanoTime()<deadline)Thread.sleep(100);
            assertEquals("FAILED",tasks.read(task).get("status"));assertEquals(1,tasks.attemptCount(task));
            assertEquals(1,db.queryForObject("SELECT count(*) FROM vision_model_calls WHERE task_id=? AND status='UNCERTAIN'",Integer.class,task));
            int before=images.get();broker.convertAndSend("analysis.v1",JSON.writeValueAsBytes(Map.of("taskId",task)));Thread.sleep(1200);assertEquals(before,images.get());
            // A manual retry may reuse succeeded calls, but cannot submit the uncertain image again.
            var receipt=json(uploaded.browser().post("/assets/"+uploaded.asset()+"/analysis-stages/VISION/retry","{}")).path("data");
            UUID retried=UUID.fromString(receipt.path("taskId").asText());
            deadline=System.nanoTime()+java.time.Duration.ofSeconds(20).toNanos();while(!"FAILED".equals(tasks.read(retried).get("status"))&&System.nanoTime()<deadline)Thread.sleep(100);
            assertEquals("FAILED",tasks.read(retried).get("status"));assertEquals(before,images.get());
            assertEquals("NOT_APPLICABLE",db.queryForObject("SELECT status FROM analysis_stages WHERE asset_id=? AND stage_key='ASR'",String.class,uploaded.asset()));
        }finally{loseImageResponse=false;}
    }
}
