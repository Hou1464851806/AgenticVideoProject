package com.agenticvideo.business.project;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {
    private final JdbcTemplate database;
    private final ProjectAccess access;

    public ProjectController(JdbcTemplate database, ProjectAccess access) {
        this.database = database;
        this.access = access;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody CreateProject input, Authentication account) {
        UUID id = UUID.randomUUID();
        String name = input.name().strip();
        database.update("INSERT INTO projects(id, owner_user_id, name) VALUES (?, ?, ?)", id, access.currentUserId(account), name);
        return response(new ProjectAccess.Project(id, name, 0));
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) UUID cursor, Authentication account) {
        List<ProjectAccess.Project> projects = database.query(
                "SELECT id, name, row_version FROM projects WHERE owner_user_id = ? AND id > ? ORDER BY id LIMIT 101",
                (row, index) -> new ProjectAccess.Project(row.getObject("id", UUID.class), row.getString("name"), row.getLong("row_version")),
                access.currentUserId(account), cursor == null ? new UUID(0, 0) : cursor);
        boolean more = projects.size() > 100;
        List<ProjectAccess.Project> page = more ? projects.subList(0, 100) : projects;
        return response(new ProjectPage(page, more ? page.get(99).id().toString() : null));
    }

    @GetMapping("/{projectId}")
    public Map<String, Object> open(@PathVariable UUID projectId, Authentication account) {
        return response(access.requireOwned(projectId, account));
    }

    private static Map<String, Object> response(Object data) {
        return Map.of("data", data, "requestId", UUID.randomUUID());
    }

    public record CreateProject(@NotBlank(message = "项目名称不能为空")
                                @Size(max = 120, message = "项目名称不能超过 120 个字符") String name) {}
    public record ProjectPage(List<ProjectAccess.Project> items, String nextCursor) {}
}
