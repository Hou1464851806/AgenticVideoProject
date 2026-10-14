package com.agenticvideo.business.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Fixed-visibility evidence reads and bounded hybrid rankings. No authorization or tool transport. */
@Service
public class QuestionEvidence {
    /** Supplied by the trusted caller; this module does not create permissions or consult current heads. */
    public record Scope(UUID id,UUID projectId,JsonNode assets) {}
    public static class ReadFailure extends RuntimeException {
        public final String code,category;
        ReadFailure(String code,String category,String message){super(message);this.code=code;this.category=category;}
    }
    private static final int CHANNEL_LIMIT=50, CANDIDATE_LIMIT=100;
    private final JdbcTemplate db;
    private final SearchVectors vectors;
    private final ObjectMapper json;
    public QuestionEvidence(JdbcTemplate db,SearchVectors vectors,ObjectMapper json){this.db=db;this.vectors=vectors;this.json=json;}
    public List<JsonNode> read(Scope scope,UUID asset,Long start,Long end,int cursor) {
        String range=start==null?"":" AND ((e.source_kind='SPEECH' AND e.start_ms<? AND e.end_ms>?) OR (e.source_kind<>'SPEECH' AND e.start_ms>=? AND e.start_ms<?))";
        var args=new ArrayList<Object>(List.of(scope.id(),scope.projectId(),asset));
        if(start!=null)args.addAll(List.of(end,start,start,end));args.add(cursor);
        var rows=db.queryForList("SELECT e.* FROM question_scope_evidence q JOIN evidence_segments e ON e.id=q.evidence_id WHERE q.scope_id=? AND e.project_id=? AND e.asset_id=?"+range+" ORDER BY e.start_ms,e.source_kind,e.id LIMIT 101 OFFSET ?",args.toArray());
        return rows.stream().map(this::record).toList();
    }
    public JsonNode record(Map<String,Object> row) {
        var node=json.createObjectNode();
        node.put("id",row.get("id").toString()).put("asset_id",row.get("asset_id").toString())
            .put("analysis_version",row.get("analysis_id").toString()).put("source_kind",row.get("source_kind").toString())
            .put("text",row.get("text").toString());
        if("SPEECH".equals(row.get("source_kind"))) {
            node.put("time_kind","INTERVAL").put("start_ms",((Number)row.get("start_ms")).longValue()).put("end_ms",((Number)row.get("end_ms")).longValue());
            node.set("audio_start_ms",json.valueToTree(row.get("audio_start_ms")));node.set("audio_end_ms",json.valueToTree(row.get("audio_end_ms")));
            node.put("temporal_precision","MODEL_TIMESTAMPS");
        } else {
            node.put("time_kind","POINT").put("observed_at_ms",((Number)row.get("start_ms")).longValue()).put("temporal_precision","FRAME_OBSERVATION");
            node.set("frame_id",json.valueToTree(row.get("frame_id")));
        }
        node.set("observation",parse(row.get("observation_json")));
        return node;
    }
    public JsonNode search(Scope scope,JsonNode conditions,String hash) {
        UUID identity=UUID.randomUUID();
        int admitted=db.update("INSERT INTO question_searches(id,scope_id,conditions_hash,conditions_json) VALUES (?,?,?,?::jsonb) ON CONFLICT(scope_id,conditions_hash) DO NOTHING",identity,scope.id(),hash,conditions.toString());
        var saved=db.queryForMap("SELECT id,conditions_json,result_json FROM question_searches WHERE scope_id=? AND conditions_hash=?",scope.id(),hash);
        if(!parse(saved.get("conditions_json")).equals(conditions))throw new ReadFailure("QUERY_CONFLICT","IDENTITY_CONFLICT","检索身份冲突");
        if(saved.get("result_json")!=null)return parse(saved.get("result_json"));
        if(admitted==0)throw new ReadFailure("QUERY_UNCONFIRMED","EXECUTION_UNCONFIRMED","检索尚未核对完成，停止自动重付");
        var result=rank(scope,conditions,(UUID)saved.get("id"));
        db.update("UPDATE question_searches SET result_json=?::jsonb WHERE id=?",result.toString(),saved.get("id"));
        return result;
    }
    private JsonNode rank(Scope scope,JsonNode conditions,UUID queryId) {
        var assets=new HashSet<String>();conditions.path("asset_ids").forEach(a->assets.add(a.asText()));
        var kinds=new ArrayList<String>();conditions.path("source_kinds").forEach(k->kinds.add(k.asText()));
        String assetFilter=String.join(",",assets),kindFilter=String.join(",",kinds),query=conditions.path("query").asText();
        String authorized="""
                FROM question_scope_units q JOIN retrieval_units u ON u.id=q.unit_id
                WHERE q.scope_id=? AND u.project_id=?
                AND u.asset_id=ANY(string_to_array(?,',')::uuid[])
                AND u.source_kind=ANY(string_to_array(?,',')::text[])
                """;
        var literal=db.queryForList("SELECT u.id "+authorized+" AND position(? in u.text)>0 ORDER BY u.asset_id,u.start_ms,u.id LIMIT 51",scope.id(),scope.projectId(),assetFilter,kindFilter,query);
        var ranks=new LinkedHashMap<String,Map<String,Integer>>();
        var statuses=json.createObjectNode();boolean truncated=literal.size()>CHANNEL_LIMIT;
        var literalRanks=new LinkedHashMap<String,Integer>();
        for(int i=0;i<Math.min(CHANNEL_LIMIT,literal.size());i++)literalRanks.put(literal.get(i).get("id").toString(),i+1);
        ranks.put("LITERAL",literalRanks);statuses.putObject("LITERAL").put("status","AVAILABLE");
        var groups=new TreeMap<String,List<Map<String,Object>>>();
        var groupKinds=new HashMap<String,List<String>>();
        for(var asset:scope.assets())if(assets.contains(asset.path("id").asText())&&!asset.path("index_generation").isNull()) {
            var generation=db.queryForMap("SELECT * FROM search_index_generations WHERE id=?",UUID.fromString(asset.path("index_generation").asText()));
            var ready=kinds.stream().filter(kind->"SUCCEEDED".equals(asset.path("sources").path(kind).asText())).sorted().toList();
            if(ready.isEmpty())continue;
            String groupKey=generation.get("collection_name")+":"+String.join(",",ready);
            groups.computeIfAbsent(groupKey,key->new ArrayList<>()).add(generation);groupKinds.put(groupKey,ready);
        }
        var embedded=new HashMap<String,JsonNode>();var embeddingFailures=new HashSet<String>();
        for(String channel:List.of("DENSE","BM25")) {
            var channelRanks=new LinkedHashMap<String,Integer>();var failures=json.createArrayNode();int success=0;
            for(var grouped:groups.entrySet()) {
                var group=grouped.getValue();
                var ids=group.stream().map(g->g.get("id").toString()).toList();
                var profile=vectors.profile(group.get(0));Object filter=SearchVectors.scope(scope.projectId(),ids,groupKinds.get(grouped.getKey()));
                try {
                    Object input;
                    if("DENSE".equals(channel)) {
                        String profileKey=profile.model()+":"+profile.dimension();
                        if(embeddingFailures.contains(profileKey))throw new com.agenticvideo.business.bailian.BailianHttp.Unavailable("同一查询的提交已停止");
                        if(!embedded.containsKey(profileKey)) {
                            try {embedded.put(profileKey,vectors.embedQuestion(scope.projectId(),query,profile,queryId));}
                            catch(org.springframework.web.server.ResponseStatusException | com.agenticvideo.business.bailian.BailianHttp.Unavailable failure){embeddingFailures.add(profileKey);throw failure;}
                        }
                        input=embedded.get(profileKey);
                    }
                    else input=vectors.document(query,profile);
                    var points=vectors.query(input,"DENSE".equals(channel)?"dense":"bm25",filter,filter,CHANNEL_LIMIT+1,profile.collection());
                    success++;truncated|=points.size()>CHANNEL_LIMIT;
                    for(int i=0;i<Math.min(CHANNEL_LIMIT,points.size());i++) {
                        String unit=points.get(i).path("payload").path("unitId").asText();
                        try {
                            if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 "+authorized+" AND u.id=?)",Boolean.class,scope.id(),scope.projectId(),assetFilter,kindFilter,UUID.fromString(unit))))channelRanks.merge(unit,i+1,Math::min);
                        } catch(IllegalArgumentException invalidIdentity){/* Ignore untrusted index payload. */}
                    }
                } catch(org.springframework.web.server.ResponseStatusException | SearchVectors.MissingCollection | com.agenticvideo.business.bailian.BailianHttp.Unavailable failure) {
                    failures.addObject().put("collection",profile.collection()).put("code","CHANNEL_UNAVAILABLE");
                }
            }
            var status=statuses.putObject(channel);
            status.put("status",groups.isEmpty()?"NOT_READY":success==0?"UNAVAILABLE":failures.isEmpty()?"AVAILABLE":"DEGRADED");
            status.set("failures",failures);
            // Bound the combined channel by group rank then stable identity before fusion.
            var orderedCandidates=channelRanks.entrySet().stream()
                .sorted(Map.Entry.<String,Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey())).toList();
            truncated|=orderedCandidates.size()>CHANNEL_LIMIT;
            var boundedRanks=new LinkedHashMap<String,Integer>();
            orderedCandidates.stream().limit(CHANNEL_LIMIT).forEach(entry->boundedRanks.put(entry.getKey(),entry.getValue()));
            ranks.put(channel,boundedRanks);
        }
        var scores=new HashMap<String,Double>();
        for(var channel:ranks.values())channel.forEach((id,rank)->scores.merge(id,1.0/(60+rank),Double::sum));
        var ordered=scores.keySet().stream().sorted(Comparator.<String>comparingDouble(scores::get).reversed().thenComparing(id->id)).toList();
        truncated|=ordered.size()>CANDIDATE_LIMIT;
        var result=json.createObjectNode().put("query_id",queryId.toString()).put("candidate_limit",CANDIDATE_LIMIT).put("channel_limit",CHANNEL_LIMIT).put("candidates_truncated",truncated);
        result.set("channels",statuses);
        result.set("index_generations",json.valueToTree(groups.values().stream().flatMap(List::stream).map(g->g.get("id").toString()).distinct().sorted().toList()));
        var hits=result.putArray("hits");
        for(String unit:ordered.stream().limit(CANDIDATE_LIMIT).toList()) {
            var hit=hits.addObject().put("unit_id",unit).put("rrf_score",scores.get(unit));
            var evidence=hit.putArray("evidence_ids");
            db.queryForList("SELECT ref.evidence_id FROM unit_evidence_refs ref JOIN question_scope_evidence q ON q.evidence_id=ref.evidence_id WHERE q.scope_id=? AND ref.unit_id=? ORDER BY ref.ordinal",scope.id(),UUID.fromString(unit)).forEach(row->evidence.add(row.get("evidence_id").toString()));
            if(evidence.isEmpty())throw new ReadFailure("SNAPSHOT_INVALID","DEPENDENCY_INVALID","检索单元没有快照内的权威证据");
        }
        result.set("model_calls",json.valueToTree(db.queryForList("SELECT id,status,request_id FROM query_model_calls WHERE question_search_id=? ORDER BY created_at,id",queryId)));
        return result;
    }
    public List<JsonNode> searchRecords(Scope scope,JsonNode ranking,int cursor) {
        var records=new ArrayList<JsonNode>();
        // Flatten in rank order, deduplicate records shared by retrieval units; page the frozen identities.
        var ids=new LinkedHashSet<String>();ranking.path("hits").forEach(hit->hit.path("evidence_ids").forEach(id->ids.add(id.asText())));
        var ordered=new ArrayList<>(ids);
        for(String id:ordered.stream().skip(cursor).limit(101).toList()) {
            var rows=db.queryForList("SELECT e.* FROM question_scope_evidence q JOIN evidence_segments e ON e.id=q.evidence_id WHERE q.scope_id=? AND e.id=? AND e.project_id=?",scope.id(),UUID.fromString(id),scope.projectId());
            if(rows.isEmpty())throw new ReadFailure("SNAPSHOT_INVALID","DEPENDENCY_INVALID","排名依赖的证据已失效");
            records.add(record(rows.get(0)));
        }
        return records;
    }
    private JsonNode parse(Object value){try{return json.readTree(value.toString());}catch(java.io.IOException invalid){throw new IllegalStateException(invalid);}}
}
