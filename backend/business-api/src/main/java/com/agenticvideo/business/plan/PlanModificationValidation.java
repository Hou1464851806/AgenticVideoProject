package com.agenticvideo.business.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.CONFLICT;

/** Merges a bounded edit into an immutable complete version; untouched clips never pass through the model. */
@Component
public class PlanModificationValidation {
    private final ObjectMapper json;
    public PlanModificationValidation(ObjectMapper json){this.json=json;}

    public EditPlanValidation.Validated validate(JsonNode input,JsonNode proposal,Function<JsonNode,EditPlanValidation.Validated> generate){
        if(!proposal.isObject()||proposal.size()!=1||!proposal.has("clips"))reject("修改候选仅可提供目标成片的有序片段");
        JsonNode entries=proposal.path("clips");
        if(!entries.isArray()||entries.isEmpty()||entries.size()>10)reject("修改后成片须有 1 至 10 个片段");
        JsonNode editing=input.path("editing"),change=editing.path("modification"),base=change.path("baseContent");
        ObjectNode content=base.deepCopy();content.remove("changeSummary");
        ObjectNode output=null;
        for(var item:content.path("outputs"))if(item.path("outputId").equals(change.path("outputId")))output=(ObjectNode)item;
        if(output==null)reject("修改输出不属于基准版本");
        var mutable=new LinkedHashSet<String>();for(var id:change.path("clipIds"))mutable.add(id.asText());
        var originals=new LinkedHashMap<String,JsonNode>();var offsets=new HashMap<String,Long>();
        long oldOffset=0;
        for(var clip:output.path("clips")){String id=clip.path("clipId").asText();originals.put(id,clip);offsets.put(id,oldOffset);oldOffset+=length(clip);}
        if(mutable.isEmpty()||!originals.keySet().containsAll(mutable)||!Set.of("REPLACE","COMPACT").contains(change.path("mode").asText()))reject("修改范围不属于固定基准");
        boolean replace="REPLACE".equals(change.path("mode").asText());
        if(replace&&mutable.size()!=1)reject("定向替换须固定一个片段");
        var retained=new ArrayList<String>();var seen=new HashSet<String>();
        var clips=json.createArrayNode();var cues=json.createArrayNode();
        long duration=0;int generated=0;
        var originalCues=output.path("subtitles").path("cues");
        var before=new ArrayList<String>();var after=new ArrayList<String>();boolean past=false;
        for(String id:originals.keySet()){if(mutable.contains(id))past=true;else (past?after:before).add(id);}
        for(var entry:entries){
            if(entry.has("clipId")){
                String id=entry.path("clipId").asText();
                if(entry.size()!=1||!originals.containsKey(id)||!seen.add(id))reject("保留片段只能引用原标识，不能改写或重复");
                retained.add(id);JsonNode clip=originals.get(id);clips.add(clip.deepCopy());
                long start=offsets.get(id),end=start+length(clip);
                for(var cue:originalCues)if(cue.path("startMs").asLong()>=start&&cue.path("endMs").asLong()<=end){
                    ObjectNode shifted=cue.deepCopy();shifted.put("startMs",duration+cue.path("startMs").asLong()-start);shifted.put("endMs",duration+cue.path("endMs").asLong()-start);cues.add(shifted);
                }
                duration+=length(clip);
            }else{
                if(replace){
                    // New clips occupy only the one requested slot between the exact prefix and suffix.
                    if(!retained.equals(before))reject("新增片段只能替换指定位置，不能插入目标外范围");
                }
                JsonNode fragment=json.valueToTree(Map.of("title",output.path("title").asText(),"clips",List.of(entry)));
                JsonNode rendered=json.valueToTree(generate.apply(fragment).content()).path("outputs").get(0);
                clips.add(rendered.path("clips").get(0));
                for(var cue:rendered.path("subtitles").path("cues")){
                    ObjectNode shifted=cue.deepCopy();shifted.put("startMs",duration+cue.path("startMs").asLong());shifted.put("endMs",duration+cue.path("endMs").asLong());cues.add(shifted);
                }
                duration+=rendered.path("durationMs").asLong();generated++;
            }
        }
        var required=new ArrayList<String>();for(String id:originals.keySet())if(!mutable.contains(id))required.add(id);
        var actual=new ArrayList<String>();for(String id:retained)if(!mutable.contains(id))actual.add(id);
        if(!actual.equals(required))reject("必须原样保留目标外片段及顺序");
        if(replace&&(generated==0||retained.stream().anyMatch(mutable::contains)))reject("定向替换必须更换目标片段，保留其他片段");
        if(!replace&&duration>=oldOffset)reject("紧凑化必须实际缩短成片，不能只改写说明");
        if(duration<editing.path("minDurationMs").asLong()||duration>editing.path("maxDurationMs").asLong())reject("完整成片总时长不满足原硬时长范围");
        var ranges=new HashMap<String,List<long[]>>();var order=new ArrayList<String>();
        for(var clip:clips){
            String asset=clip.path("assetId").asText();long start=clip.path("sourceInMs").asLong(),end=clip.path("sourceOutMs").asLong();
            for(var range:ranges.computeIfAbsent(asset,key->new ArrayList<>()))if(start<range[1]&&end>range[0])reject("完整成片不能重复使用重叠原文区间");
            ranges.get(asset).add(new long[]{start,end});
            if(order.isEmpty()||!order.get(order.size()-1).equals(asset))order.add(asset);
        }
        if("MONTAGE".equals(editing.path("kind").asText())){
            var requiredOrder=new ArrayList<String>();for(var asset:editing.path("orderedAssetIds"))requiredOrder.add(asset.asText());
            if(!order.equals(requiredOrder))reject("修改后仍须保留原素材参与顺序");
        }else if(ranges.size()!=1)reject("讲话方案仍须使用一份讲话素材");
        output.set("clips",clips);output.put("durationMs",duration);((ObjectNode)output.path("subtitles")).set("cues",cues);
        var oldRanges=new ArrayList<List<Object>>();var newRanges=new ArrayList<List<Object>>();
        for(var clip:originals.values())oldRanges.add(List.of(UUID.fromString(clip.path("assetId").asText()),clip.path("sourceInMs").asLong(),clip.path("sourceOutMs").asLong()));
        for(var clip:clips)newRanges.add(List.of(UUID.fromString(clip.path("assetId").asText()),clip.path("sourceInMs").asLong(),clip.path("sourceOutMs").asLong()));
        if(oldRanges.equals(newRanges))reject("候选没有实际片段内容变更，不能只改写说明或身份");
        var removed=new ArrayList<String>();for(String id:mutable)if(!seen.contains(id))removed.add("片段 "+(new ArrayList<>(originals.keySet()).indexOf(id)+1));
        content.put("changeSummary",(replace?"替换":"精简")+String.join("、",removed)+"；成片时长 "+oldOffset/1000.0+" → "+duration/1000.0+" 秒。其他输出、目标外片段、原画幅与硬约束保留。");
        Set<UUID> assets=new HashSet<>(),evidence=new HashSet<>();
        for(var item:content.path("outputs")){
            for(var clip:item.path("clips")){assets.add(UUID.fromString(clip.path("assetId").asText()));for(var id:clip.path("evidenceIds"))evidence.add(UUID.fromString(id.asText()));}
            for(var cue:item.path("subtitles").path("cues"))if(cue.has("evidenceId"))evidence.add(UUID.fromString(cue.path("evidenceId").asText()));
        }
        return new EditPlanValidation.Validated(json.convertValue(content,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}),assets,evidence);
    }
    private long length(JsonNode clip){return clip.path("sourceOutMs").asLong()-clip.path("sourceInMs").asLong();}
    private void reject(String message){throw new ResponseStatusException(CONFLICT,message);}
}
