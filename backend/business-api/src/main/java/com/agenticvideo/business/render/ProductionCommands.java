package com.agenticvideo.business.render;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Recognizes direct user instructions before any agent receives them. Questions are clarification only. */
@Service
public class ProductionCommands {
    private static final Pattern CONFIRM=Pattern.compile("(?:请)?确认(?:当前版本|[Vv]([1-9][0-9]*))");
    private static final Pattern EXPORT=Pattern.compile("(?:请)?导出(?:当前版本|[Vv]([1-9][0-9]*))(?:第([1-9][0-9]*)条)?(?:(720|1080)[pP])?");
    private final JdbcTemplate database;
    private final ObjectMapper json;
    private final ExportRequests exports;
    public ProductionCommands(JdbcTemplate database,ObjectMapper json,ExportRequests exports){this.database=database;this.json=json;this.exports=exports;}
    public record Reply(String content,List<Object> references) {}
    private Reply clarify(String reason){return new Reply(reason+" 请明确发送“确认当前版本”，再另行发送“导出当前版本 720p”或“导出当前版本 1080p”。",List.of());}
    private String normalized(String text){return text.replaceAll("\\s+","").replaceFirst("[。！!]$","");}
    private UUID selectedRevision(Map<String,Object> session,String number) {
        if(session.get("view_revision_id")==null)return null;
        if(number==null)return (UUID)session.get("view_revision_id");
        long value;try{value=Long.parseLong(number);}catch(NumberFormatException invalid){return null;}
        var rows=database.queryForList("SELECT id FROM plan_revisions WHERE project_id=? AND plan_id=? AND revision_number=?",session.get("project_id"),session.get("view_plan_id"),value);
        return rows.isEmpty()?null:(UUID)rows.get(0).get("id");
    }
    /** Observe the selected source outside the conversation admission transaction. */
    public ExportRequests.Prepared prepare(UUID sessionId,String text) {
        var command=EXPORT.matcher(normalized(text));if(!command.matches())return null;
        var session=database.queryForMap("SELECT * FROM conversations WHERE id=?",sessionId);
        UUID revision=selectedRevision(session,command.group(1));if(revision==null)return null;
        if(json.valueToTree(exports.confirmations(revision)).path("items").isEmpty())return null;
        try {
            var outputs=json.readTree(database.queryForObject("SELECT content_json::text FROM plan_revisions WHERE id=?",String.class,revision)).path("outputs");
            int selected=0;
            if(command.group(2)!=null){try{selected=Integer.parseInt(command.group(2))-1;}catch(NumberFormatException invalid){return null;}}
            else if(outputs.size()!=1)return null;
            if(selected<0||selected>=outputs.size())return null;
            return exports.prepare(revision,UUID.fromString(outputs.get(selected).path("outputId").asText()));
        }catch(java.io.IOException invalid){throw new IllegalStateException(invalid);}
    }
    public Reply route(Map<String,Object> session,UUID message,String text,ExportRequests.Prepared prepared) {
        if(!text.contains("确认")&&!text.contains("导出"))return null;
        String command=normalized(text);
        var confirm=CONFIRM.matcher(command);var export=EXPORT.matcher(command);
        boolean confirming=confirm.matches(),exporting=export.matches();
        // Quoted instruction words in an evidence query are content, not production intent.
        if(!confirming&&!exporting && command.matches("(?:请|帮我)?(?:查询|查找|搜索|检索|找到|找出).+"))return null;
        if(!confirming&&!exporting)return clarify("尚未收到无歧义的确认或导出指令。");
        if(session.get("view_revision_id")==null)return clarify("请先选择要操作的方案版本。");
        UUID project=(UUID)session.get("project_id");
        UUID revision=selectedRevision(session,confirming?confirm.group(1):export.group(1));
        if(revision==null)return clarify("指定版本不存在或版本号无效。");
        UUID actor=database.queryForObject("SELECT owner_user_id FROM projects WHERE id=?",UUID.class,project);
        try {
            if(confirming){
                var confirmed=json.valueToTree(exports.confirmFromMessage(revision,actor,"message-"+message,json.valueToTree(Map.of("explicitIntent","CONFIRM")),message));
                return new Reply("已确认指定版本。正式成片仍需另行明确导出。",List.of(Map.of("type","CONFIRMATION","id",confirmed.path("confirmationId").asText(),"revisionId",revision)));
            }
            var confirmations=json.valueToTree(exports.confirmations(revision)).path("items");
            if(confirmations.isEmpty())return clarify("这个版本尚未确认。");
            var content=json.readTree(database.queryForObject("SELECT content_json::text FROM plan_revisions WHERE id=?",String.class,revision));
            var outputs=content.path("outputs");int selected=0;
            if(export.group(2)!=null){try{selected=Integer.parseInt(export.group(2))-1;}catch(NumberFormatException invalid){return clarify("成片序号无效。");}}
            else if(outputs.size()!=1)return clarify("该版本有多条成片，请明确选择，例如“导出当前版本第1条 720p”。");
            if(selected<0||selected>=outputs.size())return clarify("指定成片不存在。");
            if(prepared==null || !revision.equals(prepared.revision()) || !outputs.get(selected).path("outputId").asText().equals(prepared.output().toString()))return clarify("查看版本或成片已变化，请重发明确指令。");
            String profile="1080".equals(export.group(3))?"FULL_HD_1080":"HD_720";
            var accepted=json.valueToTree(exports.requestFromMessage(revision,actor,"message-"+message,json.valueToTree(Map.of("outputIds",List.of(outputs.get(selected).path("outputId").asText()),"confirmationId",confirmations.get(0).path("confirmationId").asText(),"exportProfile",profile,"explicitIntent","EXPORT")),message,prepared));
            return new Reply("已接纳明确导出指令，任务固定使用指定版本。",List.of(Map.of("type","EXPORT_REQUEST","id",accepted.path("requestId").asText(),"revisionId",revision,"taskId",accepted.path("taskId").asText())));
        }catch(java.io.IOException invalid){throw new IllegalStateException(invalid);}
    }
}
