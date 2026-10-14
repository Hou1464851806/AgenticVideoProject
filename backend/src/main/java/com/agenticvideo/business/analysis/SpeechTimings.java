package com.agenticvideo.business.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Maps persisted provider word observations onto the original media timeline. */
@Service
public class SpeechTimings {
    private final ObjectMapper json;
    public SpeechTimings(ObjectMapper json) { this.json = json; }
    public ArrayNode words(Map<String,Object> observation) {
        var mapped = json.createArrayNode();
        long start = ((Number)observation.get("start_ms")).longValue();
        long end = ((Number)observation.get("end_ms")).longValue();
        long offset = start - ((Number)observation.get("audio_start_ms")).longValue();
        try {
            for (var word : json.readTree(observation.get("observation_json").toString()).path("words")) {
                if (!(word.deepCopy() instanceof ObjectNode object)) continue;
                long audioStart = word.path("begin_time").asLong(), audioEnd = word.path("end_time").asLong();
                object.put("audioStartMs", audioStart).put("audioEndMs", audioEnd)
                        .put("startMs", Math.max(0, audioStart+offset)).put("endMs", Math.min(end,audioEnd+offset));
                mapped.add(object);
            }
        } catch (java.io.IOException invalid) { throw new IllegalStateException(invalid); }
        return mapped;
    }
}
