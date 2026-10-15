package com.agenticvideo.business.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.stereotype.Service;

/** Maps persisted provider word observations onto the original media timeline. */
@Service
public class SpeechTimings {
    private final ObjectMapper json;
    public SpeechTimings(ObjectMapper json) { this.json = json; }
    public ArrayNode words(Map<String,Object> observation) {
        long offset=((Number)observation.get("start_ms")).longValue()-((Number)observation.get("audio_start_ms")).longValue();
        return words(observation,offset);
    }
    public ArrayNode words(Map<String,Object> observation,long offset) {
        var mapped = json.createArrayNode();
        long end = ((Number)observation.get("end_ms")).longValue();
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
    public record Cut(String field,long actual,List<Long> nearbySafeBoundaries) {}
    public record Selection(String text,long startMs,long endMs,List<Cut> cuts,boolean sentenceCut) {}

    /** Selects original whole words, preserving source coordinates until the caller maps the output. */
    public Selection select(Map<String,Object> observation,long start,long end,long offset) {
        long sentenceStart=((Number)observation.get("start_ms")).longValue();
        long sentenceEnd=((Number)observation.get("end_ms")).longValue();
        if(start<=((Number)observation.get("audio_start_ms")).longValue()+offset && end>=((Number)observation.get("audio_end_ms")).longValue()+offset)
            return new Selection(observation.get("text").toString(),sentenceStart,sentenceEnd,List.of(),false);
        var words=words(observation,offset);
        if(words.isEmpty())return new Selection("",sentenceStart,sentenceEnd,List.of(),true);
        var cuts=new ArrayList<Cut>();var selected=new ArrayList<JsonNode>();
        for(var word:words) {
            long a=word.path("startMs").asLong(),b=word.path("endMs").asLong();
            if(a>=end || b<=start)continue;
            if(a<start)cuts.add(new Cut("source_in_ms",start,List.of(a,b)));
            if(b>end)cuts.add(new Cut("source_out_ms",end,List.of(a,b)));
            if(a>=start && b<=end)selected.add(word);
        }
        var text=new StringBuilder();selected.forEach(w->text.append(w.path("text").asText()).append(w.path("punctuation").asText()));
        return new Selection(text.toString(),selected.isEmpty()?start:selected.get(0).path("startMs").asLong(),
                selected.isEmpty()?start:selected.get(selected.size()-1).path("endMs").asLong(),cuts,false);
    }

    public boolean safeBoundary(List<Map<String,Object>> observations,long time,long offset) {
        for(var row:observations) {
            if(((Number)row.get("start_ms")).longValue()>=time || ((Number)row.get("end_ms")).longValue()<=time)continue;
            var words=words(row,offset);
            if(words.isEmpty())return false;
            for(var word:words)if(word.path("startMs").asLong()<time && word.path("endMs").asLong()>time)return false;
        }
        return true;
    }

}
