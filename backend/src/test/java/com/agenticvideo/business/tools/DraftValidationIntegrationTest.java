package com.agenticvideo.business.tools;

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
class DraftValidationIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:18.6-bookworm");
    static final ObjectMapper JSON=new ObjectMapper();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",POSTGRES::getJdbcUrl);p.add("spring.datasource.username",POSTGRES::getUsername);p.add("spring.datasource.password",POSTGRES::getPassword);
        p.add("internal.worker-token",()->"draft-integration-internal-token");
    }
    @Autowired JdbcTemplate db;
    @LocalServerPort int port;
    record Asset(UUID project,UUID id,UUID analysis,UUID model) {}
    record Production(UUID id,UUID scope) {}
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
        return new Asset(project,asset,analysis,model);
    }
    UUID speech(Asset a,int ordinal,long start,long end,String text,Object... words) {
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO evidence_segments(id,project_id,asset_id,analysis_id,source_kind,ordinal,text,start_ms,end_ms,audio_start_ms,audio_end_ms,observation_json,model_call_id) VALUES (?,?,?,?,'SPEECH',?,?,?,?,?,?,?::jsonb,?)",id,a.project,a.id,a.analysis,ordinal,text,start,end,start-1436,end-1436,JSON.valueToTree(Map.of("words",List.of(words))).toString(),a.model);
        return id;
    }
    Object word(long start,long end,String text){return Map.of("begin_time",start-1436,"end_time",end-1436,"text",text,"punctuation","");}
    HttpResponse<String> http(String method,String path,Object body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/internal/v1/questions"+path)).header("Content-Type","application/json").header("Authorization","Bearer draft-integration-internal-token")
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
    JsonNode content(JsonNode result) throws Exception{return JSON.readTree(db.queryForObject("SELECT normalized_content_json::text FROM edit_draft_validations WHERE id=?",String.class,UUID.fromString(result.path("data").path("validation_id").asText())));}

    ObjectNode saveRequest(Asset a,Production p,JsonNode validation,ObjectNode input) {
        var request=JSON.createObjectNode().put("project_id",a.project.toString()).put("requirement_id",p.id.toString()).put("scope_id",p.scope.toString())
            .put("validation_id",validation.path("data").path("validation_id").asText()).put("draft_hash",input.path("draft_hash").asText()).put("requirements_hash",input.path("requirements_hash").asText());
        request.set("requirements_sources",JSON.valueToTree(Map.of("topic",Map.of("kind","USER","message_id","m1","text","剪辑上传核查"),"profile",Map.of("kind","DEFAULT","value","LANDSCAPE_720"))));return request;
    }
    @Test void pythonAgentRepairsRealValidationThenExplicitCliSaves() throws Exception {
        var a=fixture(null);var b=fixture(a.project);
        var first=speech(a,0,2000,6000,"上传核查",word(2000,4000,"上传"),word(4000,6000,"核查"));
        var second=speech(b,0,3000,5000,"完成");
        var root=java.nio.file.Path.of("").toAbsolutePath();
        if(root.getFileName().toString().equals("backend"))root=root.getParent();
        var log=java.nio.file.Files.createTempFile("m2-python-", ".log");
        try {
            var process=new ProcessBuilder(root.resolve("agent-service/.venv/bin/python").toString(),
                root.resolve("agent-service/tests/java_production_scenario.py").toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try(var input=process.getOutputStream()) {
                JSON.writeValue(input,Map.of("url","http://localhost:"+port,"token","draft-integration-internal-token",
                    "project",a.project,"assets",List.of(Map.of("id",a.id,"evidence",first),Map.of("id",b.id,"evidence",second))));
            }
            assertTrue(process.waitFor(90,java.util.concurrent.TimeUnit.SECONDS),"Python scenario timed out");
            String output=java.nio.file.Files.readString(log);assertEquals(0,process.exitValue(),output);
            var evidence=JSON.readTree(output);
            assertEquals("SAVED",evidence.path("saved").path("status").asText());
            assertEquals(2,evidence.path("saved").path("saved_result").path("outputs").size());
            var target=root.resolve("backend/target/m2-stage4-controlled.json");
            java.nio.file.Files.writeString(target,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
        } finally {java.nio.file.Files.deleteIfExists(log);}
    }

    @Test void explicitSavePublishesAllOutputsAndRecoversTheSameVersion() throws Exception {
        var a=fixture(null);var b=fixture(a.project);var first=speech(a,0,2000,6000,"上传");var second=speech(b,0,3000,5000,"核查");
        var p=production(a,b);read(p,a,2000,6000,0);read(p,b,3000,5000,0);
        var input=input(List.of(a,b),List.of(clip(a,2000,6000,first),clip(b,3000,5000,second)),List.of(clip(a,2000,6000,first)));
        var checked=validate(p,"save-valid",input);assertTrue(checked.path("data").path("valid").asBoolean());
        assertEquals(404,http("GET","/productions/"+p.id+"/save",null).statusCode());
        var request=saveRequest(a,p,checked,input);var response=http("POST","/productions/"+p.id+"/save",request);assertEquals(200,response.statusCode(),response.body());
        var saved=JSON.readTree(response.body());assertEquals("SAVED",saved.path("status").asText());assertEquals(1,saved.path("revision_number").asInt());assertEquals(2,saved.path("outputs").size());
        assertEquals(6000,saved.path("outputs").get(0).path("duration_ms").asLong());assertEquals(4000,saved.path("outputs").get(1).path("duration_ms").asLong());
        assertEquals(saved,JSON.readTree(http("POST","/productions/"+p.id+"/save",request).body()));
        assertEquals(saved,JSON.readTree(http("GET","/productions/"+p.id+"/save",null).body()));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM plan_revisions WHERE source_production_id=?",Integer.class,p.id));
        assertEquals(2,db.queryForObject("SELECT count(*) FROM revision_asset_refs WHERE revision_id=?",Integer.class,UUID.fromString(saved.path("revision_id").asText())));
        assertEquals(2,db.queryForObject("SELECT count(*) FROM revision_evidence_refs WHERE revision_id=?",Integer.class,UUID.fromString(saved.path("revision_id").asText())));
        assertEquals("PRODUCTION_SAVED",validate(p,"after-save",input).path("error").path("code").asText());
    }

    HttpResponse<String> api(HttpClient client,String method,String path,Object body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1"+path)).header("Content-Type","application/json");
        if(!method.equals("GET")) {
            var csrf=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/auth/csrf")).GET().build(),HttpResponse.BodyHandlers.ofString());
            request.header("X-XSRF-TOKEN",JSON.readTree(csrf.body()).path("data").path("csrfToken").asText()).header("Idempotency-Key",UUID.randomUUID().toString());
        }
        return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpClient owner(Asset a) throws Exception {
        String user=db.queryForObject("SELECT owner_user_id::text FROM projects WHERE id=?",String.class,a.project);
        db.update("UPDATE users SET password_hash=? WHERE id=?",new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("fixture-password"),UUID.fromString(user));
        var client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        var response=api(client,"POST","/auth/login",Map.of("username",user,"password","fixture-password"));assertEquals(200,response.statusCode(),response.body());return client;
    }
    @Test void savedRevisionExposesItsSourceAndFeedsTheExistingPreviewAssembler() throws Exception {
        var a=fixture(null);var id=speech(a,0,2000,6000,"上传核查");var p=production(a);read(p,a,2000,6000,0);
        var input=input(List.of(a),List.of(clip(a,2000,6000,id)),List.of(clip(a,2000,6000,id)));((ObjectNode)input.path("requirements")).put("profile","PORTRAIT_720").put("subtitles_enabled",false);
        var checked=validate(p,"consume",input);var response=http("POST","/productions/"+p.id+"/save",saveRequest(a,p,checked,input));assertEquals(200,response.statusCode(),response.body());
        var saved=JSON.readTree(response.body());var client=owner(a);var retrieved=api(client,"GET","/revisions/"+saved.path("revision_id").asText(),null);assertEquals(200,retrieved.statusCode(),retrieved.body());
        var version=JSON.readTree(retrieved.body()).path("data");var source=version.path("productionSource");assertEquals(p.id.toString(),source.path("requirement_id").asText());assertEquals(p.scope.toString(),source.path("scope_id").asText());
        assertEquals(JSON.readTree(input.path("draft").toString()),source.path("draft"));assertEquals(JSON.readTree(input.path("requirements").toString()),source.path("requirements"));assertEquals(checked.path("data").path("validation_id"),source.path("validation_id"));
        assertEquals(saved.path("outputs"),source.path("outputs"));
        assertEquals("剪辑上传核查",source.path("requirements_sources").path("topic").path("text").asText());
        var ids=new HashSet<String>();
        for(int i=0;i<2;i++) {
            var output=version.path("content").path("outputs").get(i);assertEquals(saved.path("outputs").get(i).path("output_id").asText(),output.path("outputId").asText());
            assertTrue(ids.add(output.path("outputId").asText()));assertTrue(ids.add(output.path("clips").get(0).path("clipId").asText()));assertFalse(output.has("output_key"));
            assertFalse(output.path("subtitles").path("enabled").asBoolean());assertTrue(output.path("subtitles").path("cues").isEmpty());assertEquals(720,output.path("render").path("width").asInt());
            var preview=api(client,"POST","/revisions/"+saved.path("revision_id").asText()+"/previews",Map.of("outputId",output.path("outputId").asText(),"previewProfile","LOW_360"));assertEquals(202,preview.statusCode(),preview.body());
            var task=UUID.fromString(JSON.readTree(preview.body()).path("data").path("taskId").asText());
            var renderInput=JSON.readTree(db.queryForObject("SELECT input_json::text FROM tasks WHERE id=?",String.class,task));
            assertEquals(output,renderInput.path("output"));assertEquals(360,renderInput.path("configuration").path("width").asInt());assertEquals(640,renderInput.path("configuration").path("height").asInt());assertEquals(a.id.toString(),renderInput.path("sources").get(0).path("assetId").asText());
        }
        assertEquals(404,api(owner(fixture(null)),"GET","/revisions/"+saved.path("revision_id").asText(),null).statusCode());
    }

    @Test void saveRejectsMismatchedIdentityHashesAndUnapprovedOrSupersededDrafts() throws Exception {
        var a=fixture(null);var id=speech(a,0,2000,6000,"原句");var p=production(a);read(p,a,2000,6000,0);
        var input=input(List.of(a),List.of(clip(a,2000,6000,id)));var checked=validate(p,"initial",input);var request=saveRequest(a,p,checked,input);
        for(String field:List.of("project_id","scope_id","requirement_id","validation_id")) {
            var changed=request.deepCopy().put(field,UUID.randomUUID().toString());var rejected=http("POST","/productions/"+p.id+"/save",changed);assertTrue(Set.of(400,409).contains(rejected.statusCode()),rejected.body());
        }
        for(String field:List.of("draft_hash","requirements_hash"))assertEquals(409,http("POST","/productions/"+p.id+"/save",request.deepCopy().put(field,"0".repeat(64))).statusCode());
        var other=production(a);assertEquals(409,http("POST","/productions/"+other.id+"/save",request.deepCopy().put("requirement_id",other.id.toString()).put("scope_id",other.scope.toString())).statusCode());
        ((ObjectNode)input.path("requirements").path("outputs").get(0)).put("duration_min_ms",9000);
        var invalid=validate(p,"bad-duration",input);assertFalse(invalid.path("data").path("valid").asBoolean());
        var rejected=http("POST","/productions/"+p.id+"/save",saveRequest(a,p,invalid,input));assertEquals(409,rejected.statusCode(),rejected.body());assertEquals("DRAFT_NOT_VALID",JSON.readTree(rejected.body()).path("error").path("code").asText());
        rejected=http("POST","/productions/"+p.id+"/save",request);assertEquals(409,rejected.statusCode());assertEquals("VALIDATION_STALE",JSON.readTree(rejected.body()).path("error").path("code").asText());
        assertEquals(404,http("GET","/productions/"+p.id+"/save",null).statusCode());
        assertEquals(0,db.queryForObject("SELECT count(*) FROM edit_plans WHERE project_id=?",Integer.class,a.project));
    }
    @Test void invalidDependenciesStopAnUnsavedDraftButDoNotEraseAnAlreadyCommittedResult() throws Exception {
        var a=fixture(null);var id=speech(a,0,2000,6000,"原句");var p=production(a);read(p,a,2000,6000,0);
        var input=input(List.of(a),List.of(clip(a,2000,6000,id)));var checked=validate(p,"dependency",input);var request=saveRequest(a,p,checked,input);
        db.update("UPDATE assets SET sha256=? WHERE id=?","c".repeat(64),a.id);
        var rejected=http("POST","/productions/"+p.id+"/save",request);assertEquals(409,rejected.statusCode());assertEquals("DEPENDENCY_INVALID",JSON.readTree(rejected.body()).path("error").path("category").asText());
        assertEquals(404,http("GET","/productions/"+p.id+"/save",null).statusCode());
        db.update("UPDATE assets SET sha256=? WHERE id=?","b".repeat(64),a.id);
        var saved=http("POST","/productions/"+p.id+"/save",request);assertEquals(200,saved.statusCode(),saved.body());
        db.update("UPDATE assets SET validation_status='INVALID' WHERE id=?",a.id);
        assertEquals(JSON.readTree(saved.body()),JSON.readTree(http("GET","/productions/"+p.id+"/save",null).body()));
        assertEquals(JSON.readTree(saved.body()),JSON.readTree(http("POST","/productions/"+p.id+"/save",request).body()));
    }
    @Test void failureAfterRevisionAndReferenceWritesRollsBackTheWholeSaveAndCanBeRetried() throws Exception {
        var a=fixture(null);var first=speech(a,0,2000,4000,"上传");var second=speech(a,1,4000,6000,"核查");var p=production(a);read(p,a,2000,6000,0);
        var input=input(List.of(a),List.of(clip(a,2000,4000,first)),List.of(clip(a,4000,6000,second)));var checked=validate(p,"atomic-save",input);var request=saveRequest(a,p,checked,input);
        db.execute("CREATE FUNCTION reject_test_save() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled save failure'; END $$");
        db.execute("CREATE TRIGGER reject_test_save BEFORE INSERT ON revision_evidence_refs FOR EACH ROW EXECUTE FUNCTION reject_test_save()");
        try {
            assertEquals(500,http("POST","/productions/"+p.id+"/save",request).statusCode());
            assertEquals(404,http("GET","/productions/"+p.id+"/save",null).statusCode());
            assertEquals(0,db.queryForObject("SELECT count(*) FROM edit_plans WHERE project_id=?",Integer.class,a.project));
            assertEquals(0,db.queryForObject("SELECT count(*) FROM plan_revisions WHERE project_id=?",Integer.class,a.project));
            assertEquals(0,db.queryForObject("SELECT count(*) FROM revision_asset_refs WHERE asset_id=?",Integer.class,a.id));
        } finally {db.execute("DROP TRIGGER reject_test_save ON revision_evidence_refs");db.execute("DROP FUNCTION reject_test_save()");}
        var response=http("POST","/productions/"+p.id+"/save",request);assertEquals(200,response.statusCode(),response.body());assertEquals(2,JSON.readTree(response.body()).path("outputs").size());
    }
    @Test void concurrentSavesReturnOneVersionAndCannotChangeItsContentOrProvenance() throws Exception {
        var a=fixture(null);var id=speech(a,0,2000,6000,"原句");var p=production(a);read(p,a,2000,6000,0);var input=input(List.of(a),List.of(clip(a,2000,6000,id)));
        var checked=validate(p,"concurrent",input);var request=saveRequest(a,p,checked,input);
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first=executor.submit(()->http("POST","/productions/"+p.id+"/save",request));var second=executor.submit(()->http("POST","/productions/"+p.id+"/save",request));
            var one=first.get();var two=second.get();assertEquals(200,one.statusCode(),one.body());assertEquals(200,two.statusCode(),two.body());assertEquals(JSON.readTree(one.body()),JSON.readTree(two.body()));
        } finally {executor.shutdownNow();}
        var changed=request.deepCopy();((ObjectNode)changed.path("requirements_sources").path("topic")).put("text","篡改来源");assertEquals(409,http("POST","/productions/"+p.id+"/save",changed).statusCode());
        assertEquals(1,db.queryForObject("SELECT count(*) FROM edit_plans WHERE project_id=?",Integer.class,a.project));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM plan_revisions WHERE source_production_id=?",Integer.class,p.id));
    }

    @Test void legalMultipleOutputsPersistNormalizedCaptionsAndReplayWithoutPublishing() throws Exception {
        var a=fixture(null);var b=fixture(a.project);var first=speech(a,0,2000,6000,"上传核查",word(2000,4000,"上传"),word(4000,6000,"核查"));var second=speech(b,0,3000,5000,"导出");
        var p=production(a,b);read(p,a,2000,6000,0);read(p,b,3000,5000,0);
        var input=input(List.of(a,b),List.of(clip(a,4000,6000,first),clip(b,3000,5000,second)),List.of(clip(a,2000,6000,first)));
        ((ObjectNode)input.path("requirements").path("outputs").get(0)).put("duration_min_ms",4000).put("duration_max_ms",4000);
        ((ObjectNode)input.path("requirements").path("outputs").get(1)).put("duration_min_ms",4000).put("duration_max_ms",4000);
        var result=validate(p,"valid",input);assertEquals("READY",result.path("status").asText(),result.toString());assertTrue(result.path("data").path("valid").asBoolean(),result.toString());assertEquals(JSON.readTree("[4000,4000]"),result.path("data").path("durations_ms"));
        var normalized=content(result);var output=normalized.path("outputs").get(0);assertEquals("核查",output.path("subtitles").path("cues").get(0).path("text").asText());assertEquals(0,output.path("subtitles").path("cues").get(0).path("startMs").asInt());assertEquals(2000,output.path("subtitles").path("cues").get(1).path("startMs").asInt());assertEquals("hardcut-m2-v1",output.path("render").path("configVersion").asText());assertFalse(output.has("outputId"));assertFalse(output.path("clips").get(0).has("clipId"));
        assertEquals(result,validate(p,"valid",input));assertEquals(1,db.queryForObject("SELECT count(*) FROM edit_draft_validations WHERE production_id=?",Integer.class,p.id));assertEquals(0,db.queryForObject("SELECT count(*) FROM plan_revisions WHERE source_production_id=?",Integer.class,p.id));
    }
    Set<String> codes(JsonNode result) {var codes=new HashSet<String>();result.path("data").path("issues").forEach(i->codes.add(i.path("code").asText()));return codes;}
    UUID visual(Asset a,int ordinal,long time) {
        UUID frame=UUID.randomUUID(),call=UUID.randomUUID(),id=UUID.randomUUID();String hash=UUID.randomUUID().toString().replace("-","").repeat(2);
        db.update("INSERT INTO vision_frames(id,analysis_id,ordinal,observed_at_ms,source_pts,object_key,sha256,size_bytes) VALUES (?,?,?,?,?,?,?,1)",frame,a.analysis,ordinal,time,Long.toString(time),frame.toString(),hash);
        db.update("INSERT INTO vision_model_calls(id,analysis_id,task_id,frame_sha256,model,parameters_json,status) SELECT ?,analysis_id,task_id,?,'fixture','{}','SUCCEEDED' FROM model_calls WHERE id=?",call,hash,a.model);
        db.update("INSERT INTO evidence_segments(id,project_id,asset_id,analysis_id,source_kind,ordinal,text,start_ms,end_ms,observation_json,frame_id,vision_call_id) VALUES (?,?,?,?,'VISUAL',?,'画面观察',?,?,'{}',?,?)",id,a.project,a.id,a.analysis,ordinal,time,time,frame,call);return id;
    }
    @Test void missingContextAndSkippedPagesCannotPassUntilTheWholeChainWasRead() throws Exception {
        var a=fixture(null);UUID first=null;
        for(int i=0;i<103;i++){var id=speech(a,i,2000+i*10,2005+i*10,"字");if(i==0)first=id;}
        UUID unit=UUID.randomUUID();
        db.update("INSERT INTO retrieval_units(id,project_id,asset_id,analysis_id,source_kind,text,start_ms,end_ms,rules_hash) SELECT ?,project_id,asset_id,analysis_id,source_kind,text,start_ms,end_ms,'fixture' FROM evidence_segments WHERE id=?",unit,first);
        db.update("INSERT INTO unit_evidence_refs VALUES (?,?,0)",unit,first);
        var p=production(a);var input=input(List.of(a),List.of(clip(a,2000,4000,first)));
        var search=tool("/scopes/"+p.scope,"search-only","search_evidence",Map.of("query","字"));assertEquals(first.toString(),search.path("records").get(0).path("id").asText());
        tool("/scopes/"+p.scope,"navigation","get_video_overview",Map.of("asset_id",a.id));
        var unread=validate(p,"unread",input);assertTrue(codes(unread).containsAll(Set.of("CONTEXT_NOT_READ","EVIDENCE_NOT_READ")),unread.toString());
        var page=read(p,a,2000,4000,0);int next=page.path("data").path("next_cursor").asInt();assertTrue(next>0);
        read(p,a,2000,4000,next+1);
        assertTrue(codes(validate(p,"skipped",input)).contains("CONTEXT_NOT_READ"));
        read(p,a,2000,4000,next);
        var complete=validate(p,"complete",input);assertTrue(complete.path("data").path("valid").asBoolean(),complete.toString());
    }
    @Test void boundariesUseAllIntersectingSpeechAndSubtitlesOffCannotBypassForbiddenWords() throws Exception {
        var a=fixture(null);var cited=speech(a,0,2000,4000,"允许");speech(a,1,4000,8000,"禁止内容",word(4000,6000,"禁止"),word(6000,8000,"内容"));
        var p=production(a);read(p,a,2000,8000,0);var input=input(List.of(a),List.of(clip(a,2000,7000,cited),clip(a,2000,4000,cited)));
        ((ObjectNode)input.path("requirements")).put("subtitles_enabled",false);((ObjectNode)input.path("requirements").path("outputs").get(0)).put("duration_max_ms",1000);
        var bad=validate(p,"bad",input);assertEquals(Set.of("CUT_INSIDE_WORD","OVERLAPPING_CLIPS","OUTPUT_DURATION"),codes(bad),bad.toString());
        assertTrue(java.util.stream.StreamSupport.stream(bad.path("data").path("issues").spliterator(),false).anyMatch(i->i.path("field").asText().equals("outputs[0].clips[0].source_out_ms") && i.path("expected").path("nearby_safe_boundaries_ms").toString().equals("[6000,8000]")));
        input=input(List.of(a),List.of(clip(a,2000,8000,cited)));((ObjectNode)input.path("requirements")).put("subtitles_enabled",false);((com.fasterxml.jackson.databind.node.ArrayNode)input.path("requirements").path("forbidden_content")).add("禁止");
        assertEquals(Set.of("FORBIDDEN_CONTENT"),codes(validate(p,"forbidden",input)));
        ((com.fasterxml.jackson.databind.node.ArrayNode)input.path("requirements").path("forbidden_content")).removeAll();var off=validate(p,"off",input);assertTrue(off.path("data").path("valid").asBoolean(),off.toString());assertTrue(content(off).path("outputs").get(0).path("subtitles").path("cues").isEmpty());
    }
    @Test void sentenceWithoutWordTimingCannotBeCutAndInvalidRangeSkipsDerivedErrors() throws Exception {
        var a=fixture(null);var id=speech(a,0,2000,6000,"整句");var p=production(a);read(p,a,2000,6000,0);
        var input=input(List.of(a),List.of(clip(a,3000,6000,id)));assertEquals(Set.of("MISSING_WORD_TIMING"),codes(validate(p,"sentence",input)));
        input=input(List.of(a),List.of(clip(a,2000,21000,id)));((ObjectNode)input.path("requirements").path("outputs").get(0)).put("duration_min_ms",25000);
        var invalid=validate(p,"range",input);assertEquals(Set.of("CLIP_RANGE"),codes(invalid));assertFalse(invalid.path("data").path("valid").asBoolean());
    }
    @Test void frozenAnalysisSurvivesNewHeadButNewVersionReferencesAreRejected() throws Exception {
        var a=fixture(null);var old=speech(a,0,2000,6000,"旧证据");var p=production(a);read(p,a,2000,6000,0);
        UUID analysis=UUID.randomUUID();db.update("INSERT INTO asset_analysis_revisions(id,project_id,asset_id,source_sha256,profile_hash) VALUES (?,?,?,?,'new')",analysis,a.project,a.id,"b".repeat(64));db.update("UPDATE asset_analysis_heads SET analysis_id=? WHERE asset_id=?",analysis,a.id);
        UUID model=UUID.randomUUID();db.update("INSERT INTO model_calls(id,analysis_id,task_id,model,parameters_json,status) SELECT ?,?,task_id,'fixture','{}','SUCCEEDED' FROM model_calls WHERE id=?",model,analysis,a.model);
        var fresh=speech(new Asset(a.project,a.id,analysis,model),0,2000,6000,"新证据");
        var good=validate(p,"old",input(List.of(a),List.of(clip(a,2000,6000,old))));assertTrue(good.path("data").path("valid").asBoolean(),good.toString());assertEquals("旧证据",content(good).path("outputs").get(0).path("subtitles").path("cues").get(0).path("text").asText());
        assertEquals(Set.of("EVIDENCE_OUTSIDE_SCOPE"),codes(validate(p,"new",input(List.of(a),List.of(clip(a,2000,6000,fresh))))));
        db.update("UPDATE assets SET validation_status='INVALID' WHERE id=?",a.id);assertEquals("DEPENDENCY_INVALID",validate(p,"old",input(List.of(a),List.of(clip(a,2000,6000,old)))).path("error").path("category").asText());
    }
    @Test void pointEvidenceDoesNotExpandAndNoAudioDoesNotRequireAsrSubtitles() throws Exception {
        var a=fixture(null);db.update("UPDATE assets SET time_mapping_json='{\"hasAudio\":false}'::jsonb WHERE id=?",a.id);db.update("UPDATE analysis_stages SET status='NOT_APPLICABLE' WHERE analysis_id=? AND stage_key='ASR'",a.analysis);
        var point=visual(a,0,5000);var p=production(a);read(p,a,4000,6000,0);var input=input(List.of(a),List.of(clip(a,4000,6000,point)));((ObjectNode)input.path("requirements")).put("require_complete_subtitles",true).put("profile","PORTRAIT_720");
        var good=validate(p,"visual",input);assertTrue(good.path("data").path("valid").asBoolean(),good.toString());var output=content(good).path("outputs").get(0);assertEquals(720,output.path("render").path("width").asInt());assertEquals(1280,output.path("render").path("height").asInt());assertTrue(output.path("subtitles").path("cues").isEmpty());assertTrue(good.path("data").path("warnings").isEmpty());
        input=input(List.of(a),List.of(clip(a,4000,5000,point)));assertEquals(Set.of("EVIDENCE_OUTSIDE_CLIP"),codes(validate(p,"exclusive-end",input)));
    }
    @Test void missingAsrCoverageWarnsAndCannotSatisfyCompleteSubtitles() throws Exception {
        var a=fixture(null);var point=visual(a,0,5000);db.update("UPDATE analysis_stages SET status='PENDING' WHERE analysis_id=? AND stage_key='ASR'",a.analysis);
        var p=production(a);read(p,a,4000,6000,0);var input=input(List.of(a),List.of(clip(a,4000,6000,point)));
        var gap=validate(p,"gap",input);assertTrue(gap.path("data").path("valid").asBoolean(),gap.toString());assertEquals("SUBTITLE_GAP",gap.path("data").path("warnings").get(0).path("code").asText());
        ((ObjectNode)input.path("requirements")).put("require_complete_subtitles",true);assertEquals(Set.of("INCOMPLETE_SUBTITLES"),codes(validate(p,"complete",input)));
    }
    @Test void identityConflictsHashesAndUnconfirmedCallsStopOutsideTheRepairLoop() throws Exception {
        var a=fixture(null);var id=speech(a,0,2000,6000,"原文");var p=production(a);read(p,a,2000,6000,0);var input=input(List.of(a),List.of(clip(a,2000,6000,id)));
        var good=validate(p,"same",input);assertTrue(good.path("data").path("valid").asBoolean());
        ((ObjectNode)input.path("draft")).put("title","changed");assertEquals("CALL_ID_CONFLICT",validate(p,"same",input).path("error").path("code").asText());
        var second=UUID.randomUUID();assertEquals(200,http("PUT","/productions/"+second,Map.of("project_id",a.project,"scope_id",p.scope)).statusCode());assertEquals("CALL_ID_CONFLICT",validate(new Production(second,p.scope),"same",input).path("error").path("code").asText());
        hashes(input);input.put("draft_hash","a".repeat(64));assertEquals("CONTENT_HASH_MISMATCH",tool("/productions/"+p.id,"hash","validate_edit_draft",input).path("error").path("code").asText());
        assertEquals("PRODUCTION_REQUIRED",tool("/scopes/"+p.scope,"unbound","validate_edit_draft",hashes(input)).path("error").path("code").asText());
    }
    @Test void feedbackAggregatesAndExplicitlyTruncatesIssuesWithinTheEnvelopeLimit() throws Exception {
        var a=fixture(null);var id=speech(a,0,2000,6000,"禁词");var p=production(a);read(p,a,2000,6000,0);
        var clips=new ArrayList<Object>();for(int i=0;i<90;i++)clips.add(clip(a,2000,6000,id));var input=input(List.of(a),clips);((com.fasterxml.jackson.databind.node.ArrayNode)input.path("requirements").path("forbidden_content")).add("禁词").add("禁").add("词");
        var result=validate(p,"many",input);assertEquals("READY",result.path("status").asText(),result.toString());assertFalse(result.path("data").path("valid").asBoolean());assertTrue(result.path("data").path("issues_truncated").asBoolean());assertTrue(result.path("data").path("issues").size()>1);assertTrue(codes(result).containsAll(Set.of("FORBIDDEN_CONTENT","OVERLAPPING_CLIPS")));assertEquals(result,validate(p,"many",input));
    }

    @Test void audioPrerollUsesOriginalOffsetEvenWhenTheSentenceWasClampedAtZero() throws Exception {
        var a=fixture(null);db.update("UPDATE assets SET time_mapping_json='{\"hasAudio\":true,\"audioStartMs\":-1000,\"formatStartMs\":0}',media_info_json='{\"streams\":[{\"codec_type\":\"audio\",\"duration\":\"21\"}]}' WHERE id=?",a.id);
        var id=speech(a,0,0,1500,"前核查尾");
        db.update("UPDATE evidence_segments SET audio_start_ms=500,audio_end_ms=2500,observation_json=?::jsonb WHERE id=?",JSON.valueToTree(Map.of("words",List.of(Map.of("begin_time",500,"end_time",1000,"text","前"),Map.of("begin_time",1000,"end_time",2000,"text","核查"),Map.of("begin_time",2000,"end_time",2500,"text","尾")))).toString(),id);
        var p=production(a);read(p,a,0,1500,0);var result=validate(p,"preroll",input(List.of(a),List.of(clip(a,0,1000,id))));
        assertTrue(result.path("data").path("valid").asBoolean(),result.toString());var cue=content(result).path("outputs").get(0).path("subtitles").path("cues").get(0);assertEquals("核查",cue.path("text").asText());assertEquals(0,cue.path("startMs").asInt());assertEquals(1000,cue.path("endMs").asInt());
    }

    @Test void suggestedWordBoundariesAreSafeForOtherIntersectingSpeechToo() throws Exception {
        var a=fixture(null);var id=speech(a,0,2000,6000,"甲乙",word(2000,4000,"甲"),word(4000,6000,"乙"));speech(a,1,3000,5000,"没有词时间的重叠讲话");
        var p=production(a);read(p,a,2000,6000,0);var result=validate(p,"safe",input(List.of(a),List.of(clip(a,2000,4500,id))));
        var cut=java.util.stream.StreamSupport.stream(result.path("data").path("issues").spliterator(),false).filter(i->"CUT_INSIDE_WORD".equals(i.path("code").asText())).findFirst().orElseThrow();
        assertEquals("[6000]",cut.path("expected").path("nearby_safe_boundaries_ms").toString(),result.toString());
    }

    @Test void completeSubtitlesDoNotRequireAsrForSilentTimeOutsideTheAudioTrack() throws Exception {
        var a=fixture(null);var id=speech(a,0,2000,6000,"完整字幕");var p=production(a);read(p,a,0,6000,0);
        var input=input(List.of(a),List.of(clip(a,0,6000,id)));((ObjectNode)input.path("requirements")).put("require_complete_subtitles",true);
        var result=validate(p,"leading-silence",input);assertTrue(result.path("data").path("valid").asBoolean(),result.toString());assertTrue(result.path("data").path("warnings").isEmpty(),result.toString());
    }

    @Test void validationAndReceiptCommitTogetherAndDatabaseErrorsAreNotRepairable() throws Exception {
        var a=fixture(null);var id=speech(a,0,2000,6000,"原文");var p=production(a);read(p,a,2000,6000,0);var input=input(List.of(a),List.of(clip(a,2000,6000,id)));
        db.execute("CREATE FUNCTION reject_test_validation() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled validation persistence failure'; END $$");
        db.execute("CREATE TRIGGER reject_test_validation BEFORE INSERT ON edit_draft_validations FOR EACH ROW EXECUTE FUNCTION reject_test_validation()");
        try {
            var request=JSON.createObjectNode().put("call_id","db-fault").put("tool_name","validate_edit_draft");request.set("arguments",input);var hash=JSON.createObjectNode().put("tool","validate_edit_draft");hash.set("args",input);request.put("request_hash",ToolJson.hash(hash));
            assertEquals(500,http("POST","/productions/"+p.id+"/tools",request).statusCode());
            assertEquals(0,db.queryForObject("SELECT count(*) FROM edit_draft_validations WHERE production_id=?",Integer.class,p.id));
            assertNull(db.queryForObject("SELECT result_json FROM question_tool_receipts WHERE scope_id=? AND call_id='db-fault'",String.class,p.scope));
            assertEquals("CALL_UNCONFIRMED",validate(p,"db-fault",input).path("error").path("code").asText());
        } finally {db.execute("DROP TRIGGER reject_test_validation ON edit_draft_validations");db.execute("DROP FUNCTION reject_test_validation()");}
    }

    @Test void forbiddenLiteralCannotBeHiddenByTheAsrSentenceSplit() throws Exception {
        var a=fixture(null);var id=speech(a,0,2000,4000,"禁止");speech(a,1,4000,6000,"发布");var p=production(a);read(p,a,2000,6000,0);
        var input=input(List.of(a),List.of(clip(a,2000,6000,id)));((ObjectNode)input.path("requirements")).put("subtitles_enabled",false);((com.fasterxml.jackson.databind.node.ArrayNode)input.path("requirements").path("forbidden_content")).add("禁止发布");
        assertEquals(Set.of("FORBIDDEN_CONTENT"),codes(validate(p,"split-literal",input)));
    }

    @Test void aScopeWithoutFixedAudioMetadataCannotSilentlyTreatAudioAsAbsent() throws Exception {
        var a=fixture(null);var p=production(a);
        db.update("UPDATE question_scopes SET assets_json=jsonb_set(assets_json,'{0}',(assets_json->0)-'has_audio'-'audio_offset_ms') WHERE id=?",p.scope);
        var response=http("PUT","/productions/"+UUID.randomUUID(),Map.of("project_id",a.project,"scope_id",p.scope));
        assertEquals(409,response.statusCode(),response.body());assertEquals("DEPENDENCY_INVALID",JSON.readTree(response.body()).path("error").path("category").asText());
    }

    @Test void warningTruncationMarkerFitsInsideALegalMultiOutputResponse() throws Exception {
        var a=fixture(null);var clips=new ArrayList<Object>();
        for(int i=0;i<20;i++){long time=2000+i*10;var id=visual(a,i,time);clips.add(clip(a,time,time+1,id));}
        var p=production(a);read(p,a,2000,2200,0);List<?>[] outputs=new List<?>[10];Arrays.fill(outputs,clips);var input=input(List.of(a),outputs);
        // Vary the legal call identity length so the marker itself must fit even near the byte boundary.
        for(int length:new int[]{1,50,100,150,200}) {
            var result=validate(p,"w".repeat(length),input);assertTrue(result.path("data").path("valid").asBoolean(),result.toString());
            assertTrue(java.util.stream.StreamSupport.stream(result.path("data").path("warnings").spliterator(),false).anyMatch(w->"WARNINGS_TRUNCATED".equals(w.path("code").asText()) && w.path("omitted_count").asInt()>0));
        }
    }
    @Test void completeSubtitlesMeanProcessedAudioAndAllRecognizedSpeechRatherThanPerfectAsr() throws Exception {
        var a=fixture(null);var point=visual(a,0,5000);var p=production(a);read(p,a,4000,6000,0);
        var input=input(List.of(a),List.of(clip(a,4000,6000,point)));((ObjectNode)input.path("requirements")).put("require_complete_subtitles",true);
        var result=validate(p,"recognized-speech",input);assertTrue(result.path("data").path("valid").asBoolean(),result.toString());assertEquals("SUBTITLE_GAP",result.path("data").path("warnings").get(0).path("code").asText());assertTrue(content(result).path("outputs").get(0).path("subtitles").path("cues").isEmpty());
    }

    @Test void forbiddenLiteralSpansClipsInOneOutputButNotSeparateOutputsOrInvalidGaps() throws Exception {
        var a=fixture(null);var id=speech(a,0,2000,6000,"禁止发布",word(2000,4000,"禁止"),word(4000,6000,"发布"));var p=production(a);read(p,a,2000,6000,0);
        var first=clip(a,2000,4000,id);var last=clip(a,4000,6000,id);var input=input(List.of(a),List.of(first,last));
        ((ObjectNode)input.path("requirements")).put("subtitles_enabled",false);((com.fasterxml.jackson.databind.node.ArrayNode)input.path("requirements").path("forbidden_content")).add("禁止发布");
        assertEquals(Set.of("FORBIDDEN_CONTENT"),codes(validate(p,"cross-clips",input)));
        input=input(List.of(a),List.of(first),List.of(last));((com.fasterxml.jackson.databind.node.ArrayNode)input.path("requirements").path("forbidden_content")).add("禁止发布");
        var separate=validate(p,"separate-outputs",input);assertTrue(separate.path("data").path("valid").asBoolean(),separate.toString());
        input=input(List.of(a),List.of(first,clip(a,2000,21000,id),last));((com.fasterxml.jackson.databind.node.ArrayNode)input.path("requirements").path("forbidden_content")).add("禁止发布");
        assertEquals(Set.of("CLIP_RANGE"),codes(validate(p,"invalid-gap",input)));
    }

}
