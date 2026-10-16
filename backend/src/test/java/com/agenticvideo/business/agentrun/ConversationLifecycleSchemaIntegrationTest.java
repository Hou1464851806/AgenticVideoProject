package com.agenticvideo.business.agentrun;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.junit.jupiter.api.Assertions.*;

/** Database associations are the step-1 public boundary; no browser execution API yet. */
@Testcontainers
class ConversationLifecycleSchemaIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6-bookworm");
    private static JdbcTemplate db;
    private static UUID legacyProduction, legacyTask;

    @BeforeAll static void upgradeExistingDatabase() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        db = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).target("53").load().migrate();
        var fixture = context();
        legacyProduction = UUID.randomUUID(); legacyTask = UUID.randomUUID();
        db.update("INSERT INTO edit_productions(id,project_id,scope_id) VALUES (?,?,?)",legacyProduction,fixture.project(),fixture.scope());
        db.update("INSERT INTO tasks(id,project_id,type,input_json) VALUES (?,?,'AGENT_TURN','{}')",legacyTask,fixture.project());
        db.update("INSERT INTO conversation_execution_requests(task_id,requirement_id,request_json,input_json) VALUES (?,?,'{}','{}')",legacyTask,UUID.randomUUID());
        Flyway.configure().dataSource(source).load().migrate();
        Flyway.configure().dataSource(source).load().validate();
    }
    private record Context(UUID project, UUID conversation, UUID scope) {}
    private static Context context() {
        UUID user=UUID.randomUUID(), project=UUID.randomUUID(), conversation=UUID.randomUUID(), scope=UUID.randomUUID();
        db.update("INSERT INTO users(id,normalized_username,password_hash) VALUES (?,?,'fixture')",user,user.toString());
        db.update("INSERT INTO projects(id,owner_user_id,name) VALUES (?,?,'lifecycle')",project,user);
        db.update("INSERT INTO conversations(id,project_id,title) VALUES (?,?,'lifecycle')",conversation,project);
        db.update("INSERT INTO question_scopes(id,project_id,assets_json) VALUES (?,?,'[]')",scope,project);
        return new Context(project,conversation,scope);
    }
    private UUID production(Context c) {
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO edit_productions(id,project_id,scope_id,conversation_id,thread_id,requirements_json,requirements_sources_json) VALUES (?,?,?,?,?,'{}','{}')",
                id,c.project(),c.scope(),c.conversation(),UUID.randomUUID());
        return id;
    }
    private record Run(UUID task, UUID message, UUID turn, UUID thread, UUID execution, UUID attempt) {}
    private Run run(Context c, UUID production, Long generation) {
        var thread = production == null ? UUID.randomUUID() : db.queryForObject("SELECT thread_id FROM edit_productions WHERE id=?",UUID.class,production);
        var r=new Run(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),thread,UUID.randomUUID(),UUID.randomUUID());
        db.update("INSERT INTO tasks(id,project_id,type,input_json) VALUES (?,?,'AGENT_TURN','{}')",r.task(),c.project());
        db.update("INSERT INTO conversation_messages(id,conversation_id,message_seq,role,content) SELECT ?,?,coalesce(max(message_seq),0)+1,'USER','fixture' FROM conversation_messages WHERE conversation_id=?",r.message(),c.conversation(),c.conversation());
        db.update("INSERT INTO conversation_turns(id,conversation_id,message_id,task_id,request_key,request_json) VALUES (?,?,?,?,?,'{}')",r.turn(),c.conversation(),r.message(),r.task(),r.turn().toString());
        db.update("""
                INSERT INTO conversation_execution_requests(task_id,request_json,input_json,schema_version,project_id,conversation_id,
                    turn_id,message_id,input_hash,thread_id,execution_id,production_id,edit_generation,scope_id)
                VALUES (?,'{}','{}','1.0',?,?,?,?,?,?,?,?,?,?)
                """,r.task(),c.project(),c.conversation(),r.turn(),r.message(),"a".repeat(64),r.thread(),r.execution(),production,generation,c.scope());
        db.update("""
                INSERT INTO task_attempts(id,task_id,attempt_number,executor_id,claim_request_id,lease_token_hash,lease_expires_at)
                VALUES (?,?,1,'fixture',?,? ,now()+interval '1 minute')
                """,r.attempt(),r.task(),UUID.randomUUID(),"b".repeat(64));
        return r;
    }
    @Test void legacyRowsSurviveAndIndependentQuestionDoesNotCreateProduction() {
        assertNull(db.queryForObject("SELECT conversation_id FROM edit_productions WHERE id=?",UUID.class,legacyProduction));
        assertNotNull(db.queryForObject("SELECT requirement_id FROM conversation_execution_requests WHERE task_id=?",UUID.class,legacyTask));
        var c=context(); var r=run(c,null,null);
        assertEquals(0,db.queryForObject("SELECT count(*) FROM edit_productions WHERE conversation_id=?",Integer.class,c.conversation()));
        db.update("UPDATE tasks SET status='WAITING_INPUT' WHERE id=?",r.task());
        assertNull(db.queryForObject("SELECT production_id FROM conversation_execution_requests WHERE task_id=?",UUID.class,r.task()));
        assertThrows(DataIntegrityViolationException.class, () -> db.update("UPDATE conversation_execution_requests SET schema_version=NULL WHERE task_id=?",r.task()));
        assertThrows(DataIntegrityViolationException.class, () -> db.update("UPDATE conversation_execution_requests SET input_hash='bad' WHERE task_id=?",r.task()));
    }
    @Test void currentProductionAndRunCannotCrossProjectOrConversation() {
        var c=context(); var other=context(); var p=production(c); var foreign=production(other);
        assertThrows(DataIntegrityViolationException.class, () -> db.update("UPDATE conversations SET current_production_id=? WHERE id=?",foreign,c.conversation()));
        UUID sameProjectConversation=UUID.randomUUID();
        db.update("INSERT INTO conversations(id,project_id,title) VALUES (?,?,'other')",sameProjectConversation,c.project());
        assertThrows(DataIntegrityViolationException.class, () -> db.update("UPDATE conversations SET current_production_id=? WHERE id=?",p,sameProjectConversation));
        db.update("UPDATE conversations SET current_production_id=? WHERE id=?",p,c.conversation());
        var r=run(c,p,0L);
        assertThrows(DataIntegrityViolationException.class, () -> db.update("UPDATE conversation_execution_requests SET production_id=? WHERE task_id=?",foreign,r.task()));
        assertThrows(DataIntegrityViolationException.class, () -> db.update("UPDATE conversation_execution_requests SET project_id=? WHERE task_id=?",other.project(),r.task()));
        assertThrows(DataIntegrityViolationException.class, () -> db.update("UPDATE conversation_execution_requests SET conversation_id=? WHERE task_id=?",sameProjectConversation,r.task()));
        assertThrows(DataIntegrityViolationException.class, () -> db.update("UPDATE conversation_execution_requests SET edit_generation=NULL WHERE task_id=?",r.task()));
        assertThrows(DataIntegrityViolationException.class, () -> db.update("UPDATE conversation_execution_requests SET thread_id=? WHERE task_id=?",UUID.randomUUID(),r.task()));
    }
    @Test void draftBindsExactValidationHashesGenerationAttemptAndMessage() {
        var c=context(); var p=production(c); var r=run(c,p,0L); var other=production(c);
        UUID validation=UUID.randomUUID(), message=UUID.randomUUID();
        db.update("INSERT INTO question_tool_receipts(scope_id,call_id,request_json,result_json) VALUES (?,'validate','{}','{}')",c.scope());
        db.update("""
                INSERT INTO edit_draft_validations(id,production_id,project_id,scope_id,call_id,draft_hash,requirements_hash,
                    rule_version,draft_json,requirements_json,sources_json,result_json,normalized_content_json,source_task_id,source_attempt_id,edit_generation)
                VALUES (?,?,?,?,'validate',?,?,'hardcut-m2-v1','{}','{}','{}','{"valid":true}','{}',?,?,0)
                """,validation,p,c.project(),c.scope(),"a".repeat(64),"b".repeat(64),r.task(),r.attempt());
        assertThrows(DataIntegrityViolationException.class, () -> db.update("UPDATE edit_draft_validations SET source_attempt_id=? WHERE id=?",UUID.randomUUID(),validation));
        db.update("INSERT INTO conversation_messages(id,conversation_id,message_seq,role,content,source_task_id) VALUES (?,?,2,'ASSISTANT','draft',?)",message,c.conversation(),r.task());
        db.update("""
                INSERT INTO conversation_edit_drafts(message_id,conversation_id,project_id,production_id,validation_id,source_task_id,draft_hash,requirements_hash,edit_generation)
                VALUES (?,?,?,?,?,?,?,?,0)
                """,message,c.conversation(),c.project(),p,validation,r.task(),"a".repeat(64),"b".repeat(64));
        assertThrows(DataIntegrityViolationException.class, () -> db.update("UPDATE conversation_edit_drafts SET source_task_id=? WHERE message_id=?",UUID.randomUUID(),message));
        assertThrows(DataIntegrityViolationException.class, () -> db.update("UPDATE conversation_edit_drafts SET production_id=? WHERE message_id=?",other,message));
        assertThrows(DataIntegrityViolationException.class, () -> db.update("UPDATE conversation_edit_drafts SET draft_hash=? WHERE message_id=?","c".repeat(64),message));
        assertThrows(DataIntegrityViolationException.class, () -> db.update("UPDATE conversation_edit_drafts SET requirements_hash=? WHERE message_id=?","c".repeat(64),message));
        assertThrows(DataIntegrityViolationException.class, () -> db.update("UPDATE conversation_edit_drafts SET edit_generation=1 WHERE message_id=?",message));
        db.update("UPDATE edit_productions SET current_draft_message_id=?,edit_generation=1 WHERE id=?",message,p);
        assertThrows(DataIntegrityViolationException.class, () -> db.update("UPDATE edit_productions SET current_draft_message_id=? WHERE id=?",message,other));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM plan_revisions WHERE source_production_id=?",Integer.class,p));
        assertThrows(DataIntegrityViolationException.class, () -> db.update("UPDATE conversation_execution_requests SET scope_id=? WHERE task_id=?",context().scope(),r.task()));
        // The last published draft remains readable after an edit; eligibility is checked by step 3.
        assertEquals(0L,db.queryForObject("SELECT edit_generation FROM conversation_edit_drafts WHERE message_id=?",Long.class,message));
        db.update("""
                INSERT INTO conversation_questions(id,conversation_id,task_id,turn_id,prompt,reason,fields_json,production_id,thread_id,execution_id,wait_id,edit_generation)
                VALUES (?,?,?,?,'duration?','missing','[]',?,?,?,'wait-1',0)
                """,UUID.randomUUID(),c.conversation(),r.task(),r.turn(),p,r.thread(),r.execution());
        assertThrows(DataIntegrityViolationException.class, () -> db.update("UPDATE conversation_questions SET execution_id=? WHERE task_id=?",UUID.randomUUID(),r.task()));
        assertThrows(DataIntegrityViolationException.class, () -> db.update("UPDATE conversation_questions SET production_id=? WHERE task_id=?",other,r.task()));
        var unrelated=run(context(),null,null);
        assertThrows(DataIntegrityViolationException.class, () -> db.update("UPDATE conversation_execution_requests SET core_task_id=? WHERE task_id=?",unrelated.task(),r.task()));
        assertThrows(DataIntegrityViolationException.class, () -> db.update("UPDATE conversation_execution_requests SET core_task_id=task_id WHERE task_id=?",r.task()));
        assertThrows(DataIntegrityViolationException.class, () -> db.update("UPDATE conversation_questions SET successor_task_id=? WHERE task_id=?",unrelated.task(),r.task()));
        var answer=run(c,p,0L);
        db.update("UPDATE conversation_execution_requests SET thread_id=?,execution_id=? WHERE task_id=?",r.thread(),r.execution(),answer.task());
        db.update("UPDATE conversation_questions SET successor_task_id=? WHERE task_id=?",answer.task(),r.task());
        assertEquals(2,db.queryForObject("SELECT count(*) FROM conversation_execution_requests WHERE execution_id=?",Integer.class,r.execution()));
    }
}
