package com.agenticvideo.business.plan;

import com.agenticvideo.business.project.ProjectAccess;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class EditPlanController {
    private final JdbcTemplate database;
    private final ProjectAccess access;
    private final EditPlans plans;
    public EditPlanController(JdbcTemplate database, ProjectAccess access, EditPlans plans) {
        this.database = database; this.access = access; this.plans = plans;
    }
    @GetMapping("/projects/{projectId}/plans")
    public Object list(@PathVariable UUID projectId, Authentication account) {
        access.requireOwned(projectId, account);
        return response(Map.of("items", database.queryForList("SELECT id,title,editing_task_id AS \"editingTaskId\" FROM edit_plans WHERE project_id=? ORDER BY created_at,id", projectId)));
    }
    @GetMapping("/plans/{planId}/revisions")
    public Object revisions(@PathVariable UUID planId, Authentication account) {
        requireOwned(planId, account);
        return response(Map.of("items", database.queryForList("SELECT id,revision_number AS \"revisionNumber\",parent_revision_id AS \"parentRevisionId\" FROM plan_revisions WHERE plan_id=? ORDER BY revision_number", planId)));
    }
    @GetMapping("/revisions/{revisionId}")
    public Object revision(@PathVariable UUID revisionId, Authentication account) {
        var rows = database.queryForList("SELECT id,plan_id AS \"planId\",revision_number AS \"revisionNumber\",parent_revision_id AS \"parentRevisionId\",content_json FROM plan_revisions WHERE id=?", revisionId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "方案版本不存在或无权访问");
        var row = rows.get(0);
        requireOwned((UUID)row.get("planId"), account);
        row.put("content", plans.parse(row.remove("content_json")));
        var source=plans.productionSource(revisionId);if(source!=null)row.put("productionSource",source);
        return response(row);
    }
    private void requireOwned(UUID plan, Authentication account) {
        if (database.queryForObject("SELECT count(*) FROM edit_plans ep JOIN projects p ON p.id=ep.project_id WHERE ep.id=? AND p.owner_user_id=?", Integer.class, plan, access.currentUserId(account)) != 1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "方案不存在或无权访问");
    }
    private Object response(Object data) { return Map.of("data", data, "requestId", UUID.randomUUID()); }
}
