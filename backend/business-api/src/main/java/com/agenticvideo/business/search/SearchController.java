package com.agenticvideo.business.search;

import com.agenticvideo.business.project.ProjectAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
class SearchController {
    private final EvidenceSearch search;
    private final JdbcTemplate database;
    private final ProjectAccess access;
    SearchController(EvidenceSearch search,JdbcTemplate database, ProjectAccess access) { this.search=search; this.database=database;this.access=access; }

    @PostMapping("/api/v1/projects/{projectId}/search")
    Map<String,Object> search(@PathVariable UUID projectId,@Valid @RequestBody Query input,Authentication account) {
        access.requireOwned(projectId,account);
        var hits=new ArrayList<Object>();
        var found=search.search(projectId,input.query(),Boolean.TRUE.equals(input.literalPhrase()),input.limit()==null?20:input.limit(),null,input.sourceKinds()==null?List.of("SPEECH"):input.sourceKinds());
        var rows=found.rows();
        for(var row:rows) {
            var hit=new LinkedHashMap<String,Object>();
            hit.put("assetId",row.get("asset_id"));hit.put("filename",row.get("filename"));hit.put("sourceSha256",row.get("sha256"));
            hit.put("analysisId",row.get("analysis_id"));hit.put("retrievalUnitId",row.get("id"));hit.put("text",row.get("text"));
            if(row.containsKey("rrfScore")){hit.put("rrfScore",row.get("rrfScore"));hit.put("channelRanks",row.get("channelRanks"));}
            hit.put("sourceKind",row.get("source_kind"));hit.put("contextAvailable",true);
            if("SPEECH".equals(row.get("source_kind"))){
                hit.put("startMs",row.get("start_ms"));hit.put("endMs",row.get("end_ms"));hit.put("temporalPrecision","MODEL_TIMESTAMPS");
                hit.put("candidateWindow",Map.of("startMs",row.get("start_ms"),"endMs",row.get("end_ms"),"purpose","RETRIEVAL"));
            }else{
                hit.put("observedAtMs",row.get("start_ms"));hit.put("temporalPrecision","FRAME_OBSERVATION");
                hit.put("candidateWindow",Map.of("observedAtMs",row.get("start_ms"),"purpose","OBSERVATION_POINT"));
            }
            hit.put("evidenceIds",database.queryForList("SELECT e.id FROM unit_evidence_refs ref JOIN evidence_segments e ON e.id=ref.evidence_id WHERE ref.unit_id=? AND e.analysis_id=? AND e.project_id=? ORDER BY ref.ordinal",UUID.class,row.get("id"),row.get("analysis_id"),projectId));
            hits.add(hit);
        }
        var coverage=new ArrayList<Object>();
        for(var row:database.queryForList("""
                SELECT a.id,a.filename,a.duration_ms,a.media_info_json,a.time_mapping_json,r.vision_profile_json,COALESCE(v.status,'NOT_STARTED') AS vision_status,s.status,COALESCE(ix.status,'NOT_STARTED') AS index_status FROM assets a
                JOIN asset_analysis_heads h ON h.asset_id=a.id
                JOIN asset_analysis_revisions r ON r.id=h.analysis_id AND r.source_sha256=a.sha256
                JOIN analysis_stages s ON s.analysis_id=h.analysis_id AND s.stage_key='ASR'
                LEFT JOIN analysis_stages ix ON ix.analysis_id=h.analysis_id AND ix.stage_key='INDEX'
                LEFT JOIN analysis_stages v ON v.analysis_id=h.analysis_id AND v.stage_key='VISION'
                WHERE a.project_id=? AND a.validation_status='VALID' ORDER BY a.id
                """,projectId)) {
            var ranges=new ArrayList<Object>();
            if("SUCCEEDED".equals(row.get("status"))) {
                var media=parse(row.get("media_info_json"));var mapping=parse(row.get("time_mapping_json"));
                long offset=mapping.path("audioStartMs").asLong()-mapping.path("formatStartMs").asLong();
                for(var stream:media.path("streams")) if("audio".equals(stream.path("codec_type").asText())) {
                    String duration=stream.path("duration").asText();
                    if(duration.matches("[0-9]+(?:\\.[0-9]+)?")) {
                        long length=new java.math.BigDecimal(duration).multiply(java.math.BigDecimal.valueOf(1000)).setScale(0,java.math.RoundingMode.HALF_UP).longValueExact();
                        long start=Math.max(0,offset),end=Math.min(((Number)row.get("duration_ms")).longValue(),offset+length);
                        if(end>start)ranges.add(Map.of("startMs",start,"endMs",end));
                    }
                    break;
                }
            }
            if(input.sourceKinds()==null||input.sourceKinds().contains("SPEECH"))coverage.add(Map.of("assetId",row.get("id"),"filename",row.get("filename"),"sourceKind","SPEECH","status",row.get("status"),"indexStatus",row.get("index_status"),"startMs",0,"endMs",row.get("duration_ms"),"processedRanges",ranges));
            if(input.sourceKinds()!=null)for(String kind:input.sourceKinds())if(!"SPEECH".equals(kind))coverage.add(Map.of("assetId",row.get("id"),"filename",row.get("filename"),"sourceKind",kind,"status",row.get("vision_status"),"indexStatus",row.get("index_status"),"processedRanges",List.of(),"sampling",row.get("vision_profile_json")==null?Map.of("kind","SPARSE_FRAMES","complete",false):parse(row.get("vision_profile_json"))));
        }
        return Map.of("data",Map.of("hits",hits,"channels",found.channels(),"semanticAvailable",found.semanticAvailable(),"rankings",found.rankings(),"coverage",Map.of("assets",coverage)),"requestId",UUID.randomUUID());
    }
    @GetMapping("/api/v1/evidence/{evidenceId}/context")
    Map<String,Object> context(@PathVariable UUID evidenceId,@RequestParam(defaultValue="5000") long boundedWindowMs,Authentication account) {
        if(boundedWindowMs<0 || boundedWindowMs>30000) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"上下文范围必须在 0 至 30000 毫秒内");
        var targets=database.queryForList("""
                SELECT e.*,a.duration_ms FROM evidence_segments e
                JOIN assets a ON a.id=e.asset_id AND a.project_id=e.project_id
                JOIN projects p ON p.id=e.project_id AND p.owner_user_id=?
                JOIN asset_analysis_heads h ON h.asset_id=a.id AND h.analysis_id=e.analysis_id
                JOIN asset_analysis_revisions r ON r.id=h.analysis_id AND r.source_sha256=a.sha256
                WHERE e.id=? AND a.validation_status='VALID'
                """,access.currentUserId(account),evidenceId);
        if(targets.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"证据不存在或不在可查范围");
        var target=targets.get(0);
        long start=Math.max(0,((Number)target.get("start_ms")).longValue()-boundedWindowMs);
        long end=Math.min(((Number)target.get("duration_ms")).longValue(),((Number)target.get("end_ms")).longValue()+boundedWindowMs);
        int ordinal=((Number)target.get("ordinal")).intValue();
        var observations=new ArrayList<Object>();
        for(var row:database.queryForList("""
                SELECT id,text,start_ms,end_ms,source_kind FROM evidence_segments
                WHERE project_id=? AND asset_id=? AND analysis_id=? AND source_kind=?
                AND ordinal BETWEEN ? AND ? AND end_ms>=? AND start_ms<=? ORDER BY ordinal
                """,target.get("project_id"),target.get("asset_id"),target.get("analysis_id"),target.get("source_kind"),ordinal-5,ordinal+5,start,end)) {
            if("SPEECH".equals(row.get("source_kind")))observations.add(Map.of("id",row.get("id"),"text",row.get("text"),"startMs",row.get("start_ms"),"endMs",row.get("end_ms"),"sourceKind",row.get("source_kind"),"temporalPrecision","MODEL_TIMESTAMPS"));
            else observations.add(Map.of("id",row.get("id"),"text",row.get("text"),"observedAtMs",row.get("start_ms"),"sourceKind",row.get("source_kind"),"temporalPrecision","FRAME_OBSERVATION"));
        }
        return Map.of("data",Map.of("targetEvidenceId",evidenceId,"assetId",target.get("asset_id"),"analysisId",target.get("analysis_id"),"observations",observations,
                "window",Map.of("startMs",start,"endMs",end,"purpose","CONTEXT"),"maxNeighboursPerSide",5),"requestId",UUID.randomUUID());
    }
    private com.fasterxml.jackson.databind.JsonNode parse(Object value) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().readTree(value.toString()); }
        catch(java.io.IOException invalid) { throw new IllegalStateException(invalid); }
    }
    record Query(@NotBlank @Size(max=256) String query,Boolean literalPhrase,@Min(1) @Max(50) Integer limit,@Size(min=1,max=3) List<@Pattern(regexp="SPEECH|VISUAL|VISIBLE_TEXT") String> sourceKinds) {}
}
