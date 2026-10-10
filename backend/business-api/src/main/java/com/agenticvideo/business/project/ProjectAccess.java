package com.agenticvideo.business.project;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/** Server-owned authorization boundary reused by all project resource operations. */
@Service
public class ProjectAccess {
    private final JdbcTemplate database;

    public ProjectAccess(JdbcTemplate database) { this.database = database; }

    public UUID currentUserId(Authentication account) {
        return database.queryForObject("SELECT id FROM users WHERE normalized_username = ? AND status = 'ACTIVE'",
                UUID.class, account.getName());
    }

    public Project requireOwned(UUID projectId, Authentication account) {
        return database.query("SELECT id, name, row_version FROM projects WHERE id = ? AND owner_user_id = ?",
                (row, index) -> new Project(row.getObject("id", UUID.class), row.getString("name"), row.getLong("row_version")),
                projectId, currentUserId(account)).stream().findFirst().orElseThrow(ProjectNotFound::new);
    }

    public record Project(UUID id, String name, long rowVersion) {}
    static final class ProjectNotFound extends RuntimeException {}
}
