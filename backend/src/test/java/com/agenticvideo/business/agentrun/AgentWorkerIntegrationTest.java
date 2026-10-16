package com.agenticvideo.business.agentrun;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

/** Real Java HTTP/PostgreSQL/RabbitMQ and a separate Python public Agent/SQLite process. */
@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"tasks.dispatch-enabled=true","tasks.consume-enabled=false","tasks.dispatch-interval-ms=100","conversation.events.cleanup-enabled=false"})
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class AgentWorkerIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:18.6-bookworm");
    @Container static final GenericContainer<?> RABBIT=new GenericContainer<>("rabbitmq:4.3.6-management").withEnv("RABBITMQ_DEFAULT_USER","fixture").withEnv("RABBITMQ_DEFAULT_PASS","fixture").withExposedPorts(5672);
    static final String TOKEN="actual-worker-fixture-token";
    @DynamicPropertySource static void services(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",POSTGRES::getJdbcUrl);p.add("spring.datasource.username",POSTGRES::getUsername);p.add("spring.datasource.password",POSTGRES::getPassword);
        p.add("spring.rabbitmq.host",RABBIT::getHost);p.add("spring.rabbitmq.port",()->RABBIT.getMappedPort(5672));
        p.add("spring.rabbitmq.username",()->"fixture");p.add("spring.rabbitmq.password",()->"fixture");p.add("internal.worker-token",()->TOKEN);
    }
    @LocalServerPort int port;
    @Autowired MockMvc http;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired AgentRunRecovery recovery;
    @Autowired com.agenticvideo.business.task.TaskExecution tasks;
    @Autowired org.springframework.amqp.rabbit.core.RabbitTemplate broker;
    @TempDir Path state;
    boolean dropAck,holdBeforeExecution;
    Process worker;UUID project,conversation;String owner;
    Path root=Path.of("..").toAbsolutePath().normalize();
    @BeforeEach void setup() {
        UUID account=UUID.randomUUID();project=UUID.randomUUID();conversation=UUID.randomUUID();owner=account.toString();
        db.update("INSERT INTO users(id,normalized_username,password_hash) VALUES (?,?,'fixture')",account,owner);
        db.update("INSERT INTO projects(id,owner_user_id,name) VALUES (?,?,'worker')",project,account);
        db.update("INSERT INTO conversations(id,project_id,title) VALUES (?,?,'worker')",conversation,project);
    }
    void start(boolean crash) throws Exception {start(crash,false,false);}
    void start(boolean crash,boolean failReceipt,boolean failValidation) throws Exception {
        var process=new ProcessBuilder(root.resolve("agent-service/.venv/bin/python").toString(),root.resolve("agent-service/tests/worker_process_fixture.py").toString());
        var env=process.environment();env.put("PYTHONPATH",root.resolve("agent-service").toString());env.put("JAVA_URL","http://127.0.0.1:"+port);env.put("INTERNAL_WORKER_TOKEN",TOKEN);
        env.put("RABBITMQ_HOST",RABBIT.getHost());env.put("RABBITMQ_PORT",RABBIT.getMappedPort(5672).toString());env.put("RABBITMQ_USER","fixture");env.put("RABBITMQ_PASSWORD","fixture");env.put("AGENT_DATA_DIRECTORY",state.toString());env.put("CRASH_AFTER_RECEIPT",crash?"1":"0");
        env.put("FAIL_AFTER_RECEIPT",failReceipt?"1":"0");env.put("FAIL_AFTER_VALIDATION",failValidation?"1":"0");
        env.put("DROP_ACK",dropAck?"1":"0");env.put("HOLD_BEFORE_EXECUTE",holdBeforeExecution?"1":"0");
        process.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(state.resolve("worker.log").toFile()));worker=process.start();
    }
    @AfterEach void stop() throws Exception {
        if(worker!=null&&worker.isAlive()){worker.destroyForcibly();assertTrue(worker.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));}
        for(var row:db.queryForList("SELECT id FROM tasks WHERE project_id=? AND status IN ('PENDING','RUNNING','CANCEL_REQUESTED','WAITING_INPUT')",project)) {
            UUID task=(UUID)row.get("id");tasks.cancel(task);
            db.update("UPDATE task_attempts SET lease_expires_at=now()-interval '1 second' WHERE id=(SELECT current_attempt_id FROM tasks WHERE id=?)",task);
        }
        recovery.recover();
    }
    void expireAttempt(UUID attempt) throws Exception {
        // A request already accepted by Java may finish its heartbeat after the process dies.
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(5).toNanos();
        while("RUNNING".equals(db.queryForObject("SELECT status FROM task_attempts WHERE id=?",String.class,attempt))&&System.nanoTime()<deadline) {
            db.update("UPDATE task_attempts SET lease_expires_at=now()-interval '1 second' WHERE id=?",attempt);
            recovery.recover();Thread.sleep(20);
        }
        assertEquals("EXPIRED",db.queryForObject("SELECT status FROM task_attempts WHERE id=?",String.class,attempt));
    }
    JsonNode send(String content) throws Exception {
        var response=http.perform(post("/api/v1/conversations/"+conversation+"/turns").with(user(owner)).with(csrf()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsBytes(Map.of("content",content)))).andReturn().getResponse();
        assertEquals(202,response.getStatus(),response.getContentAsString());return json.readTree(response.getContentAsString()).path("data");
    }
    String status(JsonNode run){return db.queryForObject("SELECT status FROM tasks WHERE id=?",String.class,UUID.fromString(run.path("taskId").asText()));}
    void await(JsonNode run,String expected) throws Exception {
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(30).toNanos();
        while(!expected.equals(status(run))&&System.nanoTime()<deadline)Thread.sleep(100);
        assertEquals(expected,status(run),Files.exists(state.resolve("worker.log"))?Files.readString(state.resolve("worker.log")):"worker not started");
    }
    @Test void actualQueueReleasesWaitingAndResumesLinkedReplyWithoutDuplicatePublication() throws Exception {
        start(false);var first=send("wait");await(first,"WAITING_INPUT");
        var question=db.queryForMap("SELECT * FROM conversation_questions WHERE task_id=?",UUID.fromString(first.path("taskId").asText()));
        var independent=send("discuss");await(independent,"SUCCEEDED");
        assertEquals("OPEN",db.queryForObject("SELECT status FROM conversation_questions WHERE id=?",String.class,question.get("id")));
        var reply=send("reply");await(reply,"SUCCEEDED");
        assertEquals("ANSWERED",db.queryForObject("SELECT status FROM conversation_questions WHERE id=?",String.class,question.get("id")));
        assertEquals("SUCCEEDED",status(first));
        assertEquals(db.queryForObject("SELECT execution_id FROM conversation_execution_requests WHERE task_id=?",UUID.class,UUID.fromString(first.path("taskId").asText())),db.queryForObject("SELECT execution_id FROM conversation_execution_requests WHERE task_id=?",UUID.class,UUID.fromString(reply.path("taskId").asText())));
        broker.convertAndSend("","agent.v1",json.writeValueAsBytes(Map.of("schemaVersion","1.0","taskType","AGENT_TURN","taskId",reply.path("taskId").asText())));
        Thread.sleep(600);
        assertEquals(1,db.queryForObject("SELECT count(*) FROM conversation_messages WHERE source_task_id=?",Integer.class,UUID.fromString(reply.path("taskId").asText())));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM plan_revisions WHERE project_id=?",Integer.class,project));
    }
    @Test void processDeathAfterReceiptRecoversSameExecutionWithNewAttempt() throws Exception {
        start(true);var run=send("discuss");
        assertTrue(worker.waitFor(20,java.util.concurrent.TimeUnit.SECONDS));assertEquals(74,worker.exitValue(),Files.readString(state.resolve("worker.log")));
        UUID attempt=db.queryForObject("SELECT current_attempt_id FROM tasks WHERE id=?",UUID.class,UUID.fromString(run.path("taskId").asText()));
        expireAttempt(attempt);
        start(false);await(run,"SUCCEEDED");
        assertEquals(2,db.queryForObject("SELECT count(*) FROM task_attempts WHERE task_id=?",Integer.class,UUID.fromString(run.path("taskId").asText())));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM conversation_messages WHERE source_task_id=?",Integer.class,UUID.fromString(run.path("taskId").asText())));
    }
    @Test void cancelStopsInflightCallAndCrashWithUnknownReceiptDoesNotPayAgain() throws Exception {
        start(false);var run=send("hold");await(run,"RUNNING");
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(15).toNanos();
        while(!Files.exists(state.resolve("holding"))&&System.nanoTime()<deadline)Thread.sleep(50);
        assertTrue(Files.exists(state.resolve("holding")));
        var response=http.perform(post("/api/v1/tasks/"+run.path("taskId").asText()+"/cancel").with(user(owner)).with(csrf())).andReturn().getResponse();assertEquals(202,response.getStatus());
        await(run,"CANCELLED");assertEquals(0,db.queryForObject("SELECT count(*) FROM conversation_messages WHERE source_task_id=?",Integer.class,UUID.fromString(run.path("taskId").asText())));
        Files.delete(state.resolve("holding"));var unknown=send("hold");await(unknown,"RUNNING");
        deadline=System.nanoTime()+java.time.Duration.ofSeconds(15).toNanos();while(!Files.exists(state.resolve("holding"))&&System.nanoTime()<deadline)Thread.sleep(50);
        assertTrue(Files.exists(state.resolve("holding")));worker.destroyForcibly();assertTrue(worker.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));
        expireAttempt(db.queryForObject("SELECT current_attempt_id FROM tasks WHERE id=?",UUID.class,UUID.fromString(unknown.path("taskId").asText())));
        start(false);await(unknown,"FAILED");
        var result=json.readTree(db.queryForObject("SELECT result_json FROM tasks WHERE id=?",String.class,UUID.fromString(unknown.path("taskId").asText())));
        assertFalse(result.path("failure").path("recoverable").asBoolean());assertTrue(result.path("content").asText().contains("不确定"));
    }
    @Test void explicitRetryResumesOriginalPaidReceiptWithoutRepeatingUnderstanding() throws Exception {
        start(false,true,false);var run=send("discuss");await(run,"FAILED");
        var response=http.perform(post("/api/v1/conversations/"+conversation+"/tasks/"+run.path("taskId").asText()+"/retry").with(user(owner)).with(csrf()).header("Idempotency-Key","retry").contentType("application/json").content("{}")).andReturn().getResponse();
        assertEquals(202,response.getStatus(),response.getContentAsString());
        var retry=json.readTree(response.getContentAsString()).path("data");await(retry,"SUCCEEDED");
        assertEquals("FAILED",status(run));
        var check=new ProcessBuilder(root.resolve("agent-service/.venv/bin/python").toString(),"-c","import sqlite3,sys; d=sqlite3.connect(sys.argv[1]); print(d.execute('select count(*) from model_calls').fetchone()[0])",state.resolve("agent.sqlite").toString()).start();
        assertEquals("2",new String(check.getInputStream().readAllBytes()).strip());assertEquals(0,check.waitFor());
        assertEquals(1,db.queryForObject("SELECT count(*) FROM conversation_messages WHERE conversation_id=? AND role='USER'",Integer.class,conversation));
    }
    @Test void retryReusesValidationCommittedByFailedPredecessorAndCanBeAccepted() throws Exception {
        media();start(false,false,true);var run=send("make");await(run,"FAILED");
        var response=http.perform(post("/api/v1/conversations/"+conversation+"/tasks/"+run.path("taskId").asText()+"/retry").with(user(owner)).with(csrf()).header("Idempotency-Key","retry").contentType("application/json").content("{}")).andReturn().getResponse();
        assertEquals(202,response.getStatus(),response.getContentAsString());var retry=json.readTree(response.getContentAsString()).path("data");await(retry,"SUCCEEDED");
        var result=json.readTree(db.queryForObject("SELECT result_json FROM tasks WHERE id=?",String.class,UUID.fromString(retry.path("taskId").asText())));
        var accepted=http.perform(post("/api/v1/conversations/"+conversation+"/drafts/accept").with(user(owner)).with(csrf()).header("Idempotency-Key","accept").contentType("application/json").content(result.path("draft").toString())).andReturn().getResponse();
        assertEquals(200,accepted.getStatus(),accepted.getContentAsString());
        assertEquals(1,db.queryForObject("SELECT count(*) FROM edit_draft_validations WHERE source_task_id=?",Integer.class,UUID.fromString(run.path("taskId").asText())));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM edit_draft_validations WHERE source_task_id=?",Integer.class,UUID.fromString(retry.path("taskId").asText())));
    }
    @Test void committedJavaResultSurvivesActualBrokerAckLoss() throws Exception {
        dropAck=true;start(false);var run=send("discuss");await(run,"SUCCEEDED");
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(15).toNanos();
        while(!Files.exists(state.resolve("ack-replayed"))&&System.nanoTime()<deadline)Thread.sleep(100);
        assertTrue(Files.exists(state.resolve("ack-lost")));assertTrue(Files.exists(state.resolve("ack-replayed")),Files.readString(state.resolve("worker.log")));
        UUID task=UUID.fromString(run.path("taskId").asText());
        assertEquals(1,db.queryForObject("SELECT count(*) FROM task_attempts WHERE task_id=?",Integer.class,task));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM conversation_messages WHERE source_task_id=?",Integer.class,task));
    }
    @Test void cancellingProductionWaitAllowsNewModificationWithoutAnsweringOldQuestion() throws Exception {
        media();start(false);var waiting=send("make-wait");await(waiting,"WAITING_INPUT");
        var original=db.queryForMap("SELECT thread_id,execution_id,production_id FROM conversation_execution_requests WHERE task_id=?",UUID.fromString(waiting.path("taskId").asText()));
        var cancelled=http.perform(post("/api/v1/tasks/"+waiting.path("taskId").asText()+"/cancel").with(user(owner)).with(csrf())).andReturn().getResponse();
        assertEquals(202,cancelled.getStatus());await(waiting,"CANCELLED");
        var followup=send("continue");await(followup,"SUCCEEDED");
        var resumed=db.queryForMap("SELECT thread_id,execution_id,production_id,edit_generation FROM conversation_execution_requests WHERE task_id=?",UUID.fromString(followup.path("taskId").asText()));
        assertEquals(original.get("thread_id"),resumed.get("thread_id"));assertEquals(original.get("production_id"),resumed.get("production_id"));
        assertNotEquals(original.get("execution_id"),resumed.get("execution_id"));assertEquals(2L,resumed.get("edit_generation"));
        assertEquals("CANCELLED",db.queryForObject("SELECT status FROM conversation_questions WHERE task_id=?",String.class,UUID.fromString(waiting.path("taskId").asText())));
    }
    @Test void cancelAfterJavaBindingBeforeCheckpointPreservesConditionsForNextModification() throws Exception {
        media();holdBeforeExecution=true;start(false);var first=send("make-hold");await(first,"RUNNING");
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(15).toNanos();
        while(!Files.exists(state.resolve("before-execution"))&&System.nanoTime()<deadline)Thread.sleep(50);
        assertTrue(Files.exists(state.resolve("before-execution")));
        var cancelled=http.perform(post("/api/v1/tasks/"+first.path("taskId").asText()+"/cancel").with(user(owner)).with(csrf())).andReturn().getResponse();
        assertEquals(202,cancelled.getStatus());await(first,"CANCELLED");
        var followup=send("continue");await(followup,"SUCCEEDED");
        assertEquals("false",db.queryForObject("SELECT p.requirements_json->>'subtitles_enabled' FROM edit_productions p JOIN conversations c ON c.current_production_id=p.id WHERE c.id=?",String.class,conversation));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM conversation_edit_drafts WHERE source_task_id=?",Integer.class,UUID.fromString(followup.path("taskId").asText())));
    }
    record Media(UUID asset,UUID evidence) {}
    Media media() {
        UUID upload=UUID.randomUUID(),asset=UUID.randomUUID(),analysis=UUID.randomUUID(),task=UUID.randomUUID(),model=UUID.randomUUID(),evidence=UUID.randomUUID();String hash="b".repeat(64);
        db.update("INSERT INTO upload_sessions(id,project_id,actor_user_id,idempotency_key,filename,declared_bytes,declared_sha256,part_size_bytes,expected_parts,object_key,writer_token) SELECT ?,id,owner_user_id,?,'speech.mp4',1,?,1,1,?,? FROM projects WHERE id=?",upload,upload.toString(),hash,upload.toString(),UUID.randomUUID(),project);
        db.update("INSERT INTO assets(id,project_id,upload_id,object_key,filename,byte_size,sha256,validation_status,duration_ms,media_info_json,time_mapping_json) VALUES (?,?,?,?,'speech.mp4',1,?,'VALID',20000,'{\"streams\":[{\"codec_type\":\"audio\",\"duration\":\"20\"}]}','{\"hasAudio\":true,\"audioStartMs\":0,\"formatStartMs\":0}')",asset,project,upload,asset.toString(),hash);
        db.update("INSERT INTO asset_analysis_revisions(id,project_id,asset_id,source_sha256,profile_hash) VALUES (?,?,?,?,'fixture')",analysis,project,asset,hash);
        db.update("INSERT INTO asset_analysis_heads VALUES (?,?)",asset,analysis);
        db.update("INSERT INTO analysis_stages(asset_id,analysis_id,stage_key,status) VALUES (?,?,'ASR','SUCCEEDED')",asset,analysis);
        db.update("INSERT INTO tasks(id,project_id,type,status,input_json) VALUES (?,?,'CONTENT_ANALYZE','SUCCEEDED','{}')",task,project);
        db.update("INSERT INTO model_calls(id,analysis_id,task_id,model,parameters_json,status) VALUES (?,?,?,'fixture','{}','SUCCEEDED')",model,analysis,task);
        db.update("INSERT INTO evidence_segments(id,project_id,asset_id,analysis_id,source_kind,ordinal,text,start_ms,end_ms,audio_start_ms,audio_end_ms,observation_json,model_call_id) VALUES (?,?,?,?,'SPEECH',0,'先确定主题。',0,5000,0,5000,'{}',?)",evidence,project,asset,analysis,model);
        db.update("UPDATE conversations SET asset_ids_json=?::jsonb WHERE id=?",json.valueToTree(List.of(asset)).toString(),conversation);
        return new Media(asset,evidence);
    }
    @Test void actualWorkerReadsEvidenceAndPublishesValidatedDraftWithoutSavingVersion() throws Exception {
        media();start(false);var run=send("make");await(run,"SUCCEEDED");
        UUID task=UUID.fromString(run.path("taskId").asText());
        assertEquals(1,db.queryForObject("SELECT count(*) FROM conversation_edit_drafts WHERE source_task_id=?",Integer.class,task));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM edit_draft_validations WHERE source_task_id=? AND source_attempt_id=(SELECT current_attempt_id FROM tasks WHERE id=?)",Integer.class,task,task));
        assertEquals(2,db.queryForObject("SELECT count(*) FROM agent_run_tool_calls WHERE task_id=?",Integer.class,task));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM plan_revisions WHERE project_id=?",Integer.class,project));
        var result=json.readTree(db.queryForObject("SELECT result_json FROM tasks WHERE id=?",String.class,task));
        assertEquals("DRAFT_READY",result.path("status").asText());assertEquals(1,java.util.stream.StreamSupport.stream(result.path("scopeReceipts").spliterator(),false).mapToInt(r->r.path("evidenceIds").size()).sum());
    }
}
