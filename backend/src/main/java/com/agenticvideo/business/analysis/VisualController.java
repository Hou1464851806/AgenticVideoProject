package com.agenticvideo.business.analysis;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import com.agenticvideo.business.project.ProjectAccess;
import com.agenticvideo.business.upload.MultipartStorage;
import static org.springframework.http.HttpStatus.*;

@RestController
class VisualController {
    private final VisualAnalysis vision;private final JdbcTemplate db;private final ProjectAccess access;private final MultipartStorage storage;
    VisualController(VisualAnalysis vision,JdbcTemplate db,ProjectAccess access,MultipartStorage storage){this.vision=vision;this.db=db;this.access=access;this.storage=storage;}
    @PostMapping("/api/v1/assets/{asset}/vision-analysis")
    @ResponseStatus(ACCEPTED)
    Object start(@PathVariable UUID asset,Authentication account){
        var rows=db.queryForList("SELECT project_id FROM assets WHERE id=?",asset);
        if(rows.isEmpty())throw new ResponseStatusException(NOT_FOUND,"素材不存在或无权访问");access.requireOwned((UUID)rows.get(0).get("project_id"),account);
        return Map.of("data",vision.enqueue(asset),"requestId",UUID.randomUUID());
    }
    @PostMapping("/api/v1/evidence/{evidence}/frame-access")
    Object frame(@PathVariable UUID evidence,Authentication account){
        var rows=db.queryForList("""
                SELECT f.id,f.object_key,f.observed_at_ms,e.asset_id,e.project_id FROM evidence_segments e
                JOIN vision_frames f ON f.id=e.frame_id AND f.analysis_id=e.analysis_id
                JOIN assets a ON a.id=e.asset_id AND a.project_id=e.project_id AND a.validation_status='VALID'
                JOIN asset_analysis_heads h ON h.asset_id=a.id AND h.analysis_id=e.analysis_id
                JOIN asset_analysis_revisions r ON r.id=h.analysis_id AND r.source_sha256=a.sha256
                WHERE e.id=?
                """,evidence);
        if(rows.isEmpty())throw new ResponseStatusException(NOT_FOUND,"帧证据不存在或无权访问");var row=rows.get(0);access.requireOwned((UUID)row.get("project_id"),account);
        return Map.of("data",Map.of("frameId",row.get("id"),"assetId",row.get("asset_id"),"observedAtMs",row.get("observed_at_ms"),"url",storage.playbackUrl(row.get("object_key").toString()),"expiresAt",java.time.Instant.now().plusSeconds(300)),"requestId",UUID.randomUUID());
    }
}
