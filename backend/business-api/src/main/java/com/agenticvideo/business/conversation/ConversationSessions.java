package com.agenticvideo.business.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ConversationSessions {
    private final JdbcTemplate database;
    private final ObjectMapper json;

    public ConversationSessions(JdbcTemplate database,ObjectMapper json) {
        this.database=database;
        this.json=json;
    }

    @Transactional
    public Object create(UUID projectId,String key,ConversationController.CreateSession request) throws java.io.IOException {
        boolean newChat=request.title()==null;
        if(!newChat)key=null; // The existing named-session API did not use a creation receipt.
        if ((newChat&&key==null)||(key!=null&&(key.isBlank()||key.length()>120)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"新聊天需要合法的创建幂等键");
        if (!newChat&&request.title().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"会话名称不能为空");
        database.queryForObject("SELECT id FROM projects WHERE id=? FOR UPDATE",UUID.class,projectId);
        String payload=json.writeValueAsString(request);
        if (key!=null) {
            var previous=database.queryForList("SELECT id,title,asset_ids_json,creation_request_json=?::jsonb AS same_request FROM conversations WHERE project_id=? AND creation_key=?",payload,projectId,key);
            if (!previous.isEmpty()) {
                var row=previous.get(0);
                if (!Boolean.TRUE.equals(row.get("same_request")))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"同一创建幂等键不能用于不同聊天");
                return Map.of("id",row.get("id"),"projectId",projectId,"title",row.get("title"),"assetIds",json.readTree(row.get("asset_ids_json").toString()),"naturalLanguage",newChat);
            }
        }
        List<UUID> scope=request.assetIds()==null
                ?(newChat?database.query("SELECT id FROM assets WHERE project_id=? AND validation_status='VALID' ORDER BY id",(row,index)->row.getObject("id",UUID.class),projectId):List.of())
                :request.assetIds();
        for (UUID asset:scope) {
            if(database.queryForObject("SELECT count(*) FROM assets WHERE id=? AND project_id=? AND validation_status='VALID'",Integer.class,asset,projectId)!=1)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,"素材不存在或无权访问");
        }
        UUID id=UUID.randomUUID();
        String title=newChat?"新聊天 "+(database.queryForObject("SELECT count(*) FROM conversations WHERE project_id=?",Integer.class,projectId)+1):request.title().strip();
        database.update("INSERT INTO conversations(id,project_id,title,asset_ids_json,creation_key,creation_request_json) VALUES (?,?,?,?::jsonb,?,?::jsonb)",id,projectId,title,json.writeValueAsString(scope),key,payload);
        if(newChat) {
            String welcome="你好，我是你的视频助手。可以直接告诉我想查找什么内容，或想制作哪种讲话短视频。默认使用当前项目已验证的 "+scope.size()+" 份素材；也可以说‘只用 A 和 B’来限定范围。尚未分析的内容需要进一步核查。";
            database.update("INSERT INTO conversation_messages(id,conversation_id,message_seq,role,content) VALUES (?,?,1,'ASSISTANT',?)",UUID.randomUUID(),id,welcome);
        }
        return Map.of("id",id,"projectId",projectId,"title",title,"assetIds",scope,"naturalLanguage",newChat);
    }
}
