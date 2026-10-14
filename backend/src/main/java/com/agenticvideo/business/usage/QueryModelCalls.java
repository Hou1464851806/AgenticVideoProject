package com.agenticvideo.business.usage;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Persists receipt uncertainty before a synchronous external query; never stores query content. */
@Service
public class QueryModelCalls {
    private final JdbcTemplate db;
    public QueryModelCalls(JdbcTemplate db){this.db=db;}
    public UUID begin(UUID project,String model,int bytes,UUID task) {
        UUID id=UUID.randomUUID();db.update("INSERT INTO query_model_calls(id,project_id,model,input_bytes,status,task_id) VALUES (?,?,?,?,'SUBMITTING',?)",id,project,model,bytes,task);return id;
    }
    public UUID beginQuestion(UUID project,String model,int bytes,UUID search) {
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO query_model_calls(id,project_id,model,input_bytes,status,question_search_id) VALUES (?,?,?,?,'SUBMITTING',?)",id,project,model,bytes,search);
        return id;
    }
    public void succeeded(UUID id,JsonNode result){db.update("UPDATE query_model_calls SET status='SUCCEEDED',request_id=?,usage_json=?::jsonb,response_json=?::jsonb WHERE id=?",result.path("request_id").asText(),result.path("usage").isObject()?result.path("usage").toString():null,result.toString(),id);}
    public void uncertain(UUID id){db.update("UPDATE query_model_calls SET status='UNCERTAIN' WHERE id=? AND status='SUBMITTING'",id);}
}
