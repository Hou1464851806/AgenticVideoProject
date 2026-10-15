package com.agenticvideo.business.tools;

import com.agenticvideo.business.search.QuestionEvidence.Scope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/** Owns trusted, immutable question visibility, independent of conversations and task leases. */
@Service
public class QuestionScopes {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final TransactionTemplate snapshot;
    public QuestionScopes(JdbcTemplate db,ObjectMapper json,PlatformTransactionManager transactions) {
        this.db=db;this.json=json;this.snapshot=new TransactionTemplate(transactions);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public Scope create(UUID project,List<UUID> requested) {
        return snapshot.execute(tx->{
            if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM projects WHERE id=?)",Boolean.class,project)))
                throw new ResponseStatusException(NOT_FOUND,"项目不存在");
            if(requested!=null && (requested.isEmpty() || new HashSet<>(requested).size()!=requested.size()))
                throw new ResponseStatusException(BAD_REQUEST,"素材范围不能为空或重复");
            var assets=json.createArrayNode();
            for(var row:db.queryForList("""
                    SELECT a.id,a.filename,a.sha256,a.validation_status,a.duration_ms,a.media_info_json,a.time_mapping_json,
                    r.id AS analysis_id,r.vision_profile_json,g.id AS generation_id,
                    COALESCE(s.status,'NOT_STARTED') AS speech_status,COALESCE(v.status,'NOT_STARTED') AS vision_status
                    FROM assets a
                    LEFT JOIN asset_analysis_heads h ON h.asset_id=a.id
                    LEFT JOIN asset_analysis_revisions r ON r.id=h.analysis_id AND r.source_sha256=a.sha256
                    LEFT JOIN analysis_stages s ON s.analysis_id=r.id AND s.stage_key='ASR'
                    LEFT JOIN analysis_stages v ON v.analysis_id=r.id AND v.stage_key='VISION'
                    LEFT JOIN search_index_heads sh ON sh.asset_id=a.id
                    LEFT JOIN search_index_generations g ON g.id=sh.generation_id AND g.analysis_id=r.id AND g.status='PUBLISHED'
                    WHERE a.project_id=? ORDER BY a.id
                    """,project)) {
                if(requested!=null&&!requested.contains(row.get("id")))continue;
                var asset=assets.addObject();
                asset.put("id",row.get("id").toString()).put("filename",row.get("filename").toString())
                    .put("source_sha256",row.get("sha256").toString()).put("validation_status",row.get("validation_status").toString());
                asset.set("duration_ms",json.valueToTree(row.get("duration_ms")));
                asset.put("has_audio",row.get("time_mapping_json")!=null && parse(row.get("time_mapping_json")).path("hasAudio").asBoolean());
                if(row.get("time_mapping_json")!=null){var mapping=parse(row.get("time_mapping_json"));asset.put("audio_offset_ms",mapping.path("audioStartMs").asLong()-mapping.path("formatStartMs").asLong());}
                asset.set("analysis_version",json.valueToTree(row.get("analysis_id")));
                asset.set("index_generation",json.valueToTree(row.get("generation_id")));
                boolean valid="VALID".equals(row.get("validation_status"));
                var sources=asset.putObject("sources");
                sources.put("SPEECH",valid?row.get("speech_status").toString():"NOT_READY");
                sources.put("VISUAL",valid?row.get("vision_status").toString():"NOT_READY");
                sources.put("VISIBLE_TEXT",sources.path("VISUAL").asText());
                var ready=asset.putArray("source_kinds");
                sources.fields().forEachRemaining(entry->{if("SUCCEEDED".equals(entry.getValue().asText()))ready.add(entry.getKey());});
                var coverage=asset.putObject("coverage");
                var audioRanges=audioRanges(row);coverage.set("audio_ranges",audioRanges);
                coverage.set("speech_processed_ranges","SUCCEEDED".equals(row.get("speech_status"))?audioRanges:json.createArrayNode());
                coverage.set("sampling",row.get("vision_profile_json")==null?json.valueToTree(Map.of("kind","SPARSE_FRAMES","complete",false)):parse(row.get("vision_profile_json")));
                coverage.put("visual_complete",false);
                if(row.get("analysis_id")!=null)coverage.set("sampled_at_ms",json.valueToTree(db.queryForList("SELECT observed_at_ms FROM vision_frames WHERE analysis_id=? AND inspection_id IS NULL ORDER BY ordinal",Long.class,row.get("analysis_id"))));
                else coverage.putArray("sampled_at_ms");
            }
            if(requested!=null&&assets.size()!=requested.size())throw new ResponseStatusException(FORBIDDEN,"素材不属于指定项目");
            UUID id=UUID.randomUUID();
            db.update("INSERT INTO question_scopes(id,project_id,assets_json) VALUES (?,?,?::jsonb)",id,project,assets.toString());
            for(var asset:assets) for(var kind:asset.path("source_kinds")) {
                db.update("INSERT INTO question_scope_evidence SELECT ?,id FROM evidence_segments WHERE project_id=? AND asset_id=? AND analysis_id=? AND source_kind=?",id,project,UUID.fromString(asset.path("id").asText()),UUID.fromString(asset.path("analysis_version").asText()),kind.asText());
                db.update("INSERT INTO question_scope_units SELECT ?,id FROM retrieval_units WHERE project_id=? AND asset_id=? AND analysis_id=? AND source_kind=?",id,project,UUID.fromString(asset.path("id").asText()),UUID.fromString(asset.path("analysis_version").asText()),kind.asText());
            }
            return new Scope(id,project,assets);
        });
    }
    private JsonNode audioRanges(Map<String,Object> row) {
        var ranges=json.createArrayNode();
        if(row.get("media_info_json")==null||row.get("time_mapping_json")==null)return ranges;
        var mapping=parse(row.get("time_mapping_json"));long offset=mapping.path("audioStartMs").asLong()-mapping.path("formatStartMs").asLong();
        for(var stream:parse(row.get("media_info_json")).path("streams"))if("audio".equals(stream.path("codec_type").asText())) {
            String duration=stream.path("duration").asText();
            if(duration.matches("[0-9]+(?:\\.[0-9]+)?")&&row.get("duration_ms")!=null) {
                long length=new java.math.BigDecimal(duration).multiply(java.math.BigDecimal.valueOf(1000)).setScale(0,java.math.RoundingMode.HALF_UP).longValueExact();
                long start=Math.max(0,offset),end=Math.min(((Number)row.get("duration_ms")).longValue(),offset+length);
                if(end>start)ranges.addObject().put("start_ms",start).put("end_ms",end);
            }
            break;
        }
        return ranges;
    }
    public Scope get(UUID id) {
        var rows=db.queryForList("SELECT project_id,assets_json FROM question_scopes WHERE id=?",id);
        if(rows.isEmpty())throw new ResponseStatusException(NOT_FOUND,"问答范围不存在");
        return new Scope(id,(UUID)rows.get(0).get("project_id"),parse(rows.get(0).get("assets_json")));
    }
    public void requireDependencies(Scope scope) {
        for(var asset:scope.assets()) {
            UUID id=UUID.fromString(asset.path("id").asText());
            boolean valid=Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM assets WHERE id=? AND project_id=? AND sha256=? AND validation_status=?)",Boolean.class,id,scope.projectId(),asset.path("source_sha256").asText(),asset.path("validation_status").asText()));
            // VALIDATING may become VALID, but the frozen unprobed metadata remains usable.
            if(!valid&& !"VALID".equals(asset.path("validation_status").asText()))
                valid=Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM assets WHERE id=? AND project_id=? AND sha256=? AND validation_status IN ('VALIDATING','VALID'))",Boolean.class,id,scope.projectId(),asset.path("source_sha256").asText()));
            if(!asset.path("analysis_version").isNull())valid &= Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM asset_analysis_revisions WHERE id=? AND asset_id=? AND source_sha256=?)",Boolean.class,UUID.fromString(asset.path("analysis_version").asText()),id,asset.path("source_sha256").asText()));
            if(!asset.path("index_generation").isNull())valid &= Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM search_index_generations WHERE id=? AND asset_id=? AND analysis_id=? AND status='PUBLISHED')",Boolean.class,UUID.fromString(asset.path("index_generation").asText()),id,UUID.fromString(asset.path("analysis_version").asText())));
            if(!valid)throw new ToolFailure("SNAPSHOT_INVALID","DEPENDENCY_INVALID","问答快照的必要依赖已失效");
        }
        if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM question_scope_evidence q LEFT JOIN evidence_segments e ON e.id=q.evidence_id WHERE q.scope_id=? AND e.id IS NULL) OR EXISTS(SELECT 1 FROM question_scope_units q LEFT JOIN retrieval_units u ON u.id=q.unit_id WHERE q.scope_id=? AND u.id IS NULL)",Boolean.class,scope.id(),scope.id())))
            throw new ToolFailure("SNAPSHOT_INVALID","DEPENDENCY_INVALID","问答证据已失效");
    }
    private JsonNode parse(Object value){try{return json.readTree(value.toString());}catch(java.io.IOException invalid){throw new IllegalStateException(invalid);}}
}
