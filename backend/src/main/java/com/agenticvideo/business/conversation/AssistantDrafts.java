package com.agenticvideo.business.conversation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** A bounded recovery window and explicit continuation for incomplete public replies. */
@Service
public class AssistantDrafts {
    private final JdbcTemplate database;
    public AssistantDrafts(JdbcTemplate database) { this.database=database; }
    public Map<String,Object> page(UUID conversation,UUID before) {
        Object created=null,attempt=null;
        if(before!=null) {
            var anchor=database.queryForList("SELECT r.created_at,a.attempt_number FROM assistant_drafts d JOIN task_attempts a ON a.id=d.attempt_id JOIN conversation_turns r ON r.task_id=d.task_id WHERE r.conversation_id=? AND d.message_id=?",conversation,before);
            if(anchor.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"草稿游标不存在或已过期，请重新读取快照");
            created=anchor.get(0).get("created_at");attempt=anchor.get(0).get("attempt_number");
        }
        List<Map<String,Object>> rows=database.queryForList("""
                SELECT d.message_id AS "messageId",d.attempt_id AS "attemptId",d.task_id AS "taskId",
                    d.content,d.content_version AS "contentVersion",d.status,t.current_attempt_id=d.attempt_id AS current
                FROM assistant_drafts d JOIN tasks t ON t.id=d.task_id JOIN task_attempts a ON a.id=d.attempt_id
                JOIN conversation_turns r ON r.task_id=t.id
                WHERE r.conversation_id=? AND d.status='INCOMPLETE'
                    AND (?::uuid IS NULL OR (r.created_at,a.attempt_number,d.message_id)<(?::timestamptz,?::integer,?::uuid))
                ORDER BY r.created_at DESC,a.attempt_number DESC,d.message_id DESC LIMIT 11
                """,conversation,before,created,attempt,before);
        boolean more=rows.size()>10;
        var items=rows.subList(0,Math.min(rows.size(),10));
        var result=new LinkedHashMap<String,Object>();result.put("items",items);
        result.put("nextCursor",more?items.get(items.size()-1).get("messageId"):null);return result;
    }
}
