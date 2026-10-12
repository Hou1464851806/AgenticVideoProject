package com.agenticvideo.business.search;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Callers supply an authorized project and optional fixed asset scope; all recalls share it. */
@Service
public class EvidenceSearch {
    private final JdbcTemplate database;private final SearchVectors vectors;
    EvidenceSearch(JdbcTemplate database,SearchVectors vectors){this.database=database;this.vectors=vectors;}
    public record Result(List<Map<String,Object>> rows,List<String> channels,boolean semanticAvailable,Map<String,List<String>> rankings,List<String> generations) {}
    public Result search(UUID project,String query,boolean literal,int limit,List<UUID> assetScope) {
        return search(project,query,literal,limit,assetScope,List.of("SPEECH"));
    }
    public Result search(UUID project,String query,boolean literal,int limit,List<UUID> assetScope,List<String> sourceKinds) {
        String kinds=String.join(",",sourceKinds);
        String scope=assetScope==null?null:assetScope.stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(","));
        String valid="""
                FROM retrieval_units u JOIN assets a ON a.id=u.asset_id AND a.project_id=u.project_id
                JOIN asset_analysis_heads h ON h.asset_id=a.id AND h.analysis_id=u.analysis_id
                JOIN asset_analysis_revisions r ON r.id=h.analysis_id AND r.source_sha256=a.sha256
                WHERE u.project_id=? AND a.validation_status='VALID' AND u.source_kind=ANY(string_to_array(?::text,',')::text[])
                AND (?::text IS NULL OR u.asset_id=ANY(string_to_array(?::text,',')::uuid[]))
                """;
        var matches=database.queryForList("SELECT u.*,a.filename,a.sha256 "+valid+" AND position(? in u.text)>0 ORDER BY a.id,u.start_ms,u.id LIMIT ?",project,kinds,scope,scope,query,literal?limit:50);
        var generations=database.queryForList("""
                SELECT g.* FROM search_index_heads sh JOIN search_index_generations g ON g.id=sh.generation_id
                JOIN assets a ON a.id=g.asset_id AND a.project_id=g.project_id
                JOIN asset_analysis_heads h ON h.asset_id=a.id AND h.analysis_id=g.analysis_id
                JOIN asset_analysis_revisions r ON r.id=h.analysis_id AND r.source_sha256=a.sha256
                WHERE g.project_id=? AND g.status='PUBLISHED' AND a.validation_status='VALID'
                """,project);
        boolean available=!generations.isEmpty();
        var rankings=new LinkedHashMap<String,List<String>>();
        if(literal||!available){rankings.put("LITERAL",matches.stream().map(row->row.get("id").toString()).toList());return new Result(matches,List.of("LITERAL"),available,rankings,List.of());}
        var scopedIds=generations.stream().filter(row->assetScope==null||assetScope.contains(row.get("asset_id"))).map(row->row.get("id").toString()).toList();
        if(scopedIds.isEmpty())return new Result(matches,List.of("LITERAL"),false,Map.of(),List.of());
        var groups=generations.stream().collect(java.util.stream.Collectors.groupingBy(row->row.get("collection_name").toString()));
        var channelRanks=new LinkedHashMap<String,Map<String,Integer>>();
        channelRanks.put("DENSE",new HashMap<>());channelRanks.put("BM25",new HashMap<>());
        for(var group:groups.values()) {
            var ids=group.stream().filter(row->scopedIds.contains(row.get("id").toString())).map(row->row.get("id").toString()).toList();
            if(ids.isEmpty())continue;
            var profile=vectors.profile(group.get(0));
            Object filter=SearchVectors.scope(project,ids,sourceKinds),corpus=SearchVectors.scope(project,group.stream().map(row->row.get("id").toString()).toList());
            var dense=vectors.query(vectors.embed(query,profile),"dense",filter,corpus,50,profile.collection());
            var bm25=vectors.query(vectors.document(query,profile),"bm25",filter,corpus,50,profile.collection());
            for(var channel:Map.of("DENSE",dense,"BM25",bm25).entrySet())for(int i=0;i<channel.getValue().size();i++)
                channelRanks.get(channel.getKey()).merge(channel.getValue().get(i).path("payload").path("unitId").asText(),i+1,Math::min);
        }
        var literalRanks=new HashMap<String,Integer>();for(int i=0;i<matches.size();i++)literalRanks.put(matches.get(i).get("id").toString(),i+1);
        channelRanks.put("LITERAL",literalRanks);
        for(var channel:channelRanks.entrySet())rankings.put(channel.getKey(),channel.getValue().keySet().stream().sorted(Comparator.<String>comparingInt(channel.getValue()::get).thenComparing(id->id)).toList());
        var scores=new HashMap<String,Double>();
        for(var ranks:channelRanks.values())for(var rank:ranks.entrySet())scores.merge(rank.getKey(),1.0/(60+rank.getValue()),Double::sum);
        var ordered=scores.keySet().stream().sorted(Comparator.<String>comparingDouble(scores::get).reversed().thenComparing(id->id)).limit(limit).toList();
        var rows=new ArrayList<Map<String,Object>>();
        for(var id:ordered) {
            var found=database.queryForList("SELECT u.*,a.filename,a.sha256 "+valid+" AND u.id=?",project,kinds,scope,scope,UUID.fromString(id));
            if(!found.isEmpty()){var row=new LinkedHashMap<>(found.get(0));row.put("rrfScore",scores.get(id));row.put("channelRanks",channelRanks.entrySet().stream().filter(channel->channel.getValue().containsKey(id)).collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,channel->channel.getValue().get(id))));rows.add(row);}
        }
        rankings.put("RRF",ordered);
        return new Result(rows,List.of("DENSE","BM25","LITERAL"),true,rankings,scopedIds);
    }
}
