package com.agenticvideo.business.plan;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.junit.jupiter.api.Assertions.*;

/** The schema itself is the public boundary under test; no save API exists in stage 1. */
@Testcontainers
class ProductionSchemaIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6-bookworm");
    private static JdbcTemplate db;
    @BeforeAll static void migrate() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).load().migrate();
        db = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @Test void productionRevisionHasItsOwnScopeValidationAndAtomicSaveResult() {
        UUID user=UUID.randomUUID(), project=UUID.randomUUID(), otherProject=UUID.randomUUID();
        UUID scope=UUID.randomUUID(), otherScope=UUID.randomUUID(), production=UUID.randomUUID(), otherProduction=UUID.randomUUID();
        db.update("INSERT INTO users(id,normalized_username,password_hash) VALUES (?,?,'fixture')", user,user.toString());
        db.update("INSERT INTO projects(id,owner_user_id,name) VALUES (?,?,'M2'),(?,?,'other')",project,user,otherProject,user);
        db.update("INSERT INTO question_scopes(id,project_id,assets_json) VALUES (?,?,'[]'),(?,?,'[]')",scope,project,otherScope,otherProject);
        assertThrows(DataIntegrityViolationException.class, () -> db.update("INSERT INTO edit_productions(id,project_id,scope_id) VALUES (?,?,?)",production,project,otherScope));
        db.update("INSERT INTO edit_productions(id,project_id,scope_id) VALUES (?,?,?),(?,?,?)",production,project,scope,otherProduction,project,scope);
        db.update("INSERT INTO question_tool_receipts(scope_id,call_id,request_json,result_json) VALUES (?,'call-1','{}','{}')",scope);
        UUID validation=UUID.randomUUID();
        db.update("""
                INSERT INTO edit_draft_validations(id,production_id,project_id,scope_id,call_id,draft_hash,requirements_hash,
                    rule_version,draft_json,requirements_json,sources_json,result_json,normalized_content_json)
                VALUES (?,?,?,?,'call-1',?,?,'hardcut-m2-v1','{}','{}','{}','{"valid":true}','{}')
                """,validation,production,project,scope,"a".repeat(64),"b".repeat(64));
        UUID plan=UUID.randomUUID();
        db.update("INSERT INTO edit_plans(id,project_id,title) VALUES (?,?,'M2')",plan,project);
        String insert="INSERT INTO plan_revisions(id,project_id,plan_id,revision_number,source_production_id,source_validation_id,production_save_result_json,content_json) VALUES (?,?,?,1,?,?,?::jsonb,'{}')";
        assertThrows(DataIntegrityViolationException.class, () -> db.update(insert,UUID.randomUUID(),project,plan,otherProduction,validation,"{}"));
        assertThrows(DataIntegrityViolationException.class, () -> db.update(insert,UUID.randomUUID(),project,plan,production,validation,null));
        UUID revision=UUID.randomUUID();
        db.update(insert,revision,project,plan,production,validation,"{\"status\":\"SAVED\"}");
        assertNull(db.queryForObject("SELECT source_task_id FROM plan_revisions WHERE id=?", UUID.class, revision));
        assertEquals("SAVED",db.queryForObject("SELECT production_save_result_json->>'status' FROM plan_revisions WHERE source_production_id=?",String.class,production));
        UUID anotherPlan=UUID.randomUUID();db.update("INSERT INTO edit_plans(id,project_id,title) VALUES (?,?,'duplicate')",anotherPlan,project);
        assertThrows(DataIntegrityViolationException.class, () -> db.update(insert,UUID.randomUUID(),project,anotherPlan,production,validation,"{}"));
        assertThrows(DataIntegrityViolationException.class, () -> db.update("INSERT INTO plan_revisions(id,project_id,plan_id,revision_number,content_json) VALUES (?,?,?,1,'{}')",UUID.randomUUID(),project,anotherPlan));
        UUID task=UUID.randomUUID();
        db.update("INSERT INTO tasks(id,project_id,type,status,input_json) VALUES (?,?,'AGENT_TURN','SUCCEEDED','{}')",task,project);
        db.update("INSERT INTO plan_revisions(id,project_id,plan_id,revision_number,source_task_id,content_json) VALUES (?,?,?,1,?,'{}')",UUID.randomUUID(),project,anotherPlan,task);
        assertThrows(DataIntegrityViolationException.class, () -> db.update("UPDATE plan_revisions SET source_task_id=? WHERE id=?",task,revision));
    }
}
