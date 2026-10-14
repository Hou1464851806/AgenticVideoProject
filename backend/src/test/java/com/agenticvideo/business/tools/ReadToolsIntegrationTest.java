package com.agenticvideo.business.tools;

import com.agenticvideo.business.search.SearchVectors;
import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReadToolsIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:18.6-bookworm");
    @Container static final GenericContainer<?> QDRANT=new GenericContainer<>("qdrant/qdrant:v1.19.2@sha256:b7b0444c4c351c970b98e90a6f89c2ee4287c65b44e52b4cb503fa5b2aa927ad")
        .withExposedPorts(6333).waitingFor(org.testcontainers.containers.wait.strategy.Wait.forHttp("/readyz").forPort(6333));
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final AtomicInteger EMBEDDINGS=new AtomicInteger();
    private static final AtomicBoolean FAIL_EMBED=new AtomicBoolean(),FAIL_DENSE=new AtomicBoolean(),FAIL_BM25=new AtomicBoolean();
    private static final com.sun.net.httpserver.HttpServer PROXY=proxy();
    static com.sun.net.httpserver.HttpServer proxy() {
        try {
            var server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{
                try {
                    byte[] input=exchange.getRequestBody().readAllBytes();byte[] body;
                    if(exchange.getRequestURI().getPath().startsWith("/embedding/")) {
                        EMBEDDINGS.incrementAndGet();
                        if(FAIL_EMBED.get()){exchange.sendResponseHeaders(502,-1);return;}
                        body=JSON.writeValueAsBytes(Map.of("request_id",UUID.randomUUID().toString(),"usage",Map.of("total_tokens",3),"output",Map.of("embeddings",List.of(Map.of("embedding",Collections.nCopies(64,0.5))))));
                    } else {
                        var request=input.length==0?JSON.createObjectNode():JSON.readTree(input);
                        if(("dense".equals(request.path("using").asText())&&FAIL_DENSE.get())||("bm25".equals(request.path("using").asText())&&FAIL_BM25.get())){exchange.sendResponseHeaders(503,-1);return;}
                        var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+QDRANT.getMappedPort(6333)+exchange.getRequestURI())).header("Content-Type","application/json").method(exchange.getRequestMethod(),HttpRequest.BodyPublishers.ofByteArray(input)).build(),HttpResponse.BodyHandlers.ofByteArray());
                        exchange.sendResponseHeaders(response.statusCode(),response.body().length);exchange.getResponseBody().write(response.body());return;
                    }
                    exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
                } catch(Exception failure){exchange.sendResponseHeaders(502,-1);}finally{exchange.close();}
            });server.start();return server;
        }catch(java.io.IOException failure){throw new IllegalStateException(failure);}
    }
    @AfterAll static void stop(){PROXY.stop(0);}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",POSTGRES::getJdbcUrl);p.add("spring.datasource.username",POSTGRES::getUsername);p.add("spring.datasource.password",POSTGRES::getPassword);
        p.add("internal.worker-token",()->"question-integration-internal-token");
        p.add("QDRANT_URL",()->"http://localhost:"+PROXY.getAddress().getPort());
        p.add("DASHSCOPE_BASE_URL",()->"http://localhost:"+PROXY.getAddress().getPort()+"/embedding");p.add("DASHSCOPE_API_KEY",()->"fixture-only");
    }
    @Autowired JdbcTemplate db;
    @Autowired SearchVectors vectors;
    @LocalServerPort int port;
    @BeforeEach void reset(){FAIL_EMBED.set(false);FAIL_DENSE.set(false);FAIL_BM25.set(false);}
    record Asset(UUID project,UUID id,UUID analysis,UUID model,UUID task) {}
    Asset fixture(UUID project) {
        if(project==null) {
            UUID user=UUID.randomUUID();project=UUID.randomUUID();
            db.update("INSERT INTO users(id,normalized_username,password_hash) VALUES (?,?,'fixture')",user,user.toString());
            db.update("INSERT INTO projects(id,owner_user_id,name) VALUES (?,?,'工具验证')",project,user);
        }
        UUID upload=UUID.randomUUID(),asset=UUID.randomUUID(),analysis=UUID.randomUUID(),task=UUID.randomUUID(),model=UUID.randomUUID();String hash="b".repeat(64);
        db.update("INSERT INTO upload_sessions(id,project_id,actor_user_id,idempotency_key,filename,declared_bytes,declared_sha256,part_size_bytes,expected_parts,object_key,writer_token) SELECT ?,id,owner_user_id,?,'test.mp4',1,?,1,1,?,? FROM projects WHERE id=?",upload,upload.toString(),hash,upload.toString(),UUID.randomUUID(),project);
        db.update("INSERT INTO assets(id,project_id,upload_id,object_key,filename,byte_size,sha256,validation_status,duration_ms,media_info_json,time_mapping_json) VALUES (?,?,?,?,'测试.mp4',1,?,'VALID',20000,'{\"streams\":[{\"codec_type\":\"audio\",\"duration\":\"18.564\"}]}','{\"audioStartMs\":1436,\"formatStartMs\":0}')",asset,project,upload,asset.toString(),hash);
        db.update("INSERT INTO asset_analysis_revisions(id,project_id,asset_id,source_sha256,profile_hash,vision_profile_json) VALUES (?,?,?,?,'fixture','{\"kind\":\"SPARSE_FRAMES\",\"complete\":false}')",analysis,project,asset,hash);
        db.update("INSERT INTO asset_analysis_heads VALUES (?,?)",asset,analysis);
        db.update("INSERT INTO analysis_stages(asset_id,analysis_id,stage_key,status) VALUES (?,?,'ASR','SUCCEEDED'),(?,?,'VISION','PENDING')",asset,analysis,asset,analysis);
        db.update("INSERT INTO tasks(id,project_id,type,status,input_json) VALUES (?,?,'CONTENT_ANALYZE','SUCCEEDED','{}')",task,project);
        db.update("INSERT INTO model_calls(id,analysis_id,task_id,model,parameters_json,status) VALUES (?,?,?,'fixture','{}','SUCCEEDED')",model,analysis,task);
        return new Asset(project,asset,analysis,model,task);
    }
    UUID speech(Asset a,int ordinal,long start,long end,String text) {
        UUID evidence=UUID.randomUUID();
        db.update("INSERT INTO evidence_segments(id,project_id,asset_id,analysis_id,source_kind,ordinal,text,start_ms,end_ms,audio_start_ms,audio_end_ms,observation_json,model_call_id) VALUES (?,?,?,?,'SPEECH',?,?,?,?,?,?,'{}',?)",evidence,a.project,a.id,a.analysis,ordinal,text,start,end,start-1436,end-1436,a.model);
        unit(a,evidence);return evidence;
    }
    void unit(Asset a,UUID evidence) {
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO retrieval_units(id,project_id,asset_id,analysis_id,source_kind,text,start_ms,end_ms,rules_hash) SELECT ?,project_id,asset_id,analysis_id,source_kind,text,start_ms,end_ms,'fixture' FROM evidence_segments WHERE id=?",id,evidence);
        db.update("INSERT INTO unit_evidence_refs VALUES (?,?,0)",id,evidence);
    }
    UUID visual(Asset a,int ordinal,long time,String text) {
        UUID frame=UUID.randomUUID(),call=UUID.randomUUID(),evidence=UUID.randomUUID();String hash=UUID.randomUUID().toString().replace("-","").repeat(2);
        db.update("INSERT INTO vision_frames(id,analysis_id,ordinal,observed_at_ms,source_pts,object_key,sha256,size_bytes) VALUES (?,?,?,?,?,?,?,1)",frame,a.analysis,ordinal,time,Long.toString(time),frame.toString(),hash);
        db.update("INSERT INTO vision_model_calls(id,analysis_id,task_id,frame_sha256,model,parameters_json,status) VALUES (?,?,?,?,'fixture','{}','SUCCEEDED')",call,a.analysis,a.task,hash);
        db.update("INSERT INTO evidence_segments(id,project_id,asset_id,analysis_id,source_kind,ordinal,text,start_ms,end_ms,observation_json,frame_id,vision_call_id) VALUES (?,?,?,?,'VISUAL',?,?,?,?,'{}',?,?)",evidence,a.project,a.id,a.analysis,ordinal,text,time,time,frame,call);
        unit(a,evidence);return evidence;
    }
    UUID generation(Asset a,String collection) {
        UUID generation=UUID.randomUUID(),task=UUID.randomUUID();
        db.update("INSERT INTO tasks(id,project_id,type,status,input_json) VALUES (?,?,'INDEX_BUILD','SUCCEEDED','{}')",task,a.project);
        db.update("INSERT INTO search_index_generations(id,project_id,asset_id,analysis_id,task_id,model,dimension,profile_json,units_json,collection_name,status) VALUES (?,?,?,?,?,'text-embedding-v4',64,?::jsonb,'[]',?,'PUBLISHED')",generation,a.project,a.id,a.analysis,task,JSON.valueToTree(SearchVectors.BM25).toString(),collection);
        db.update("INSERT INTO search_index_heads VALUES (?,?) ON CONFLICT(asset_id) DO UPDATE SET generation_id=EXCLUDED.generation_id",a.id,generation);
        vectors.request("PUT","/collections/"+collection,Map.of("vectors",Map.of("dense",Map.of("size",64,"distance","Cosine")),"sparse_vectors",Map.of("bm25",Map.of("modifier","idf"))));
        var points=new ArrayList<Object>();
        for(var row:db.queryForList("SELECT * FROM retrieval_units WHERE asset_id=?",a.id))points.add(Map.of("id",row.get("id"),"vector",Map.of("dense",Collections.nCopies(64,0.5),"bm25",vectors.document(row.get("text").toString(),new SearchVectors.Profile("text-embedding-v4",64,SearchVectors.BM25,collection))),"payload",Map.of("unitId",row.get("id").toString(),"generationId",generation.toString(),"projectId",a.project.toString(),"sourceKind",row.get("source_kind"))));
        vectors.request("PUT","/collections/"+collection+"/points?wait=true",Map.of("points",points));return generation;
    }
    HttpResponse<String> http(String path,Object body,String token) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/internal/v1/questions"+path)).header("Content-Type","application/json");
        if(token!=null)request.header("Authorization","Bearer "+token);
        if(body==null)request.GET();else request.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> http(String path,Object body) throws Exception{return http(path,body,"question-integration-internal-token");}
    UUID scope(Asset a) throws Exception {
        var response=http("/scopes",Map.of("project_id",a.project,"asset_ids",List.of(a.id)));assertEquals(201,response.statusCode(),response.body());return UUID.fromString(JSON.readTree(response.body()).path("scope_id").asText());
    }
    JsonNode request(String call,String tool,Object args) {
        var node=JSON.createObjectNode().put("call_id",call).put("tool_name",tool);node.set("arguments",JSON.valueToTree(args));
        var content=JSON.createObjectNode().put("tool",tool);content.set("args",node.path("arguments"));node.put("request_hash",ToolJson.hash(content));return node;
    }
    JsonNode tool(UUID scope,String call,String name,Object args) throws Exception{return tool(scope,request(call,name,args));}
    JsonNode tool(UUID scope,JsonNode request) throws Exception {
        var response=http("/scopes/"+scope+"/tools",request);assertEquals(200,response.statusCode(),response.body());
        assertTrue(response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=32768);return JSON.readTree(response.body());
    }
    @Test void snapshotsFreezeSourcesAndKeepOldAnalysisAfterHeadChanges() throws Exception {
        var a=fixture(null);var first=speech(a,0,1436,4000,"上传素材");UUID old=scope(a);
        visual(a,0,2320,"红色矩形");db.update("UPDATE analysis_stages SET status='SUCCEEDED' WHERE analysis_id=? AND stage_key='VISION'",a.analysis);
        var list=tool(old,"assets","list_assets",Map.of());assertEquals(List.of("SPEECH"),JSON.convertValue(list.path("data").path("assets").get(0).path("source_kinds"),List.class));
        var range=tool(old,"range","read_video_range",Map.of("asset_id",a.id,"start_ms",0,"end_ms",5000));assertEquals(1,range.path("records").size());assertEquals(first.toString(),range.path("records").get(0).path("id").asText());assertEquals(1436,range.path("coverage").path("asset").path("coverage").path("speech_processed_ranges").get(0).path("start_ms").asInt());
        assertEquals("SOURCE_NOT_READY",tool(old,"vision","search_evidence",Map.of("query","红色","source_kinds",List.of("VISUAL"))).path("error").path("code").asText());
        UUID fresh=scope(a);assertEquals(2,tool(fresh,"range","read_video_range",Map.of("asset_id",a.id,"start_ms",0,"end_ms",5000)).path("records").size());
        UUID analysis=UUID.randomUUID();db.update("INSERT INTO asset_analysis_revisions(id,project_id,asset_id,source_sha256,profile_hash) VALUES (?,?,?,?,'new')",analysis,a.project,a.id,"b".repeat(64));db.update("UPDATE asset_analysis_heads SET analysis_id=? WHERE asset_id=?",analysis,a.id);
        assertEquals(range,tool(old,"range","read_video_range",Map.of("asset_id",a.id,"start_ms",0,"end_ms",5000)));
        assertEquals(200,http("/scopes/"+old,null).statusCode());
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/tool-contract"));java.nio.file.Files.writeString(java.nio.file.Path.of("target/tool-contract/result.json"),range.toString());
    }
    @Test void rangeUsesSpeechOverlapAndVisualPointsWithExclusiveEnd() throws Exception {
        var a=fixture(null);speech(a,0,1436,5000,"上传");speech(a,1,5000,7000,"核查");UUID atStart=visual(a,0,5000,"蓝色");visual(a,1,7000,"红色");db.update("UPDATE analysis_stages SET status='SUCCEEDED' WHERE analysis_id=?",a.analysis);
        var result=tool(scope(a),"read","read_video_range",Map.of("asset_id",a.id,"start_ms",5000,"end_ms",7000));
        assertEquals(2,result.path("records").size());var point=java.util.stream.StreamSupport.stream(result.path("records").spliterator(),false).filter(r->r.path("id").asText().equals(atStart.toString())).findFirst().orElseThrow();assertEquals(5000,point.path("observed_at_ms").asInt());assertFalse(point.has("end_ms"));assertFalse(point.has("start_ms"));
        assertFalse(result.path("coverage").path("asset").path("coverage").path("visual_complete").asBoolean());
    }
    @Test void paginationReadsAllUtf8RecordsAndDoesNotTruncateAnOversizedRecord() throws Exception {
        var a=fixture(null);for(int i=0;i<80;i++)speech(a,i,1436+i*10,1440+i*10,"核查原文"+"中".repeat(500));UUID scope=scope(a);
        var ids=new HashSet<String>();int cursor=0,pages=0;
        do {
            var page=tool(scope,"page"+pages,"read_video_range",Map.of("asset_id",a.id,"start_ms",0,"end_ms",20000,"cursor",cursor));assertEquals("READY",page.path("status").asText());
            page.path("records").forEach(r->{assertTrue(ids.add(r.path("id").asText()));assertEquals(504,r.path("text").asText().length());});pages++;
            if(page.path("data").path("next_cursor").isNull())break;cursor=page.path("data").path("next_cursor").asInt();
        } while(pages<100);
        assertEquals(80,ids.size());assertTrue(pages>1);
        var overview=tool(scope,"overview","get_video_overview",Map.of("asset_id",a.id));assertTrue(overview.path("records").isEmpty());assertFalse(overview.path("coverage").path("navigation_complete").asBoolean());assertEquals("NAVIGATION",overview.path("data").path("entries").get(0).path("purpose").asText());
        var navigation=new HashSet<String>();overview.path("data").path("entries").forEach(entry->entry.path("navigation_ids").forEach(id->navigation.add(id.asText())));
        cursor=overview.path("data").path("next_cursor").asInt();pages=1;
        do {var page=tool(scope,"overview"+pages,"get_video_overview",Map.of("asset_id",a.id,"cursor",cursor));assertEquals("READY",page.path("status").asText());page.path("data").path("entries").forEach(entry->entry.path("navigation_ids").forEach(id->assertTrue(navigation.add(id.asText()))));pages++;if(page.path("data").path("next_cursor").isNull()){assertTrue(page.path("coverage").path("navigation_complete").asBoolean());assertFalse(page.path("coverage").path("asset").path("coverage").path("visual_complete").asBoolean());break;}cursor=page.path("data").path("next_cursor").asInt();}while(pages<100);
        assertEquals(ids,navigation);
        var oversized=fixture(null);speech(oversized,0,1436,5000,"中".repeat(12000));var error=tool(scope(oversized),"too-large","read_video_range",Map.of("asset_id",oversized.id,"start_ms",0,"end_ms",20000));assertEquals("RECORD_TOO_LARGE",error.path("error").path("code").asText());assertTrue(error.path("records").isEmpty());
    }
    @Test void assetPagesAccumulateAndUnprobedSnapshotsStayUnprobed() throws Exception {
        var a=fixture(null);db.update("UPDATE assets SET validation_status='VALIDATING',duration_ms=NULL WHERE id=?",a.id);
        UUID unprobed=scope(a);db.update("UPDATE assets SET validation_status='VALID',duration_ms=20000 WHERE id=?",a.id);
        var frozen=tool(unprobed,"list","list_assets",Map.of()).path("data").path("assets").get(0);
        assertEquals("VALIDATING",frozen.path("validation_status").asText());assertTrue(frozen.path("duration_ms").isNull());assertTrue(frozen.path("source_kinds").isEmpty());
        assertEquals("SOURCE_NOT_READY",tool(unprobed,"overview","get_video_overview",Map.of("asset_id",a.id)).path("error").path("code").asText());
        for(int i=0;i<40;i++)fixture(a.project);
        db.update("UPDATE assets SET filename=? WHERE project_id=?","长".repeat(250),a.project);
        UUID all=UUID.fromString(JSON.readTree(http("/scopes",Map.of("project_id",a.project)).body()).path("scope_id").asText());
        var ids=new HashSet<String>();int cursor=0,pages=0;
        do {var page=tool(all,"list"+pages,"list_assets",Map.of("cursor",cursor));assertEquals("READY",page.path("status").asText());page.path("data").path("assets").forEach(asset->assertTrue(ids.add(asset.path("id").asText())));pages++;if(page.path("data").path("next_cursor").isNull())break;cursor=page.path("data").path("next_cursor").asInt();}while(pages<20);
        assertEquals(41,ids.size());assertTrue(pages>1);
    }
    @Test void trustIdentityAndDependencyFailuresAreExplicit() throws Exception {
        var a=fixture(null);speech(a,0,1436,5000,"原文");UUID scope=scope(a);assertEquals(401,http("/scopes",Map.of("project_id",a.project),null).statusCode());
        var foreign=fixture(null);assertEquals(403,http("/scopes",Map.of("project_id",a.project,"asset_ids",List.of(foreign.id))).statusCode());
        var good=request("same","list_assets",Map.of());assertEquals(tool(scope,good),tool(scope,good));
        assertEquals("CALL_ID_CONFLICT",tool(scope,"same","get_video_overview",Map.of("asset_id",a.id)).path("error").path("code").asText());
        var wrong=good.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)wrong).put("request_hash","a".repeat(64));assertEquals("REQUEST_HASH_MISMATCH",tool(scope,wrong).path("error").path("code").asText());
        assertEquals("ASSET_OUTSIDE_SCOPE",tool(scope,"foreign","read_video_range",Map.of("asset_id",foreign.id,"start_ms",0,"end_ms",5000)).path("error").path("code").asText());
        assertEquals("INVALID_ARGUMENTS",tool(scope,"extra","list_assets",Map.of("project_id",a.project)).path("error").path("code").asText());
        db.update("UPDATE assets SET validation_status='INVALID' WHERE id=?",a.id);
        assertEquals("SNAPSHOT_INVALID",tool(scope,good).path("error").path("code").asText());assertEquals(409,http("/scopes/"+scope,null).statusCode());
    }
    @Test void searchPagesReusePaidRankingAndFrozenGeneration() throws Exception {
        var a=fixture(null);for(int i=0;i<60;i++)speech(a,i,1436+i*10,1440+i*10,"核查原文 original "+"中".repeat(700));
        UUID original=generation(a,"qa_"+UUID.randomUUID().toString().replace("-",""));UUID scope=scope(a);int before=EMBEDDINGS.get();
        var args=new LinkedHashMap<String,Object>();args.put("query","original");args.put("source_kinds",List.of("SPEECH"));
        var first=tool(scope,"search0","search_evidence",args);assertEquals("READY",first.path("status").asText(),first.toString());assertEquals(before+1,EMBEDDINGS.get());assertTrue(first.path("data").path("candidates_truncated").asBoolean());assertFalse(first.path("data").path("next_cursor").isNull());
        assertEquals("AVAILABLE",first.path("data").path("channels").path("DENSE").path("status").asText());assertEquals("AVAILABLE",first.path("data").path("channels").path("BM25").path("status").asText());
        // Rebuilding a new head does not change the already saved rank or frozen scope generation.
        generation(a,"qa_"+UUID.randomUUID().toString().replace("-",""));
        var ids=new HashSet<String>();first.path("records").forEach(r->ids.add(r.path("id").asText()));int pages=1,cursor=first.path("data").path("next_cursor").asInt();
        do {args.put("cursor",cursor);var page=tool(scope,"search"+pages,"search_evidence",args);assertEquals("READY",page.path("status").asText());page.path("records").forEach(r->assertTrue(ids.add(r.path("id").asText())));pages++;if(page.path("data").path("next_cursor").isNull())break;cursor=page.path("data").path("next_cursor").asInt();}while(pages<30);
        assertTrue(ids.size()>=50);assertEquals(before+1,EMBEDDINGS.get());assertEquals(1,db.queryForObject("SELECT count(*) FROM question_searches WHERE scope_id=?",Integer.class,scope));
        assertEquals(original.toString(),JSON.readTree(http("/scopes/"+scope,null).body()).path("assets").get(0).path("index_generation").asText());
        assertEquals(first,tool(scope,"search0","search_evidence",Map.of("query","original","source_kinds",List.of("SPEECH"))));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM query_model_calls q JOIN question_searches s ON s.id=q.question_search_id WHERE s.scope_id=? AND q.response_json IS NOT NULL",Integer.class,scope));
    }
    @Test void candidateLimitAppliesAcrossAllIndexGroupsBeforeFusion() throws Exception {
        var first=fixture(null);var second=fixture(first.project);
        for(var asset:List.of(first,second)) {
            for(int i=0;i<30;i++)speech(asset,i,1436+i*10,1440+i*10,"已保存片段 "+i);
            generation(asset,"qa_"+UUID.randomUUID().toString().replace("-",""));
        }
        UUID scope=UUID.fromString(JSON.readTree(http("/scopes",Map.of("project_id",first.project)).body()).path("scope_id").asText());
        for(String channel:List.of("DENSE","BM25")) {
            // Isolate each channel; the BM25 query shares tokens but no literal substring.
            FAIL_DENSE.set(!"DENSE".equals(channel));FAIL_BM25.set(!"BM25".equals(channel));
            int before=EMBEDDINGS.get();
            var arguments=Map.of("query","DENSE".equals(channel)?"不同的语义表达":"已保存 片段");
            String call="group-limit-"+channel;
            var result=tool(scope,call,"search_evidence",arguments);
            assertEquals("READY",result.path("status").asText(),result.toString());
            assertEquals("AVAILABLE",result.path("data").path("channels").path(channel).path("status").asText());
            assertEquals(50,result.path("data").path("channel_limit").asInt());
            assertTrue(result.path("data").path("candidates_truncated").asBoolean(),"Two groups with 30 candidates each must report truncation");
            UUID queryId=UUID.fromString(result.path("data").path("query_id").asText());
            var ranking=JSON.readTree(db.queryForObject("SELECT result_json::text FROM question_searches WHERE id=?",String.class,queryId));
            assertEquals(50,ranking.path("hits").size());
            assertEquals(result,tool(scope,call,"search_evidence",arguments));
            assertEquals(before+1,EMBEDDINGS.get(),"Same embedding profile is paid once across groups and replay");
        }
    }
    @Test void embeddingUncertaintyAndChannelFailuresDegradeIndependentlyWithoutResubmission() throws Exception {
        var a=fixture(null);speech(a,0,1436,5000,"核查 original");generation(a,"qa_"+UUID.randomUUID().toString().replace("-",""));
        UUID scope=scope(a);FAIL_EMBED.set(true);int before=EMBEDDINGS.get();
        var first=tool(scope,"uncertain","search_evidence",Map.of("query","original"));assertEquals("READY",first.path("status").asText(),first.toString());
        assertEquals("UNAVAILABLE",first.path("data").path("channels").path("DENSE").path("status").asText());assertEquals("AVAILABLE",first.path("data").path("channels").path("BM25").path("status").asText());assertEquals("UNCERTAIN",first.path("data").path("model_calls").get(0).path("status").asText());assertFalse(first.path("records").isEmpty());
        FAIL_EMBED.set(false);tool(scope,"same-query-new-call","search_evidence",Map.of("query","original","cursor",0,"asset_ids",List.of(a.id),"source_kinds",List.of("SPEECH")));assertEquals(before+1,EMBEDDINGS.get());
        FAIL_BM25.set(true);var bm25=tool(scope,"bm25-failed","search_evidence",Map.of("query","核查"));assertEquals("AVAILABLE",bm25.path("data").path("channels").path("DENSE").path("status").asText());assertEquals("UNAVAILABLE",bm25.path("data").path("channels").path("BM25").path("status").asText());
        FAIL_DENSE.set(true);var both=tool(scope,"both-failed","search_evidence",Map.of("query","original 核查"));assertEquals("AVAILABLE",both.path("data").path("channels").path("LITERAL").path("status").asText());assertEquals("UNAVAILABLE",both.path("data").path("channels").path("DENSE").path("status").asText());
    }
    @Test void sourceFiltersPrecedeRecallAndUncertainEmbeddingIsNotRepeatedAcrossGroups() throws Exception {
        var speechAsset=fixture(null);speech(speechAsset,0,1436,5000,"original speech");visual(speechAsset,0,2320,"original hidden visual");
        var visionAsset=fixture(speechAsset.project);speech(visionAsset,0,1436,5000,"original hidden speech");visual(visionAsset,0,2320,"original visual");
        db.update("UPDATE analysis_stages SET status=CASE WHEN stage_key='ASR' THEN 'PENDING' ELSE 'SUCCEEDED' END WHERE analysis_id=?",visionAsset.analysis);
        generation(speechAsset,"qa_"+UUID.randomUUID().toString().replace("-",""));generation(visionAsset,"qa_"+UUID.randomUUID().toString().replace("-",""));
        UUID scope=UUID.fromString(JSON.readTree(http("/scopes",Map.of("project_id",speechAsset.project)).body()).path("scope_id").asText());
        FAIL_EMBED.set(true);int before=EMBEDDINGS.get();
        var result=tool(scope,"source-filter","search_evidence",Map.of("query","original","source_kinds",List.of("SPEECH","VISUAL")));
        assertEquals("READY",result.path("status").asText(),result.toString());assertEquals(before+1,EMBEDDINGS.get());assertEquals(2,result.path("records").size());
        result.path("records").forEach(record->assertFalse(record.path("text").asText().contains("hidden")));
        assertEquals("AVAILABLE",result.path("data").path("channels").path("BM25").path("status").asText());
    }
    @Test void unfinishedCallsAndQueriesStopInsteadOfPayingAgain() throws Exception {
        var a=fixture(null);speech(a,0,1436,5000,"原文");UUID scope=scope(a);var req=request("unfinished","list_assets",Map.of());
        db.update("INSERT INTO question_tool_receipts(scope_id,call_id,request_json) VALUES (?,?,?::jsonb)",scope,"unfinished",req.toString());assertEquals("CALL_UNCONFIRMED",tool(scope,req).path("error").path("code").asText());
        var conditions=JSON.valueToTree(Map.of("query","原文","asset_ids",List.of(a.id.toString()),"source_kinds",List.of("SPEECH")));
        db.update("INSERT INTO question_searches(id,scope_id,conditions_hash,conditions_json) VALUES (?,?,?,?::jsonb)",UUID.randomUUID(),scope,ToolJson.hash(conditions),conditions.toString());int before=EMBEDDINGS.get();assertEquals("QUERY_UNCONFIRMED",tool(scope,"query","search_evidence",Map.of("query","原文")).path("error").path("code").asText());assertEquals(before,EMBEDDINGS.get());
        assertEquals("CURSOR_INVALID",tool(scope,"cursor","search_evidence",Map.of("query","不同","cursor",1)).path("error").path("code").asText());
    }
}
