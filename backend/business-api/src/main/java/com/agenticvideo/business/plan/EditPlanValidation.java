package com.agenticvideo.business.plan;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Turns a bounded evidence proposal into a complete renderable snapshot. */
@Service
public class EditPlanValidation {
    private final JdbcTemplate database;
    private final com.agenticvideo.business.analysis.SpeechTimings timings;
    public EditPlanValidation(JdbcTemplate database, com.agenticvideo.business.analysis.SpeechTimings timings) { this.database = database; this.timings = timings; }
    public record Validated(Map<String,Object> content, Set<UUID> assets, Set<UUID> evidence) {}

    public Validated validate(Map<String,Object> task, JsonNode input, JsonNode proposal) {
        fields(proposal, Set.of("title", "clips"));
        String title = text(proposal.path("title"), 120);
        JsonNode clips = proposal.path("clips");
        if (!clips.isArray() || clips.isEmpty() || clips.size() > 10) reject("单条成片须有 1 至 10 个有序片段");
        Set<UUID> scope = new HashSet<>(), usedAssets = new HashSet<>(), usedEvidence = new HashSet<>();
        for (var asset : input.path("assetIds")) scope.add(identifier(asset));
        var ordered = new ArrayList<Object>(); var cues = new ArrayList<Object>();
        var sourceRanges = new ArrayList<long[]>();
        long duration = 0;
        for (var clip : clips) {
            fields(clip, Set.of("assetId", "sourceInMs", "sourceOutMs", "evidenceIds", "reason"));
            UUID asset = identifier(clip.path("assetId"));
            var assets = database.queryForList("SELECT duration_ms,time_mapping_json FROM assets WHERE id=? AND project_id=? AND validation_status='VALID'", asset, task.get("project_id"));
            if (!scope.contains(asset) || assets.isEmpty()) reject("片段素材不在接纳时固定的有效项目范围内");
            usedAssets.add(asset);
            if (usedAssets.size() != 1) reject("当前初始讲话方案只支持从一份讲话素材选段");
            long start = milliseconds(clip.path("sourceInMs")), end = milliseconds(clip.path("sourceOutMs"));
            long sourceDuration = ((Number)assets.get(0).get("duration_ms")).longValue();
            if (start < 0 || end <= start || end > sourceDuration) reject("片段须满足 0 ≤ 起点 < 终点 ≤ 素材时长");
            for (var range : sourceRanges) if (start < range[1] && end > range[0]) reject("不能重复使用重叠的原文区间凑时长");
            sourceRanges.add(new long[]{start,end});
            JsonNode ids = clip.path("evidenceIds");
            if (!ids.isArray() || ids.isEmpty() || ids.size() > 50) reject("每个讲话片段必须包含实际核查的语音出处");
            var clipEvidence = new LinkedHashSet<UUID>();
            for (var citation : ids) {
                UUID evidence = identifier(citation);
                if (!clipEvidence.add(evidence)) reject("片段出处不能重复");
                Boolean read = database.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM conversation_tool_receipts r,
                            LATERAL jsonb_array_elements(coalesce(r.result_json->'observations','[]'::jsonb)) o
                            WHERE r.task_id=? AND r.request_json->>'name'='read_evidence_context'
                            AND o->>'evidenceId'=?)
                        """, Boolean.class, task.get("id"), evidence.toString());
                if (!Boolean.TRUE.equals(read)) reject("选段依据须先通过本轮上下文工具核查");
                var rows = database.queryForList("SELECT asset_id,start_ms,end_ms FROM evidence_segments WHERE id=? AND project_id=? AND source_kind='SPEECH'", evidence, task.get("project_id"));
                if (rows.isEmpty() || !asset.equals(rows.get(0).get("asset_id"))
                        || ((Number)rows.get(0).get("start_ms")).longValue() >= end
                        || ((Number)rows.get(0).get("end_ms")).longValue() <= start)
                    reject("语音依据须与对应片段的原素材区间重叠");
                usedEvidence.add(evidence);
            }
            // Derive all captions from stored observations, never from model-generated text.
            var observations = database.queryForList("""
                    SELECT e.* FROM evidence_segments e
                    JOIN asset_analysis_heads h ON h.asset_id=e.asset_id AND h.analysis_id=e.analysis_id
                    WHERE e.asset_id=? AND e.project_id=? AND e.source_kind='SPEECH'
                    AND e.start_ms<? AND e.end_ms>? ORDER BY e.start_ms,e.ordinal,e.id
                    """, asset, task.get("project_id"), end, start);
            if (observations.isEmpty() || observations.size() > 100) reject("片段语音证据为空或超过字幕上限");
            for (var observation : observations) {
                long observedStart = ((Number)observation.get("start_ms")).longValue();
                long observedEnd = ((Number)observation.get("end_ms")).longValue();
                String caption = observation.get("text").toString();
                if (observedStart < start || observedEnd > end) {
                    var selectedWords = new ArrayList<JsonNode>();
                    for (var word : timings.words(observation)) {
                        long wordStart=word.path("startMs").asLong(), wordEnd=word.path("endMs").asLong();
                        if (wordStart >= end || wordEnd <= start) continue;
                        if (wordStart < start || wordEnd > end || wordEnd <= wordStart) reject("剪辑边界不能截断原文中的词");
                        selectedWords.add(word);
                    }
                    if (selectedWords.isEmpty()) reject("缺少词级证据，不能截断已有语音原句");
                    var originalText = new StringBuilder();
                    for (var word : selectedWords) originalText.append(word.path("text").asText()).append(word.path("punctuation").asText());
                    caption=originalText.toString();
                    observedStart=selectedWords.get(0).path("startMs").asLong();
                    observedEnd=selectedWords.get(selectedWords.size()-1).path("endMs").asLong();
                }
                if (caption.isBlank() || caption.length() > 2000 || caption.chars().anyMatch(value -> value < 32 && value != 10)) reject("原文字幕不符合渲染文本限制");
                cues.add(Map.of("startMs", duration+observedStart-start, "endMs", duration+observedEnd-start,
                        "text", caption, "evidenceId", observation.get("id")));
                usedEvidence.add((UUID)observation.get("id"));
            }
            ordered.add(Map.of("clipId", UUID.randomUUID(), "assetId", asset, "sourceInMs", start,
                    "sourceOutMs", end, "audio", "original", "evidenceIds", clipEvidence,
                    "reason", text(clip.path("reason"), 1000)));
            duration += end-start; // source ranges and clip count make this sum bounded.
        }
        JsonNode editing = input.path("editing");
        if (duration < editing.path("minDurationMs").asLong() || duration > editing.path("maxDurationMs").asLong()) reject("选段总时长不满足用户硬时长范围");
        boolean portrait = "PORTRAIT_720".equals(editing.path("profile").asText());
        var render = Map.of("container", "mp4", "videoCodec", "h264", "audioCodec", "aac",
                "width", portrait ? 720 : 1280, "height", portrait ? 1280 : 720, "fit", "contain",
                "framesPerSecond", 30, "profile", editing.path("profile").asText(), "configVersion", "speech-cut-v1");
        var output = Map.of("outputId", UUID.randomUUID(), "title", title, "durationMs", duration,
                "render", render, "clips", ordered, "subtitles", Map.of("enabled", true, "cues", cues));
        return new Validated(Map.of("schemaVersion", "1.0", "outputs", List.of(output)), usedAssets, usedEvidence);
    }
    private void fields(JsonNode object, Set<String> allowed) {
        if (!object.isObject()) reject("提议结构必须是对象");
        object.fieldNames().forEachRemaining(field -> { if (!allowed.contains(field)) reject("提议包含不受支持的字段："+field); });
    }
    private UUID identifier(JsonNode value) {
        try { return UUID.fromString(value.asText()); }
        catch (IllegalArgumentException invalid) { reject("素材或证据标识不合法"); return null; }
    }
    private long milliseconds(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToLong()) reject("片段时间必须是整数毫秒");
        return value.asLong();
    }
    private String text(JsonNode value, int limit) {
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > limit) reject("标题或选取依据不符合文本限制");
        return value.asText();
    }
    private void reject(String message) { throw new ResponseStatusException(HttpStatus.CONFLICT, message); }
}
