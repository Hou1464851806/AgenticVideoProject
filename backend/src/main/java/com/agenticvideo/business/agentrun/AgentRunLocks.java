package com.agenticvideo.business.agentrun;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/** Browser writes serialize on project, conversation, production, then task/attempt and question. */
@Component
public class AgentRunLocks {
    private final JdbcTemplate db;
    public AgentRunLocks(JdbcTemplate db){this.db=db;}
    public Map<String,Object> conversation(UUID id) {
        var rows=db.queryForList("SELECT project_id FROM conversations WHERE id=?",id);
        if(rows.isEmpty())throw new ResponseStatusException(NOT_FOUND,"制作会话不存在");
        db.queryForMap("SELECT id FROM projects WHERE id=? FOR UPDATE",rows.get(0).get("project_id"));
        var session=db.queryForMap("SELECT * FROM conversations WHERE id=? FOR UPDATE",id);
        if(session.get("current_production_id")!=null)db.queryForMap("SELECT id FROM edit_productions WHERE id=? FOR UPDATE",session.get("current_production_id"));
        return session;
    }
    /** Also used before shared task cancellation/heartbeat methods acquire their task lock. */
    public void beforeTask(UUID task) {
        var rows=db.queryForList("SELECT conversation_id,production_id FROM conversation_execution_requests WHERE task_id=? AND schema_version='1.0'",task);
        if(rows.isEmpty())return;
        conversation((UUID)rows.get(0).get("conversation_id"));
        if(rows.get(0).get("production_id")!=null)db.queryForMap("SELECT id FROM edit_productions WHERE id=? FOR UPDATE",rows.get(0).get("production_id"));
    }
}
