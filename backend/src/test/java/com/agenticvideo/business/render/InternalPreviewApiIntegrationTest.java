package com.agenticvideo.business.render;

import com.agenticvideo.business.tools.ToolJson;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class InternalPreviewApiIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:18.6-bookworm");
    @Container static final org.testcontainers.containers.GenericContainer<?> SILO=new org.testcontainers.containers.GenericContainer<>(
            "pgsty/silo:RELEASE.2026-09-16T00-00-00Z@sha256:635197cb9f36d01bee221d34d1c7d7960f6a95c48b0b6c01d99cd13bdae51a46")
            .withEnv("MINIO_ROOT_USER","preview-integration").withEnv("MINIO_ROOT_PASSWORD","preview-integration-password")
            .withCommand("server","/data").withExposedPorts(9000)
            .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forHttp("/minio/health/ready").forPort(9000));
    static final ObjectMapper JSON=new ObjectMapper();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",POSTGRES::getJdbcUrl);p.add("spring.datasource.username",POSTGRES::getUsername);p.add("spring.datasource.password",POSTGRES::getPassword);
        p.add("internal.worker-token",()->"draft-integration-internal-token");
        p.add("storage.endpoint",()->"http://"+SILO.getHost()+":"+SILO.getMappedPort(9000));
        p.add("storage.public-endpoint",()->"http://"+SILO.getHost()+":"+SILO.getMappedPort(9000));
        p.add("storage.access-key",()->"preview-integration");p.add("storage.secret-key",()->"preview-integration-password");p.add("storage.bucket",()->"internal-preview-tests");
    }
    @Autowired JdbcTemplate db;
    @Autowired com.agenticvideo.business.task.TaskExecution tasks;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean com.agenticvideo.business.upload.MultipartStorage storageBoundary;
    @LocalServerPort int port;
    record Asset(UUID project,UUID id,UUID analysis,UUID model) {}
    record Production(UUID id,UUID scope) {}
    static software.amazon.awssdk.services.s3.S3Client storage() {
        return software.amazon.awssdk.services.s3.S3Client.builder().endpointOverride(URI.create("http://"+SILO.getHost()+":"+SILO.getMappedPort(9000)))
                .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("preview-integration","preview-integration-password")))
                .region(software.amazon.awssdk.regions.Region.US_EAST_1).forcePathStyle(true).build();
    }
    @BeforeAll static void bucket() {
        try(var store=storage()){store.createBucket(b->b.bucket("internal-preview-tests"));}
    }
    Asset fixture(UUID project) {
        if(project==null) {
            UUID user=UUID.randomUUID();project=UUID.randomUUID();
            db.update("INSERT INTO users(id,normalized_username,password_hash) VALUES (?,?,'fixture')",user,user.toString());
            db.update("INSERT INTO projects(id,owner_user_id,name) VALUES (?,?,'M2')",project,user);
        }
        UUID upload=UUID.randomUUID(),asset=UUID.randomUUID(),analysis=UUID.randomUUID(),task=UUID.randomUUID(),model=UUID.randomUUID();String hash="b".repeat(64);
        db.update("INSERT INTO upload_sessions(id,project_id,actor_user_id,idempotency_key,filename,declared_bytes,declared_sha256,part_size_bytes,expected_parts,object_key,writer_token) SELECT ?,id,owner_user_id,?,'test.mp4',1,?,1,1,?,? FROM projects WHERE id=?",upload,upload.toString(),hash,upload.toString(),UUID.randomUUID(),project);
        db.update("INSERT INTO assets(id,project_id,upload_id,object_key,filename,byte_size,sha256,validation_status,duration_ms,media_info_json,time_mapping_json) VALUES (?,?,?,?,'test.mp4',1,?,'VALID',20000,'{\"streams\":[{\"codec_type\":\"audio\",\"duration\":\"18.564\"}]}','{\"hasAudio\":true,\"audioStartMs\":1436,\"formatStartMs\":0}')",asset,project,upload,asset.toString(),hash);
        db.update("INSERT INTO asset_analysis_revisions(id,project_id,asset_id,source_sha256,profile_hash) VALUES (?,?,?,?,'fixture')",analysis,project,asset,hash);
        db.update("INSERT INTO asset_analysis_heads VALUES (?,?)",asset,analysis);
        db.update("INSERT INTO analysis_stages(asset_id,analysis_id,stage_key,status) VALUES (?,?,'ASR','SUCCEEDED'),(?,?,'VISION','SUCCEEDED')",asset,analysis,asset,analysis);
        db.update("INSERT INTO tasks(id,project_id,type,status,input_json) VALUES (?,?,'CONTENT_ANALYZE','SUCCEEDED','{}')",task,project);
        db.update("INSERT INTO model_calls(id,analysis_id,task_id,model,parameters_json,status) VALUES (?,?,?,'fixture','{}','SUCCEEDED')",model,analysis,task);
        try(var store=storage()){store.putObject(b->b.bucket("internal-preview-tests").key(asset.toString()),software.amazon.awssdk.core.sync.RequestBody.fromBytes(new byte[]{1}));}
        return new Asset(project,asset,analysis,model);
    }
    UUID speech(Asset a,int ordinal,long start,long end,String text,Object... words) {
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO evidence_segments(id,project_id,asset_id,analysis_id,source_kind,ordinal,text,start_ms,end_ms,audio_start_ms,audio_end_ms,observation_json,model_call_id) VALUES (?,?,?,?,'SPEECH',?,?,?,?,?,?,?::jsonb,?)",id,a.project,a.id,a.analysis,ordinal,text,start,end,start-1436,end-1436,JSON.valueToTree(Map.of("words",List.of(words))).toString(),a.model);
        return id;
    }
    Object word(long start,long end,String text){return Map.of("begin_time",start-1436,"end_time",end-1436,"text",text,"punctuation","");}
    HttpResponse<String> http(String method,String path,Object body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/internal/v1"+(path.startsWith("/questions/") || path.startsWith("/previews/") || path.endsWith("/previews") ? path : "/questions"+path))).header("Content-Type","application/json").header("Authorization","Bearer draft-integration-internal-token")
            .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    Production production(Asset... assets) throws Exception {
        var response=http("POST","/scopes",Map.of("project_id",assets[0].project,"asset_ids",Arrays.stream(assets).map(Asset::id).toList()));assertEquals(201,response.statusCode(),response.body());
        UUID scope=UUID.fromString(JSON.readTree(response.body()).path("scope_id").asText()),id=UUID.randomUUID();
        response=http("PUT","/productions/"+id,Map.of("project_id",assets[0].project,"scope_id",scope));assertEquals(200,response.statusCode(),response.body());
        return new Production(id,scope);
    }
    JsonNode tool(String path,String call,String name,Object args) throws Exception {
        var request=JSON.createObjectNode().put("call_id",call).put("tool_name",name);request.set("arguments",JSON.valueToTree(args));
        var hash=JSON.createObjectNode().put("tool",name);hash.set("args",request.path("arguments"));request.put("request_hash",ToolJson.hash(hash));
        var response=http("POST",path+"/tools",request);assertEquals(200,response.statusCode(),response.body());assertTrue(response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=32768);return JSON.readTree(response.body());
    }
    JsonNode read(Production p,Asset a,long start,long end,int cursor) throws Exception {
        return tool("/scopes/"+p.scope,"read-"+UUID.randomUUID(),"read_video_range",Map.of("asset_id",a.id,"start_ms",start,"end_ms",end,"cursor",cursor));
    }
    Map<String,Object> clip(Asset a,long start,long end,UUID evidence){return Map.of("asset_id",a.id,"source_in_ms",start,"source_out_ms",end,"evidence_ids",List.of(evidence),"reason","原文依据");}
    ObjectNode input(List<Asset> assets,List<?>... clips) {
        var draft=JSON.createObjectNode().put("title","M2");var outputs=draft.putArray("outputs");var requirements=JSON.createObjectNode().put("topic","流程").put("profile","LANDSCAPE_720").put("subtitles_enabled",true).put("require_complete_subtitles",false);
        requirements.set("asset_ids",JSON.valueToTree(assets.stream().map(Asset::id).toList()));requirements.putArray("forbidden_content");var conditions=requirements.putArray("outputs");
        for(int i=0;i<clips.length;i++){String key="out-"+(i+1);var output=outputs.addObject().put("output_key",key).put("title",key);output.set("clips",JSON.valueToTree(clips[i]));conditions.addObject().put("output_key",key).putNull("duration_min_ms").putNull("duration_max_ms");}
        var input=JSON.createObjectNode();input.set("draft",draft);input.set("requirements",requirements);return hashes(input);
    }
    ObjectNode hashes(ObjectNode input){input.put("draft_hash",ToolJson.hash(input.path("draft")));input.put("requirements_hash",ToolJson.hash(input.path("requirements")));return input;}
    JsonNode validate(Production p,String call,ObjectNode input) throws Exception{return tool("/productions/"+p.id,call,"validate_edit_draft",hashes(input));}

    ObjectNode saveRequest(Asset a,Production p,JsonNode validation,ObjectNode input) {
        var request=JSON.createObjectNode().put("project_id",a.project.toString()).put("requirement_id",p.id.toString()).put("scope_id",p.scope.toString())
            .put("validation_id",validation.path("data").path("validation_id").asText()).put("draft_hash",input.path("draft_hash").asText()).put("requirements_hash",input.path("requirements_hash").asText());
        request.set("requirements_sources",JSON.valueToTree(Map.of("topic",Map.of("kind","USER","message_id","m1","text","剪辑上传核查"),"profile",Map.of("kind","DEFAULT","value","LANDSCAPE_720"))));return request;
    }

    JsonNode saved(Asset a,int outputs) throws Exception {
        var evidence=speech(a,0,2000,6000,"上传核查");var p=production(a);read(p,a,2000,6000,0);
        List<?>[] clips=new List<?>[outputs];Arrays.fill(clips,List.of(clip(a,2000,6000,evidence)));
        var input=input(List.of(a),clips);var validation=validate(p,"validate",input);
        assertTrue(validation.path("data").path("valid").asBoolean(),validation.toString());
        var response=http("POST","/productions/"+p.id+"/save",saveRequest(a,p,validation,input));
        assertEquals(200,response.statusCode(),response.body());
        var result=(ObjectNode)JSON.readTree(response.body());result.put("requirement_id",p.id.toString());return result;
    }
    @Test void savedProductionSelectsItsOnlyOutputAndReturnsAnAsynchronousIdentity() throws Exception {
        var a=fixture(null);var saved=saved(a,1);String path="/productions/"+saved.path("requirement_id").asText()+"/previews";
        var empty=http("GET",path,null);assertEquals(200,empty.statusCode(),empty.body());assertEquals(0,JSON.readTree(empty.body()).path("items").size());
        var response=http("POST",path,Map.of());assertEquals(202,response.statusCode(),response.body());
        var result=JSON.readTree(response.body());assertEquals(saved.path("revision_id"),result.path("revision_id"));
        assertEquals(saved.path("outputs").get(0).path("output_id"),result.path("output_id"));assertEquals("PENDING",result.path("task_status").asText());
        assertEquals(a.project.toString(),result.path("project_id").asText());assertTrue(result.has("artifact"));assertTrue(result.path("artifact").isNull());
        var list=JSON.readTree(http("GET",path,null).body());assertEquals(1,list.path("items").size());assertEquals("out-1",list.path("items").get(0).path("output_key").asText());
        var repeat=http("POST",path,Map.of("output_key","out-1"));assertEquals(200,repeat.statusCode(),repeat.body());
        assertEquals(result.path("request_id"),JSON.readTree(repeat.body()).path("request_id"));
    }
    @Test void selectionAndSavedIdentityAreCheckedAtTheInternalBoundary() throws Exception {
        var a=fixture(null);var saved=saved(a,2);String path="/productions/"+saved.path("requirement_id").asText()+"/previews";
        var response=http("POST",path,Map.of());assertEquals(400,response.statusCode(),response.body());
        assertEquals("OUTPUT_SELECTION_REQUIRED",JSON.readTree(response.body()).path("error").path("code").asText());assertTrue(response.body().contains("out-2"));
        response=http("POST",path,Map.of("output_key","missing"));assertEquals(404,response.statusCode());assertTrue(response.body().contains("OUTPUT_NOT_FOUND"));
        for(Object input:List.of(Map.of("output_id",UUID.randomUUID()),Map.of("output_key",1),Map.of("previewProfile","LOW_360"),Map.of("kind","EXPORT"),Map.of("task_id",UUID.randomUUID())))assertEquals(400,http("POST",path,input).statusCode());
        response=http("POST",path,Map.of("output_key","out-2"));assertEquals(202,response.statusCode(),response.body());
        assertEquals(saved.path("outputs").get(1).path("output_id"),JSON.readTree(response.body()).path("output_id"));
        var unsaved=production(a);String pending="/productions/"+unsaved.id+"/previews";
        for(String method:List.of("POST","GET")){response=http(method,pending,method.equals("POST")?Map.of():null);assertEquals(409,response.statusCode(),response.body());assertTrue(response.body().contains("PRODUCTION_NOT_SAVED"));}
        response=http("POST","/productions/"+UUID.randomUUID()+"/previews",Map.of());assertEquals(404,response.statusCode());assertTrue(response.body().contains("PRODUCTION_NOT_FOUND"));
    }
    @Test void aSavedOutputKeyKeepsTheM2LengthContractWithoutTrimming() throws Exception {
        var a=fixture(null);var evidence=speech(a,0,2000,6000,"上传核查");var p=production(a);read(p,a,2000,6000,0);
        var input=input(List.of(a),List.of(clip(a,2000,6000,evidence)),List.of(clip(a,2000,6000,evidence)));
        ((ObjectNode)input.at("/draft/outputs/1")).put("output_key"," ");
        ((ObjectNode)input.at("/requirements/outputs/1")).put("output_key"," ");
        var validation=validate(p,"space-key",input);assertTrue(validation.path("data").path("valid").asBoolean(),validation.toString());
        var save=http("POST","/productions/"+p.id+"/save",saveRequest(a,p,validation,input));assertEquals(200,save.statusCode(),save.body());
        var saved=JSON.readTree(save.body());String path="/productions/"+p.id+"/previews";
        var response=http("POST",path,Map.of("output_key"," "));assertEquals(202,response.statusCode(),response.body());
        assertEquals(saved.path("outputs").get(1).path("output_id"),JSON.readTree(response.body()).path("output_id"));
        assertEquals(" ",JSON.readTree(http("GET",path,null).body()).path("items").get(0).path("output_key").asText());
    }
    @Test void ordinarySubmissionReportsTheCurrentTerminalResultWithoutStartingAnotherRender() throws Exception {
        var a=fixture(null);var saved=saved(a,1);String path="/productions/"+saved.path("requirement_id").asText()+"/previews";
        var first=JSON.readTree(http("POST",path,Map.of()).body());
        // Simulate the asynchronous executor's terminal failure; observe only through the HTTP seam.
        db.update("UPDATE tasks SET status='FAILED',result_json='{\"code\":\"PREVIEW_FAILED\",\"message\":\"渲染失败\"}' WHERE id=?",UUID.fromString(first.path("task_id").asText()));
        var response=http("POST",path,Map.of());assertEquals(200,response.statusCode(),response.body());
        var current=JSON.readTree(response.body());assertEquals(first.path("request_id"),current.path("request_id"));
        assertEquals("FAILED",current.path("task_status").asText());assertTrue(current.path("retry_allowed").asBoolean());
        assertEquals("PREVIEW_FAILED",current.path("error").path("code").asText());
        assertEquals(1,JSON.readTree(http("GET",path,null).body()).path("items").size());
    }
    @Test void retryHasOneSuccessorAndReplaysItsCurrentResult() throws Exception {
        var a=fixture(null);var saved=saved(a,1);String path="/productions/"+saved.path("requirement_id").asText()+"/previews";
        var first=JSON.readTree(http("POST",path,Map.of()).body());String original="/previews/"+first.path("request_id").asText();
        var blocked=http("POST",original+"/retry",null);assertEquals(409,blocked.statusCode(),blocked.body());assertTrue(blocked.body().contains("PREVIEW_NOT_RETRYABLE"));
        assertEquals(200,http("POST",original+"/cancel",null).statusCode());
        var response=http("POST",original+"/retry",null);assertEquals(202,response.statusCode(),response.body());
        var next=JSON.readTree(response.body());assertNotEquals(first.path("task_id"),next.path("task_id"));
        db.update("UPDATE tasks SET status='FAILED',result_json='{\"code\":\"RENDER_FAILED\",\"message\":\"失败\"}' WHERE id=?",UUID.fromString(next.path("task_id").asText()));
        response=http("POST",original+"/retry",null);assertEquals(200,response.statusCode(),response.body());
        var replay=JSON.readTree(response.body());assertEquals(next.path("request_id"),replay.path("request_id"));assertEquals("FAILED",replay.path("task_status").asText());
        assertEquals(next.path("request_id"),JSON.readTree(http("POST",path,Map.of()).body()).path("request_id"));
        response=http("POST","/previews/"+next.path("request_id").asText()+"/retry",null);assertEquals(202,response.statusCode(),response.body());
        assertEquals(3,JSON.readTree(http("GET",path,null).body()).path("items").size());
    }
    @Test void terminalHistorySurvivesChangedOrInvalidSources() throws Exception {
        var a=fixture(null);var saved=saved(a,1);String path="/productions/"+saved.path("requirement_id").asText()+"/previews";
        var first=JSON.readTree(http("POST",path,Map.of()).body());String original="/previews/"+first.path("request_id").asText();
        assertEquals(200,http("POST",original+"/cancel",null).statusCode());
        db.update("UPDATE assets SET sha256=?,validation_status='INVALID' WHERE id=?","c".repeat(64),a.id);
        var response=http("POST",path,Map.of());assertEquals(200,response.statusCode(),response.body());assertEquals(first.path("request_id"),JSON.readTree(response.body()).path("request_id"));
        response=http("POST",original+"/retry",null);assertEquals(409,response.statusCode(),response.body());assertTrue(response.body().contains("PREVIEW_SOURCE_UNAVAILABLE"));
        assertEquals(1,JSON.readTree(http("GET",path,null).body()).path("items").size());
    }
    @Test void concurrentSubmissionsAndRetriesKeepOneTaskAndOneSuccessor() throws Exception {
        var a=fixture(null);var saved=saved(a,1);String path="/productions/"+saved.path("requirement_id").asText()+"/previews";
        var pool=java.util.concurrent.Executors.newFixedThreadPool(6);
        try {
            var submitted=new ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
            for(int i=0;i<6;i++)submitted.add(pool.submit(()->http("POST",path,Map.of())));
            var requests=new HashSet<String>();int created=0;
            for(var pending:submitted){var response=pending.get();assertTrue(Set.of(200,202).contains(response.statusCode()),response.body());if(response.statusCode()==202)created++;requests.add(JSON.readTree(response.body()).path("request_id").asText());}
            assertEquals(1,created);assertEquals(1,requests.size());String original="/previews/"+requests.iterator().next();
            assertEquals(200,http("POST",original+"/cancel",null).statusCode());
            submitted.clear();for(int i=0;i<6;i++)submitted.add(pool.submit(()->http("POST",original+"/retry",null)));
            requests.clear();created=0;
            for(var pending:submitted){var response=pending.get();assertTrue(Set.of(200,202).contains(response.statusCode()),response.body());if(response.statusCode()==202)created++;requests.add(JSON.readTree(response.body()).path("request_id").asText());}
            assertEquals(1,created);assertEquals(1,requests.size());assertEquals(2,JSON.readTree(http("GET",path,null).body()).path("items").size());
        } finally {pool.shutdownNow();}
    }
    @Test void reuseSearchesPastNewerFailuresAndRetryCanLinkToExistingWork() throws Exception {
        var a=fixture(null);var saved=saved(a,1);String path="/productions/"+saved.path("requirement_id").asText()+"/previews";
        var first=JSON.readTree(http("POST",path,Map.of()).body());UUID request=UUID.fromString(first.path("request_id").asText());
        // Existing public clients can have more than one historical request for a target.
        UUID failed=UUID.randomUUID(),failedTask=UUID.randomUUID();
        var transaction=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(db.getDataSource()));
        transaction.executeWithoutResult(tx->{
            db.update("INSERT INTO tasks(id,project_id,type,status,input_json,result_json) SELECT ?,project_id,'PREVIEW_RENDER','FAILED',input_json,'{\"code\":\"RENDER_FAILED\",\"message\":\"失败\"}'::jsonb FROM tasks WHERE id=?",failedTask,UUID.fromString(first.path("task_id").asText()));
            db.update("INSERT INTO render_requests(id,project_id,revision_id,output_id,kind,config_json,renderer_version,cache_key,task_id) SELECT ?,project_id,revision_id,output_id,kind,config_json,renderer_version,cache_key,? FROM render_requests WHERE id=?",failed,failedTask,request);
        });
        var response=http("POST",path,Map.of());assertEquals(200,response.statusCode(),response.body());assertEquals(first.path("request_id"),JSON.readTree(response.body()).path("request_id"));
        response=http("POST","/previews/"+failed+"/retry",null);assertEquals(200,response.statusCode(),response.body());assertEquals(first.path("request_id"),JSON.readTree(response.body()).path("request_id"));
        publish(first);
        assertEquals(first.path("request_id"),JSON.readTree(http("POST",path,Map.of()).body()).path("request_id"));
        // Fixed successor replay is independent of later source and executor state changes.
        db.update("UPDATE assets SET sha256=?,validation_status='INVALID' WHERE id=?","c".repeat(64),a.id);
        response=http("POST","/previews/"+failed+"/retry",null);assertEquals(200,response.statusCode(),response.body());assertEquals(first.path("request_id"),JSON.readTree(response.body()).path("request_id"));assertEquals("SUCCEEDED",JSON.readTree(response.body()).path("task_status").asText());
        response=http("POST",path,Map.of());assertEquals(200,response.statusCode(),response.body());assertEquals(failed.toString(),JSON.readTree(response.body()).path("request_id").asText());
    }
    @Test void newRenderingRequiresSourceFilesButExistingTasksDoNot() throws Exception {
        var a=fixture(null);var saved=saved(a,1);String path="/productions/"+saved.path("requirement_id").asText()+"/previews";
        try(var store=storage()){store.deleteObject(b->b.bucket("internal-preview-tests").key(a.id.toString()));}
        var response=http("POST",path,Map.of());assertEquals(409,response.statusCode(),response.body());assertTrue(response.body().contains("PREVIEW_SOURCE_UNAVAILABLE"));
        assertEquals(0,JSON.readTree(http("GET",path,null).body()).path("items").size());
        try(var store=storage()){store.putObject(b->b.bucket("internal-preview-tests").key(a.id.toString()),software.amazon.awssdk.core.sync.RequestBody.fromBytes(new byte[]{1}));}
        var first=JSON.readTree(http("POST",path,Map.of()).body());
        db.update("UPDATE assets SET validation_status='INVALID' WHERE id=?",a.id);
        try(var store=storage()){store.deleteObject(b->b.bucket("internal-preview-tests").key(a.id.toString()));}
        response=http("POST",path,Map.of());assertEquals(200,response.statusCode(),response.body());assertEquals(first.path("task_id"),JSON.readTree(response.body()).path("task_id"));
    }
    JsonNode publish(JsonNode request) throws Exception {
        var run=tasks.claimLocal(UUID.fromString(request.path("task_id").asText()));
        String key=run.input().path("artifactObjectKey").asText();byte[] bytes="preview-fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try(var store=storage()){store.putObject(b->b.bucket("internal-preview-tests").key(key),software.amazon.awssdk.core.sync.RequestBody.fromBytes(bytes));}
        tasks.completeLocal(run,"PREVIEW_RENDERED",Map.of("artifactId",run.input().path("artifactId").asText(),"objectKey",key,"byteSize",bytes.length,"sha256","d".repeat(64),
                "durationMs",4000,"mediaInfo",Map.of("streams",List.of(Map.of("codec_type","video","codec_name","h264","width",640,"height",360),Map.of("codec_type","audio","codec_name","aac","channels",2,"sample_rate","48000")))));
        return run.input();
    }
    @Test void detailsCheckPublishedArtifactsAndRefreshPlayableAccessWithoutSources() throws Exception {
        var a=fixture(null);var saved=saved(a,1);String path="/productions/"+saved.path("requirement_id").asText()+"/previews";
        var first=JSON.readTree(http("POST",path,Map.of()).body());String original="/previews/"+first.path("request_id").asText();
        var published=publish(first);
        try(var store=storage()){store.deleteObject(b->b.bucket("internal-preview-tests").key(a.id.toString()));}
        db.update("UPDATE assets SET validation_status='INVALID' WHERE id=?",a.id);
        for(int i=0;i<2;i++) {
            var response=http("GET",original,null);assertEquals(200,response.statusCode(),response.body());var result=JSON.readTree(response.body());
            assertEquals("SUCCEEDED",result.path("task_status").asText());assertEquals("AVAILABLE",result.at("/artifact/state").asText());assertFalse(result.path("retry_allowed").asBoolean());
            assertEquals(300,result.at("/playback/expires_in_seconds").asInt());assertEquals(4000,result.at("/artifact/duration_ms").asInt());
            var video=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(result.at("/playback/url").asText())).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,video.statusCode());assertEquals("preview-fixture",video.body());
        }
        var response=http("POST",path,Map.of());assertEquals(200,response.statusCode(),response.body());assertEquals(first.path("request_id"),JSON.readTree(response.body()).path("request_id"));
        assertEquals(409,http("POST",original+"/retry",null).statusCode());
        response=http("POST",original+"/cancel",null);assertEquals(200,response.statusCode());assertEquals("AVAILABLE",JSON.readTree(response.body()).at("/artifact/state").asText());
        try(var store=storage()){store.deleteObject(b->b.bucket("internal-preview-tests").key(published.path("artifactObjectKey").asText()));}
        response=http("GET",original,null);assertEquals(200,response.statusCode(),response.body());var missing=JSON.readTree(response.body());
        assertEquals("SUCCEEDED",missing.path("task_status").asText());assertEquals("UNAVAILABLE",missing.at("/artifact/state").asText());assertTrue(missing.path("playback").isNull());
        assertTrue(missing.path("retry_allowed").asBoolean());assertEquals("PREVIEW_ARTIFACT_UNAVAILABLE",missing.at("/error/code").asText());
        assertEquals(first.path("request_id"),JSON.readTree(http("POST",path,Map.of()).body()).path("request_id"));
        db.update("UPDATE artifacts SET state='REMOVED' WHERE id=?",UUID.fromString(published.path("artifactId").asText()));
        assertEquals("REMOVED",JSON.readTree(http("GET",original,null).body()).at("/artifact/state").asText());
    }
    @Test void storageErrorsDoNotInvalidateSuccessfulArtifactsAndSizeMismatchAllowsExplicitRetry() throws Exception {
        var a=fixture(null);var saved=saved(a,1);String path="/productions/"+saved.path("requirement_id").asText()+"/previews";
        var first=JSON.readTree(http("POST",path,Map.of()).body());String original="/previews/"+first.path("request_id").asText();var published=publish(first);
        String key=published.path("artifactObjectKey").asText();
        for(RuntimeException failure:List.of(software.amazon.awssdk.core.exception.SdkClientException.create("timeout"),software.amazon.awssdk.services.s3.model.S3Exception.builder().statusCode(403).message("denied").build(),software.amazon.awssdk.services.s3.model.S3Exception.builder().statusCode(500).message("unavailable").build())) {
            org.mockito.Mockito.doThrow(failure).when(storageBoundary).objectSize(key);
            for(String operation:List.of("detail","submit","retry")) {
                var response=operation.equals("detail")?http("GET",original,null):operation.equals("submit")?http("POST",path,Map.of()):http("POST",original+"/retry",null);
                assertEquals(503,response.statusCode(),response.body());assertTrue(response.body().contains("PREVIEW_STORAGE_UNAVAILABLE"));
            }
            org.mockito.Mockito.reset(storageBoundary);
            var result=JSON.readTree(http("GET",original,null).body());assertEquals("AVAILABLE",result.at("/artifact/state").asText());assertEquals("SUCCEEDED",result.path("task_status").asText());
        }
        try(var store=storage()){store.putObject(b->b.bucket("internal-preview-tests").key(key),software.amazon.awssdk.core.sync.RequestBody.fromBytes(new byte[]{1}));}
        var response=http("GET",original,null);assertEquals(200,response.statusCode());assertEquals("UNAVAILABLE",JSON.readTree(response.body()).at("/artifact/state").asText());
        response=http("POST",original+"/retry",null);assertEquals(202,response.statusCode(),response.body());assertNotEquals(first.path("task_id"),JSON.readTree(response.body()).path("task_id"));
    }
    @Test void cancellationWaitsForTheExecutorAndNeverRevealsStagedArtifacts() throws Exception {
        var a=fixture(null);var saved=saved(a,1);String path="/productions/"+saved.path("requirement_id").asText()+"/previews";
        var first=JSON.readTree(http("POST",path,Map.of()).body());String original="/previews/"+first.path("request_id").asText();
        var run=tasks.claimLocal(UUID.fromString(first.path("task_id").asText()));
        var response=http("GET",original,null);assertEquals("RUNNING",JSON.readTree(response.body()).path("task_status").asText());assertTrue(JSON.readTree(response.body()).path("artifact").isNull());
        for(int i=0;i<2;i++) {
            response=http("POST",original+"/cancel",null);assertEquals(200,response.statusCode());assertEquals("CANCEL_REQUESTED",JSON.readTree(response.body()).path("task_status").asText());
            assertFalse(JSON.readTree(response.body()).path("retry_allowed").asBoolean());assertEquals(409,http("POST",original+"/retry",null).statusCode());
            assertEquals(first.path("task_id"),JSON.readTree(http("POST",path,Map.of()).body()).path("task_id"));
        }
        tasks.cancelLocal(run);
        response=http("POST",original+"/cancel",null);assertEquals("CANCELLED",JSON.readTree(response.body()).path("task_status").asText());
        assertEquals(200,http("GET","/productions/"+saved.path("requirement_id").asText()+"/save",null).statusCode());
    }
    @Test void malformedTransportReturnsThePreviewErrorContract() throws Exception {
        for(String suffix:List.of("","/cancel","/retry")) {
            var missing=http(suffix.isEmpty()?"GET":"POST","/previews/"+UUID.randomUUID()+suffix,null);
            assertEquals(404,missing.statusCode(),missing.body());assertTrue(missing.body().contains("PREVIEW_NOT_FOUND"));
            var malformed=http(suffix.isEmpty()?"GET":"POST","/previews/not-a-uuid"+suffix,null);
            assertEquals(400,malformed.statusCode(),malformed.body());assertTrue(malformed.body().contains("INVALID_REQUEST"));
        }
        var response=http("POST","/productions/not-a-uuid/previews",Map.of());
        assertEquals(400,response.statusCode(),response.body());assertTrue(response.body().contains("INVALID_REQUEST"));
        for(String payload:List.of("{", "null", "[]", "{\"output_key\":null}","{\"output_key\":\"\"}")) {
            var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/internal/v1/productions/"+UUID.randomUUID()+"/previews"))
                    .header("Authorization","Bearer draft-integration-internal-token").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(payload));
            response=HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(400,response.statusCode(),response.body());assertTrue(response.body().contains("INVALID_REQUEST"));
        }
    }
    @Test void exportRequestsAreNotExposedThroughTheProductionPreviewList() throws Exception {
        var a=fixture(null);var saved=saved(a,1);UUID revision=UUID.fromString(saved.path("revision_id").asText());
        UUID confirmation=UUID.randomUUID(),request=UUID.randomUUID(),task=UUID.randomUUID();
        db.update("INSERT INTO plan_confirmations(id,project_id,revision_id,actor_user_id,source_json) SELECT ?,id,?,owner_user_id,'{}' FROM projects WHERE id=?",confirmation,revision,a.project);
        db.update("INSERT INTO tasks(id,project_id,type,input_json) VALUES (?,?,'EXPORT_RENDER','{}')",task,a.project);
        db.update("INSERT INTO render_requests(id,project_id,revision_id,output_id,kind,config_json,renderer_version,cache_key,task_id,confirmation_id,actor_user_id,intent_source_json) SELECT ?,id,?,?,'EXPORT','{}','fixture','export-fixture',?,?,owner_user_id,'{}' FROM projects WHERE id=?",
                request,revision,UUID.fromString(saved.path("outputs").get(0).path("output_id").asText()),task,confirmation,a.project);
        for(String suffix:List.of("","/cancel","/retry")) {
            var denied=http(suffix.isEmpty()?"GET":"POST","/previews/"+request+suffix,null);
            assertEquals(404,denied.statusCode(),denied.body());assertTrue(denied.body().contains("PREVIEW_NOT_FOUND"));
        }
        var response=http("GET","/productions/"+saved.path("requirement_id").asText()+"/previews",null);
        assertEquals(200,response.statusCode(),response.body());assertEquals(0,JSON.readTree(response.body()).path("items").size());
    }
    @Test void internalCredentialsAreRequiredAndRetiredWorkerProtocolStaysRetired() throws Exception {
        String path="http://localhost:"+port+"/internal/v1/productions/"+UUID.randomUUID()+"/previews";
        for(String credential:List.of("","Bearer wrong")) {
            var request=HttpRequest.newBuilder(URI.create(path)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}"));
            if(!credential.isEmpty())request.header("Authorization",credential);
            assertEquals(401,HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        }
        // Use the actual retired URL, without browser authentication or the legacy feature flag.
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/internal/v1/tasks/"+UUID.randomUUID()+"/claim")).POST(HttpRequest.BodyPublishers.ofString("{}"));
        assertEquals(410,HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString()).statusCode());
    }
}
