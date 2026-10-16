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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LocalMediaIntegrationTest {
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
        properties.add("tasks.consume-enabled", () -> true);
        properties.add("media.work-directory", () -> System.getProperty("java.io.tmpdir")+"/agentic-video-test-"+java.util.UUID.randomUUID());
        properties.add("tasks.pending-renotify-seconds", () -> 2);
    }
    @org.springframework.beans.factory.annotation.Autowired org.springframework.amqp.rabbit.core.RabbitTemplate broker;
    @LocalServerPort int port;

    @org.springframework.beans.factory.annotation.Autowired org.springframework.jdbc.core.JdbcTemplate db;
    @org.springframework.beans.factory.annotation.Autowired TaskExecution tasks;
    @org.springframework.beans.factory.annotation.Autowired LocalTaskRunner runtime;

    @Test
    void unacceptedAndSavedSilentDraftsRenderThroughMqWithTheirOwnSources() throws Exception {
        var browser=signedIn("m4-silent-owner");var json=new ObjectMapper();
        var project=java.util.UUID.fromString(json(browser.post("/projects","{\"name\":\"M4 静音保存方案\"}")).path("data").path("id").asText());
        byte[] bytes=java.nio.file.Files.readAllBytes(java.nio.file.Path.of("../frontend/e2e/fixtures/labelled-vision-vfr.mp4"));
        var hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        var upload=json(browser.post("/projects/"+project+"/uploads",json.writeValueAsString(java.util.Map.of("filename","silent.mp4","sizeBytes",bytes.length,"sha256",hash,"idempotencyKey","m4-silent","writerId",browser.writerId)))).path("data");
        String writer=upload.path("writerToken").asText(),uploadId=upload.path("uploadId").asText();
        var urls=json(browser.post("/uploads/"+uploadId+"/part-urls",json.writeValueAsString(java.util.Map.of("partNumbers",java.util.List.of(1),"writerToken",writer)))).path("data");
        assertEquals(200,HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(urls.path("parts").get(0).path("url").asText())).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        var completed=json(browser.post("/uploads/"+uploadId+"/complete",json.writeValueAsString(java.util.Map.of("writerToken",writer)))).path("data");
        awaitState(java.util.UUID.fromString(completed.path("validationTaskId").asText()),"SUCCEEDED");
        var asset=java.util.UUID.fromString(completed.path("assetId").asText());
        var analysis=db.queryForObject("SELECT analysis_id FROM asset_analysis_heads WHERE asset_id=?",java.util.UUID.class,asset);
        // Controlled existing-analysis fixture; the plan is validated and saved through M2 HTTP.
        var frame=db.queryForMap("SELECT id,sha256,observed_at_ms FROM vision_frames WHERE analysis_id=? ORDER BY ordinal LIMIT 1",analysis);
        var call=java.util.UUID.randomUUID();var evidence=java.util.UUID.randomUUID();
        db.update("INSERT INTO vision_model_calls(id,analysis_id,task_id,frame_sha256,model,parameters_json,status) VALUES (?,?,?,?,'fixture','{}','SUCCEEDED')",call,analysis,java.util.UUID.fromString(completed.path("validationTaskId").asText()),frame.get("sha256"));
        db.update("INSERT INTO evidence_segments(id,project_id,asset_id,analysis_id,source_kind,ordinal,text,start_ms,end_ms,observation_json,frame_id,vision_call_id) VALUES (?,?,?,?,'VISUAL',0,'红色矩形',?,?,'{}',?,?)",evidence,project,asset,analysis,frame.get("observed_at_ms"),frame.get("observed_at_ms"),frame.get("id"),call);
        db.update("UPDATE analysis_stages SET status='SUCCEEDED' WHERE analysis_id=? AND stage_key='VISION'",analysis);
        // New browser lifecycle: actual validated draft -> MQ -> FFmpeg -> chat playback, before any save.
        var conversationReply=browser.post("/projects/"+project+"/conversations",json.writeValueAsString(java.util.Map.of("title","草稿媒体验收","assetIds",java.util.List.of(asset))));
        assertEquals(201,conversationReply.statusCode(),conversationReply.body());
        String conversation=json(conversationReply).path("data").path("id").asText();
        var sent=browser.post("/conversations/"+conversation+"/turns","{\"content\":\"制作两条静音竖屏短片\"}");assertEquals(202,sent.statusCode(),sent.body());
        String agent=json(sent).path("data").path("taskId").asText(),agentPath="/agent-runs/"+agent;
        var claimed=internal("POST",agentPath+"/claim",java.util.Map.of("executorId","draft-media-fixture","claimRequestId",java.util.UUID.randomUUID())).path("data");
        var credentials=java.util.Map.of("attemptId",claimed.path("attemptId").asText(),"leaseToken",claimed.path("leaseToken").asText());
        var bindingBody=new java.util.LinkedHashMap<String,Object>(credentials);
        var parsed=json.createObjectNode().put("mode","DRAFT").put("relation","NEW_PRODUCTION").putNull("questionId");
        parsed.set("conditionsPatch",json.valueToTree(java.util.Map.of("topic","红色矩形","profile","PORTRAIT_720","subtitles_enabled",false,"output_count",2)));
        bindingBody.put("parsed",parsed);
        var binding=internal("POST",agentPath+"/parsed",bindingBody).path("data");
        var draftOutputs=new java.util.ArrayList<Object>();
        for(String outputKey:java.util.List.of("out-1","out-2"))draftOutputs.add(java.util.Map.of("output_key",outputKey,"title",outputKey,"clips",java.util.List.of(java.util.Map.of("asset_id",asset,"source_in_ms",0,"source_out_ms",2000,"evidence_ids",java.util.List.of(evidence),"reason","已读画面"))));
        var edit=json.valueToTree(java.util.Map.of("title","未接受双输出","outputs",draftOutputs));
        String editHash=com.agenticvideo.business.tools.ToolJson.hash(edit),conditionsHash=com.agenticvideo.business.tools.ToolJson.hash(binding.path("production").path("requirements"));
        JsonNode checked=null;
        for(String tool:java.util.List.of("read_video_range","validate_edit_draft")) {
            var arguments=tool.equals("read_video_range")?json.valueToTree(java.util.Map.of("asset_id",asset,"start_ms",0,"end_ms",2000)):json.valueToTree(java.util.Map.of("draft",edit,"requirements",binding.path("production").path("requirements"),"draft_hash",editHash,"requirements_hash",conditionsHash));
            var toolRequest=java.util.Map.of("call_id",tool,"tool_name",tool,"arguments",arguments,"request_hash",com.agenticvideo.business.tools.ToolJson.hash(json.valueToTree(java.util.Map.of("tool",tool,"args",arguments))));
            var toolBody=new java.util.LinkedHashMap<String,Object>(credentials);toolBody.put("editGeneration",binding.path("production").path("editGeneration").asLong());toolBody.put("request",toolRequest);
            checked=internal("POST",agentPath+"/tools",toolBody).path("data");
        }
        assertTrue(checked.path("data").path("valid").asBoolean(),checked.toString());
        var draftBinding=json.valueToTree(java.util.Map.of("messageId",java.util.UUID.randomUUID(),"validationId",checked.path("data").path("validation_id").asText(),"editGeneration",binding.path("production").path("editGeneration").asLong(),"draftHash",editHash,"requirementsHash",conditionsHash));
        var result=json.createObjectNode().put("schemaVersion","1.0").put("status","DRAFT_READY").put("mode","DRAFT").put("content","未接受双输出草稿");
        result.set("identity",binding.path("identity"));result.set("productionId",binding.path("production").path("productionId"));result.set("editGeneration",binding.path("production").path("editGeneration"));result.set("draft",draftBinding);result.putArray("scopeReceipts");result.putNull("question");result.putNull("failure");
        var published=new java.util.LinkedHashMap<String,Object>(credentials);published.put("result",result);internal("POST",agentPath+"/result",published);
        var previewBody=json.writeValueAsString(java.util.Map.of("draft",draftBinding,"outputKey","out-2","previewProfile","LOW_360"));
        var admitted=browser.post("/conversations/"+conversation+"/drafts/previews",previewBody);assertEquals(202,admitted.statusCode(),admitted.body());
        var draftPreview=json(admitted).path("data");var draftTask=java.util.UUID.fromString(draftPreview.path("taskId").asText());
        awaitState(draftTask,"SUCCEEDED");verifyRendered(browser,draftTask,360,640);
        assertEquals(0,db.queryForObject("SELECT count(*) FROM plan_revisions WHERE project_id=?",Integer.class,project));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM plan_confirmations WHERE project_id=?",Integer.class,project));
        assertEquals(draftPreview.path("requestId"),json(browser.post("/conversations/"+conversation+"/drafts/previews",previewBody)).path("data").path("requestId"));
        broker.convertAndSend("media.v1",json.writeValueAsBytes(java.util.Map.of("taskId",draftTask.toString())));
        Thread.sleep(1000);
        var snapshot=json(browser.get("/conversations/"+conversation+"/snapshot")).path("data");int playbackCount=0;
        for(var item:snapshot.path("items"))for(var ref:item.path("references"))if("PREVIEW_RESULT".equals(ref.path("type").asText())) {
            playbackCount++;assertEquals(draftBinding.path("messageId"),ref.path("sourceDraftMessageId"));assertEquals("out-2",ref.path("outputKey").asText());
            assertEquals(200,browser.post("/artifacts/"+ref.path("id").asText()+"/playback-access","{}").statusCode());
        }
        assertEquals(1,playbackCount);assertEquals(1,tasks.attemptCount(draftTask));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM execution_reports WHERE task_id=? AND report_kind='COMPLETE'",Integer.class,draftTask));

        var scope=internal("POST","/questions/scopes",java.util.Map.of("project_id",project,"asset_ids",java.util.List.of(asset))).path("scope_id").asText();
        var production=java.util.UUID.randomUUID();String path="/questions/productions/"+production;
        internal("PUT",path,java.util.Map.of("project_id",project,"scope_id",scope));
        internalTool("/questions/scopes/"+scope,"read_video_range",java.util.Map.of("asset_id",asset,"start_ms",0,"end_ms",2000,"cursor",0));
        var draft=java.util.Map.of("title","静音竖屏","outputs",java.util.List.of(java.util.Map.of("output_key","out-1","title","静音竖屏","clips",java.util.List.of(java.util.Map.of("asset_id",asset,"source_in_ms",0,"source_out_ms",2000,"evidence_ids",java.util.List.of(evidence),"reason","已读观察画面")))));
        var conditions=json.createObjectNode().put("output_key","out-1").putNull("duration_min_ms").putNull("duration_max_ms");
        var requirements=java.util.Map.of("topic","红色矩形","asset_ids",java.util.List.of(asset),"profile","PORTRAIT_720","subtitles_enabled",false,"require_complete_subtitles",false,"forbidden_content",java.util.List.of(),"outputs",java.util.List.of(conditions));
        String draftHash=com.agenticvideo.business.tools.ToolJson.hash(json.valueToTree(draft)),requirementsHash=com.agenticvideo.business.tools.ToolJson.hash(json.valueToTree(requirements));
        var validation=internalTool(path,"validate_edit_draft",java.util.Map.of("draft",draft,"requirements",requirements,"draft_hash",draftHash,"requirements_hash",requirementsHash));
        assertTrue(validation.path("data").path("valid").asBoolean(),validation.toString());
        var saved=internal("POST",path+"/save",java.util.Map.of("project_id",project,"requirement_id",production,"scope_id",scope,"validation_id",validation.path("data").path("validation_id").asText(),"draft_hash",draftHash,"requirements_hash",requirementsHash,"requirements_sources",java.util.Map.of("topic",java.util.Map.of("kind","USER","message_id","m4-silent","text","红色矩形"))));
        var preview=internal("POST","/productions/"+production+"/previews",java.util.Map.of());
        assertEquals(saved.path("revision_id"),preview.path("revision_id"));
        var task=java.util.UUID.fromString(preview.path("task_id").asText());awaitState(task,"SUCCEEDED");
        var detail=internal("GET","/previews/"+preview.path("request_id").asText(),null);
        assertEquals("AVAILABLE",detail.path("artifact").path("state").asText());assertEquals(300,detail.path("playback").path("expires_in_seconds").asInt());
        verifyRendered(browser,task,360,640);
        var audio=new ProcessBuilder("ffmpeg","-v","info","-i","target/m1-media/360x640.mp4","-af","volumedetect","-f","null","-").redirectErrorStream(true).start();
        String audioReport=new String(audio.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);assertEquals(0,audio.waitFor());
        assertTrue(audioReport.contains("max_volume: -91.0 dB"),audioReport);
        assertEquals(saved,internal("GET",path+"/save",null));
    }
    private JsonNode internal(String method,String path,Object body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/internal/v1"+path)).header("Authorization","Bearer internal-integration-worker-token").header("Content-Type","application/json")
            .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(new ObjectMapper().writeValueAsString(body)));
        var response=HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode()==200 || response.statusCode()==201 || response.statusCode()==202,response.body());return json(response);
    }
    private JsonNode internalTool(String path,String name,Object arguments) throws Exception {
        var json=new ObjectMapper();var args=json.valueToTree(arguments);
        return internal("POST",path+"/tools",java.util.Map.of("call_id",java.util.UUID.randomUUID().toString(),"tool_name",name,"arguments",args,"request_hash",com.agenticvideo.business.tools.ToolJson.hash(json.valueToTree(java.util.Map.of("tool",name,"args",args)))));
    }
    @Test
    void uploadExecutesInConsumerAndDuplicateDeliveryReusesDurableResult() throws Exception {
        BrowserApi browser=signedIn("local-media-owner");
        String project=json(browser.post("/projects","{\"name\":\"本机媒体\"}")).path("data").path("id").asText();
        byte[] bytes=java.nio.file.Files.readAllBytes(java.nio.file.Path.of("../frontend/e2e/fixtures/labelled-speech.mp4"));
        String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        var upload=json(browser.post("/projects/"+project+"/uploads",new ObjectMapper().writeValueAsString(java.util.Map.of("filename","speech.mp4","sizeBytes",bytes.length,"sha256",hash,"idempotencyKey","local-upload","writerId",browser.writerId)))).path("data");
        String id=upload.path("uploadId").asText(),writer=upload.path("writerToken").asText();
        var urls=json(browser.post("/uploads/"+id+"/part-urls","{\"partNumbers\":[1],\"writerToken\":\""+writer+"\"}")).path("data");
        assertEquals(200,HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(urls.path("parts").get(0).path("url").asText())).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        var completed=json(browser.post("/uploads/"+id+"/complete","{\"writerToken\":\""+writer+"\"}")).path("data");
        java.util.UUID task=java.util.UUID.fromString(completed.path("validationTaskId").asText());
        awaitState(task,"SUCCEEDED");
        var result=new ObjectMapper().readTree(db.queryForObject("SELECT result_json::text FROM tasks WHERE id=?",String.class,task));
        assertEquals(1436,result.path("derivedAudio").path("sourceOffsetMs").asLong());
        assertTrue(result.path("representativeFrames").path("frames").size()>0);
        org.junit.jupiter.api.Assertions.assertFalse(result.path("representativeFrames").path("profile").path("complete").asBoolean(true));
        assertEquals(1,tasks.attemptCount(task));
        broker.convertAndSend("media.v1",new ObjectMapper().writeValueAsBytes(java.util.Map.of("taskId",task.toString())));
        Thread.sleep(1000);
        assertEquals(1,tasks.attemptCount(task));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM execution_reports WHERE task_id=? AND report_kind='COMPLETE'",Integer.class,task));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM task_attempts WHERE task_id=? AND local_execution AND lease_token_hash=''",Integer.class,task));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM tasks WHERE type IN ('CONTENT_ANALYZE','VISION_ANALYZE','INDEX_BUILD','AGENT_TURN') AND status='PENDING'",Integer.class));
        var asset=java.util.UUID.fromString(completed.path("assetId").asText());
        assertEquals("VALID",db.queryForObject("SELECT validation_status FROM assets WHERE id=?",String.class,asset));
        var retiredWorker=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/internal/v1/tasks/"+task+"/claim"))
            .header("Authorization","Bearer internal-integration-worker-token").header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"executorId\":\"retired\",\"claimRequestId\":\""+java.util.UUID.randomUUID()+"\"}")).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(410,retiredWorker.statusCode());
        var sessionReply=browser.post("/projects/"+project+"/conversations",new ObjectMapper().writeValueAsString(java.util.Map.of("title","旧入口检查","assetIds",java.util.List.of(asset))));
        assertEquals(201,sessionReply.statusCode(),sessionReply.body());
        var session=json(sessionReply).path("data").path("id").asText();
        // New Agent admission is durable; its Worker dispatch is a separate implementation step.
        assertEquals(202,browser.post("/conversations/"+session+"/turns","{\"content\":\"视频讲了什么\"}").statusCode());
        assertEquals(1,db.queryForObject("SELECT count(*) FROM tasks WHERE type='AGENT_TURN' AND status='PENDING'",Integer.class));

        java.util.UUID revision=java.util.UUID.randomUUID(),plan=java.util.UUID.randomUUID(),output=java.util.UUID.randomUUID();
        db.update("INSERT INTO edit_plans(id,project_id,title,next_revision_number) VALUES (?,?,'媒体回归方案',2)",plan,java.util.UUID.fromString(project));
        var definition=java.util.Map.of("outputId",output,"title","原声回归","durationMs",2000,
            "clips",java.util.List.of(java.util.Map.of("clipId",java.util.UUID.randomUUID(),"assetId",asset,"sourceInMs",2000,"sourceOutMs",4000,"audio","original","evidenceIds",java.util.List.of(),"reason","已确认片段")),
            "render",java.util.Map.of("profile","LANDSCAPE_720","width",1280,"height",720,"fit","contain","framesPerSecond",30,"container","mp4","videoCodec","h264","audioCodec","aac","configVersion","speech-cut-v1"),
            "subtitles",java.util.Map.of("enabled",false,"cues",java.util.List.of()));
        db.update("INSERT INTO plan_revisions(id,project_id,plan_id,revision_number,source_task_id,content_json) VALUES (?,?,?,1,?,?::jsonb)",revision,java.util.UUID.fromString(project),plan,task,new ObjectMapper().writeValueAsString(java.util.Map.of("schemaVersion","1.0","outputs",java.util.List.of(definition))));
        db.update("INSERT INTO revision_asset_refs(revision_id,asset_id) VALUES (?,?)",revision,asset);
        var previewReply=browser.post("/revisions/"+revision+"/previews",new ObjectMapper().writeValueAsString(java.util.Map.of("outputId",output,"previewProfile","LOW_360")));
        assertEquals(202,previewReply.statusCode(),previewReply.body());
        java.util.UUID preview=java.util.UUID.fromString(json(previewReply).path("data").path("taskId").asText());
        awaitState(preview,"SUCCEEDED");
        verifyRendered(browser,preview,640,360);
        var confirmationReply=browser.post("/revisions/"+revision+"/confirmations","{\"explicitIntent\":\"CONFIRM\"}");
        assertEquals(201,confirmationReply.statusCode(),confirmationReply.body());
        var confirmation=json(confirmationReply).path("data").path("confirmationId").asText();
        var exportReply=browser.post("/revisions/"+revision+"/export-requests",new ObjectMapper().writeValueAsString(java.util.Map.of("outputIds",java.util.List.of(output),"confirmationId",confirmation,"exportProfile","HD_720","explicitIntent","EXPORT")));
        assertEquals(202,exportReply.statusCode(),exportReply.body());
        java.util.UUID export=java.util.UUID.fromString(json(exportReply).path("data").path("taskId").asText());
        awaitState(export,"SUCCEEDED");
        verifyRendered(browser,export,1280,720);
        broker.convertAndSend("media.v1",new ObjectMapper().writeValueAsBytes(java.util.Map.of("taskId",export.toString())));
        Thread.sleep(1000);
        assertEquals(1,tasks.attemptCount(export));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM execution_reports WHERE task_id=? AND report_kind='COMPLETE'",Integer.class,export));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM artifacts WHERE task_id=? AND state='AVAILABLE'",Integer.class,export));
    }
    private void verifyRendered(BrowserApi browser,java.util.UUID task,int width,int height) throws Exception {
        var result=new ObjectMapper().readTree(db.queryForObject("SELECT result_json::text FROM tasks WHERE id=?",String.class,task));
        assertTrue(Math.abs(result.path("durationMs").asLong()-2000)<=100);
        JsonNode video=null,audio=null;
        for(var stream:result.path("mediaInfo").path("streams")) {
            if("video".equals(stream.path("codec_type").asText()))video=stream;
            if("audio".equals(stream.path("codec_type").asText()))audio=stream;
        }
        assertEquals(width,video.path("width").asInt());assertEquals(height,video.path("height").asInt());
        assertEquals("h264",video.path("codec_name").asText());assertEquals("aac",audio.path("codec_name").asText());
        assertEquals("AVAILABLE",db.queryForObject("SELECT state FROM artifacts WHERE task_id=?",String.class,task));
        assertEquals(1,tasks.attemptCount(task));
        String artifact=db.queryForObject("SELECT id::text FROM artifacts WHERE task_id=?",String.class,task);
        String url=json(browser.post("/artifacts/"+artifact+"/download-access","{}")).path("data").path("url").asText();
        var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200,response.statusCode());
        var directory=java.nio.file.Path.of("target/m1-media");java.nio.file.Files.createDirectories(directory);
        java.nio.file.Files.write(directory.resolve(width+"x"+height+".mp4"),response.body());
    }
    @Test
    void transientMediaFailureRetriesThroughOutboxThenDeadLetters() throws Exception {
        BrowserApi owner=signedIn("local-retry-owner");
        var project=java.util.UUID.fromString(json(owner.post("/projects","{\"name\":\"有限重试\"}")).path("data").path("id").asText());
        java.util.UUID task=java.util.UUID.randomUUID();
        var input=java.util.Map.of("assetId",java.util.UUID.randomUUID(),"objectKey","missing-source","sizeBytes",1,"sha256","b".repeat(64));
        db.update("INSERT INTO tasks(id,project_id,type,input_json) VALUES (?,?,'MEDIA_PREPARE',?::jsonb)",task,project,new ObjectMapper().writeValueAsString(input));
        enqueue(task,"MEDIA_PREPARE");
        awaitState(task,"FAILED");
        assertEquals(3,tasks.attemptCount(task));
        assertEquals(3,db.queryForObject("SELECT count(*) FROM execution_reports WHERE task_id=? AND report_kind='FAIL'",Integer.class,task));
        org.springframework.amqp.core.Message dead=null;
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(5).toNanos();
        while(System.nanoTime()<deadline && dead==null) {
            var candidate=broker.receive("media.dead.v1",250);
            if(candidate!=null && new ObjectMapper().readTree(candidate.getBody()).path("taskId").asText().equals(task.toString()))dead=candidate;
        }
        org.junit.jupiter.api.Assertions.assertNotNull(dead);
        assertEquals("MEDIA_EXECUTION_FAILED",new ObjectMapper().readTree(db.queryForObject("SELECT result_json::text FROM tasks WHERE id=?",String.class,task)).path("code").asText());
    }
    @Test
    void cancelledAndAbandonedGenerationsCannotPublishLateResults() throws Exception {
        BrowserApi owner=signedIn("local-cancel-owner");
        var project=java.util.UUID.fromString(json(owner.post("/projects","{\"name\":\"取消与恢复\"}")).path("data").path("id").asText());
        java.util.UUID task=java.util.UUID.randomUUID();
        db.update("INSERT INTO tasks(id,project_id,type,input_json) VALUES (?,?,'MEDIA_PREPARE','{}')",task,project);
        var run=tasks.claimLocal(task);
        tasks.cancel(task);
        tasks.cancelLocal(run);
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class,()->tasks.completeLocal(run,"MEDIA_PREPARED",java.util.Map.of()));
        enqueue(task,"MEDIA_PREPARE");
        Thread.sleep(1000);
        assertEquals("CANCELLED",tasks.read(task).get("status"));
        assertEquals(1,tasks.attemptCount(task));
    }
    @Test
    void brokerOutageKeepsCommittedIntentAndResumesThroughMq() throws Exception {
        BrowserApi owner=signedIn("local-outage-owner");
        var project=java.util.UUID.fromString(json(owner.post("/projects","{\"name\":\"Broker 故障\"}")).path("data").path("id").asText());
        java.util.UUID task=java.util.UUID.randomUUID();
        try {
            assertEquals(0,BROKER.execInContainer("rabbitmqctl","stop_app").getExitCode());
            var input=java.util.Map.of("assetId",java.util.UUID.randomUUID(),"objectKey","missing-after-outage","sizeBytes",1,"sha256","b".repeat(64));
            db.update("INSERT INTO tasks(id,project_id,type,input_json) VALUES (?,?,'MEDIA_PREPARE',?::jsonb)",task,project,new ObjectMapper().writeValueAsString(input));
            enqueue(task,"MEDIA_PREPARE");
            Thread.sleep(2200);
            assertEquals("PENDING",tasks.read(task).get("status"));
            assertEquals(0,tasks.attemptCount(task));
            assertEquals(0,db.queryForObject("SELECT count(*) FROM outbox_events WHERE task_id=? AND status='PUBLISHED'",Integer.class,task));
        }finally { assertEquals(0,BROKER.execInContainer("rabbitmqctl","start_app").getExitCode()); }
        awaitState(task,"FAILED");
        assertEquals(3,tasks.attemptCount(task));
    }
    @Test
    void restartRecoveryCreatesNewGenerationAndRejectsAbandonedPublisher() throws Exception {
        BrowserApi owner=signedIn("local-restart-owner");
        var project=java.util.UUID.fromString(json(owner.post("/projects","{\"name\":\"恢复代次\"}")).path("data").path("id").asText());
        java.util.UUID task=java.util.UUID.randomUUID();
        var input=java.util.Map.of("assetId",java.util.UUID.randomUUID(),"objectKey","missing-after-restart","sizeBytes",1,"sha256","b".repeat(64));
        db.update("INSERT INTO tasks(id,project_id,type,input_json) VALUES (?,?,'MEDIA_PREPARE',?::jsonb)",task,project,new ObjectMapper().writeValueAsString(input));
        var abandoned=tasks.claimLocal(task);
        awaitState(task,"FAILED");
        assertEquals(3,tasks.attemptCount(task));
        assertEquals("EXPIRED",db.queryForObject("SELECT status FROM task_attempts WHERE id=?",String.class,abandoned.attemptId()));
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class,()->tasks.completeLocal(abandoned,"MEDIA_PREPARED",java.util.Map.of()));
    }
    private void enqueue(java.util.UUID task,String type) {
        java.util.UUID event=java.util.UUID.randomUUID();
        db.update("INSERT INTO outbox_events(id,task_id,type,payload_json) VALUES (?,?,?,jsonb_build_object('schemaVersion','1.0','eventId',?::text,'taskId',?::text,'taskType',?::text))",event,task,type,event,task,type);
    }
    private void awaitState(java.util.UUID task,String state) throws Exception {
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(45).toNanos();
        while(System.nanoTime()<deadline) {
            String current=db.queryForObject("SELECT status FROM tasks WHERE id=?",String.class,task);
            if(state.equals(current))return;
            if("FAILED".equals(current))org.junit.jupiter.api.Assertions.fail(db.queryForObject("SELECT result_json::text FROM tasks WHERE id=?",String.class,task));
            Thread.sleep(100);
        }
        org.junit.jupiter.api.Assertions.fail("Task did not reach "+state);
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
                    .header("Content-Type", "application/json").header("X-XSRF-TOKEN", token).header("Idempotency-Key",java.util.UUID.randomUUID().toString())
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
