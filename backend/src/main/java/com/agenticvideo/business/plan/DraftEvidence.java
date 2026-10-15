package com.agenticvideo.business.plan;

import com.agenticvideo.business.search.QuestionEvidence.Scope;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Loads only fixed-scope observations and server-owned range receipts for draft rules. */
@Service
public class DraftEvidence {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public DraftEvidence(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
    public record Range(String asset,long start,long end) {}
    public record Reads(Set<String> evidenceIds,List<Range> completeRanges,List<String> callIds) {
        public boolean covers(String asset,long start,long end) {
            long through=start;
            for(var range:completeRanges.stream().filter(r->r.asset().equals(asset)).sorted(Comparator.comparingLong(Range::start)).toList()) {
                if(range.start()>through)break;
                if(range.end()>through)through=range.end();
                if(through>=end)return true;
            }
            return false;
        }
    }
    public Map<String,Map<String,Object>> observations(Scope scope) {
        var result=new HashMap<String,Map<String,Object>>();
        for(var row:db.queryForList("SELECT e.* FROM question_scope_evidence q JOIN evidence_segments e ON e.id=q.evidence_id WHERE q.scope_id=? AND e.project_id=? ORDER BY e.start_ms,e.ordinal,e.id",scope.id(),scope.projectId()))
            result.put(row.get("id").toString(),row);
        return result;
    }
    public Reads reads(Scope scope) {
        var groups=new LinkedHashMap<Range,Map<Integer,JsonNode>>();
        var ids=new HashSet<String>();var calls=new ArrayList<String>();
        for(var row:db.queryForList("SELECT call_id,request_json,result_json FROM question_tool_receipts WHERE scope_id=? AND request_json->>'tool_name'='read_video_range' AND result_json->>'status'='READY' ORDER BY call_id",scope.id())) {
            var args=parse(row.get("request_json")).path("arguments");var result=parse(row.get("result_json"));
            result.path("records").forEach(record->ids.add(record.path("id").asText()));calls.add(row.get("call_id").toString());
            var range=new Range(args.path("asset_id").asText(),args.path("start_ms").asLong(),args.path("end_ms").asLong());
            groups.computeIfAbsent(range,ignored->new HashMap<>()).put(args.path("cursor").asInt(0),result);
        }
        var complete=new ArrayList<Range>();
        groups.forEach((range,pages)->{
            int cursor=0;
            while(pages.containsKey(cursor)) {
                var page=pages.get(cursor);var next=page.path("data").path("next_cursor");
                if(next.isNull()) {if(page.path("coverage").path("page_complete").asBoolean())complete.add(range);break;}
                cursor=next.asInt(); // Successful server pages always advance by the number of returned records.
            }
        });
        return new Reads(ids,complete,calls);
    }
    private JsonNode parse(Object value){try{return json.readTree(value.toString());}catch(java.io.IOException invalid){throw new IllegalStateException(invalid);}}
}
