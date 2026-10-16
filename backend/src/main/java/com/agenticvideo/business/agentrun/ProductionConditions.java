package com.agenticvideo.business.agentrun;

import com.agenticvideo.business.plan.EditDraftContracts;
import com.agenticvideo.business.search.QuestionEvidence.Scope;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import static com.agenticvideo.business.agentrun.AgentRuns.conflict;

/** The browser's authoritative condition projection and user-message provenance. */
final class ProductionConditions {
    record Values(String requirements,String sources) {}
    private ProductionConditions() {}
    static Values defaults(ObjectMapper json,Scope scope) {
        var r=json.createObjectNode().put("topic","").put("profile","LANDSCAPE_720").put("subtitles_enabled",true).put("require_complete_subtitles",false);
        var ids=r.putArray("asset_ids");scope.assets().forEach(a->ids.add(a.path("id").asText()));
        r.putArray("outputs").addObject().put("output_key","out-1").putNull("duration_min_ms").putNull("duration_max_ms");r.putArray("forbidden_content");
        var sources=json.createObjectNode();
        for(String field:List.of("profile","subtitles_enabled","require_complete_subtitles"))sources.putObject(field).put("kind","DEFAULT").set("value",r.get(field));
        sources.putObject("outputs").put("kind","DEFAULT").put("value",1);
        sources.putObject("asset_ids").put("kind","SCOPE").put("scope_id",scope.id().toString());
        for(String field:List.of("duration_min_ms","duration_max_ms"))sources.putObject("outputs.out-1."+field).put("kind","DEFAULT").putNull("value");
        return new Values(r.toString(),sources.toString());
    }
    static Values merge(ObjectMapper json,JsonNode current,JsonNode provenance,Map<String,JsonNode> patch,String message,String text) {
        var r=(ObjectNode)current.deepCopy();var sources=(ObjectNode)provenance.deepCopy();
        var source=json.createObjectNode().put("kind","USER").put("message_id",message).put("text",text);
        Set<String> fields=Set.of("topic","asset_ids","forbidden_content","profile","subtitles_enabled","require_complete_subtitles","output_count","duration_min_ms","duration_max_ms","output_durations");
        patch.forEach((key,value)->{if(!fields.contains(key)||value.isNull())throw conflict("制作条件字段无效: "+key);});
        for(String key:List.of("topic","asset_ids","forbidden_content","profile","subtitles_enabled","require_complete_subtitles"))if(patch.containsKey(key)){r.set(key,patch.get(key));sources.set(key,source);}
        var outputs=(ArrayNode)r.path("outputs");
        if(patch.containsKey("output_count")) {
            var count=patch.get("output_count");if(!count.isIntegralNumber()||count.asLong()<1||count.asLong()>10)throw conflict("output_count 必须为 1 至 10");
            int next=0;for(var output:outputs)next=Math.max(next,Integer.parseInt(output.path("output_key").asText().substring(4)));
            while(outputs.size()>count.asInt())outputs.remove(outputs.size()-1);
            while(outputs.size()<count.asInt()) {
                String key="out-"+(++next);outputs.addObject().put("output_key",key).putNull("duration_min_ms").putNull("duration_max_ms");
                for(String field:List.of("duration_min_ms","duration_max_ms"))sources.putObject("outputs."+key+"."+field).put("kind","DEFAULT").putNull("value");
            }
            sources.set("outputs",source);
        }
        for(String field:List.of("duration_min_ms","duration_max_ms"))if(patch.containsKey(field))for(var output:outputs) {
            ((ObjectNode)output).set(field,patch.get(field));sources.set("outputs."+output.path("output_key").asText()+"."+field,source);
        }
        if(patch.containsKey("output_durations")) {
            var updates=patch.get("output_durations");if(!updates.isArray()||updates.size()>10)throw conflict("output_durations 必须为成片条件列表");
            for(var update:updates) {
                var condition=EditDraftContracts.read(update,EditDraftContracts.OutputCondition.class);
                var output=java.util.stream.StreamSupport.stream(outputs.spliterator(),false).filter(o->o.path("output_key").asText().equals(condition.output_key())).findFirst().orElseThrow(()->conflict("output_key 不属于当前制作"));
                for(String field:List.of("duration_min_ms","duration_max_ms"))if(update.hasNonNull(field)) {
                    ((ObjectNode)output).set(field,update.get(field));sources.set("outputs."+condition.output_key()+"."+field,source);
                }
            }
        }
        // Existing M2 schema is the single authority for complete conditions and integer limits.
        EditDraftContracts.read(r,EditDraftContracts.Requirements.class);
        return new Values(r.toString(),sources.toString());
    }
}
