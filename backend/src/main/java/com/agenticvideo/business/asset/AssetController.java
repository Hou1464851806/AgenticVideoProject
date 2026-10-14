package com.agenticvideo.business.asset;

import com.agenticvideo.business.project.ProjectAccess;
import com.agenticvideo.business.upload.MultipartStorage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@RestController
@RequestMapping("/api/v1/assets")
class AssetController {
    private final JdbcTemplate database;
    private final ProjectAccess access;
    private final MultipartStorage storage;
    private final com.agenticvideo.business.analysis.SpeechAnalysis speech;
    AssetController(JdbcTemplate database, ProjectAccess access, MultipartStorage storage,com.agenticvideo.business.analysis.SpeechAnalysis speech) { this.database=database;this.access=access;this.storage=storage;this.speech=speech; }
    private Map<String,Object> owned(UUID id, Authentication account) {
        var rows=database.queryForList("SELECT * FROM assets WHERE id=?",id);
        if(rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND,"素材不存在或无权访问");
        var asset=rows.get(0);
        access.requireOwned((UUID)asset.get("project_id"),account);
        return asset;
    }
    @GetMapping("/{id}")
    Map<String,Object> get(@PathVariable UUID id, Authentication account) {
        var asset=owned(id,account);
        var view=new LinkedHashMap<String,Object>();
        view.put("id",id);view.put("filename",asset.get("filename"));view.put("validationStatus",asset.get("validation_status"));
        view.put("byteSize",asset.get("byte_size"));view.put("durationMs",asset.get("duration_ms"));
        view.put("analysis",speech.stages(id));
        for(var field:Map.of("mediaInfo","media_info_json","timeMapping","time_mapping_json","error","error_json").entrySet()) {
            Object value=asset.get(field.getValue());
            if(value!=null) {
                try { view.put(field.getKey(),new com.fasterxml.jackson.databind.ObjectMapper().readTree(value.toString())); }
                catch(java.io.IOException invalid) { throw new IllegalStateException(invalid); }
            }
        }
        return Map.of("data",view,"requestId",UUID.randomUUID());
    }
    @PostMapping("/{id}/playback-access")
    Map<String,Object> playback(@PathVariable UUID id, Authentication account) {
        var asset=owned(id,account);
        if(!"VALID".equals(asset.get("validation_status"))) throw new ResponseStatusException(CONFLICT,"素材尚未通过媒体验证");
        return Map.of("data",Map.of("assetId",id,"filename",asset.get("filename"),"durationMs",asset.get("duration_ms"),
                "url",storage.playbackUrl(asset.get("object_key").toString()),"expiresAt",java.time.Instant.now().plusSeconds(300).toString()),"requestId",UUID.randomUUID());
    }
    @GetMapping("/{id}/evidence")
    Map<String,Object> evidence(@PathVariable UUID id,Authentication account) {
        owned(id,account);
        return Map.of("data",speech.evidence(id),"requestId",UUID.randomUUID());
    }
}
