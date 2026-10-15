package com.agenticvideo.business.plan;

import com.agenticvideo.business.analysis.SpeechTimings;
import com.agenticvideo.business.search.QuestionEvidence.Scope;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.agenticvideo.business.plan.EditDraftContracts.*;

/** Deterministic hard-cut rules. Does not admit calls, persist results or allocate business IDs. */
@Service
public class EditDraftValidation {
    public static final String RULE_VERSION="hardcut-m2-v1";
    private final DraftEvidence evidence;
    private final SpeechTimings timings;
    private final ObjectMapper json;
    public EditDraftValidation(DraftEvidence evidence,SpeechTimings timings,ObjectMapper json){this.evidence=evidence;this.timings=timings;this.json=json;}
    public record Checked(ObjectNode result,JsonNode content,JsonNode sources) {}
    public Checked validate(Scope scope,Input input,UUID validationId) {
        var observations=evidence.observations(scope);var reads=evidence.reads(scope);
        var assets=new HashMap<String,JsonNode>();scope.assets().forEach(a->assets.put(a.path("id").asText(),a));
        var issues=json.createArrayNode();var warnings=json.createArrayNode();var durations=json.createArrayNode();var outputs=json.createArrayNode();
        var usedAssets=new LinkedHashSet<String>();var usedEvidence=new LinkedHashSet<String>();
        for(int oi=0;oi<input.draft().outputs().size();oi++) {
            var output=input.draft().outputs().get(oi);String op="outputs["+oi+"]",key=output.output_key();
            var clips=json.createArrayNode();var cues=json.createArrayNode();long duration=0;boolean calculable=true;
            var outputText=new StringBuilder();var reportedForbidden=new HashSet<String>();boolean outputSpeechValid=true;
            var ranges=new HashMap<String,List<long[]>>();
            for(int ci=0;ci<output.clips().size();ci++) {
                var clip=output.clips().get(ci);String cp=op+".clips["+ci+"]";var asset=assets.get(clip.asset_id());
                if(asset==null || !input.requirements().asset_ids().contains(clip.asset_id())) {
                    issue(issues,"ASSET_OUTSIDE_SCOPE",cp+".asset_id",key,"素材不在固定范围和制作条件中",clip.asset_id(),null);calculable=false;continue;
                }
                long start=clip.source_in_ms(),end=clip.source_out_ms();
                if(!"VALID".equals(asset.path("validation_status").asText()) || asset.path("duration_ms").isNull()) {
                    issue(issues,"ASSET_NOT_READY",cp+".asset_id",key,"固定快照中的素材尚未完成探测",clip.asset_id(),null);calculable=false;continue;
                }
                if(end<=start || end>asset.path("duration_ms").asLong()) {
                    issue(issues,"CLIP_RANGE",cp+".source_out_ms",key,"片段须满足 0 ≤ in < out ≤ 素材时长",end,Map.of("source_in_ms",start,"asset_duration_ms",asset.path("duration_ms").asLong()));calculable=false;continue;
                }
                var prior=ranges.computeIfAbsent(clip.asset_id(),ignored->new ArrayList<>());
                if(prior.stream().anyMatch(r->start<r[1] && end>r[0]))issue(issues,"OVERLAPPING_CLIPS",cp,key,"同一输出不能复用重叠素材区间",Map.of("start_ms",start,"end_ms",end),null);
                prior.add(new long[]{start,end});usedAssets.add(clip.asset_id());
                if(!reads.covers(clip.asset_id(),start,end))issue(issues,"CONTEXT_NOT_READ",cp,key,"尚未完整读取覆盖片段的范围分页",Map.of("asset_id",clip.asset_id(),"start_ms",start,"end_ms",end),null);
                var citations=new HashSet<String>();
                for(int ei=0;ei<clip.evidence_ids().size();ei++) {
                    String id=clip.evidence_ids().get(ei),field=cp+".evidence_ids["+ei+"]";var row=observations.get(id);
                    if(!citations.add(id)){issue(issues,"DUPLICATE_EVIDENCE",field,key,"片段引用重复",id,null);continue;}
                    if(row==null || !row.get("asset_id").toString().equals(clip.asset_id()) || !row.get("analysis_id").toString().equals(asset.path("analysis_version").asText())) {
                        issue(issues,"EVIDENCE_OUTSIDE_SCOPE",field,key,"依据不属于同素材的固定分析版本",id,null);continue;
                    }
                    long a=((Number)row.get("start_ms")).longValue(),b=((Number)row.get("end_ms")).longValue();
                    boolean overlaps="SPEECH".equals(row.get("source_kind"))?a<end && b>start:a>=start && a<end;
                    if(!overlaps)issue(issues,"EVIDENCE_OUTSIDE_CLIP",field,key,"语音须相交；视觉点须位于半开片段内",id,null);
                    if(!reads.evidenceIds().contains(id))issue(issues,"EVIDENCE_NOT_READ",field,key,"依据尚未经范围工具实际读取",id,null);
                    usedEvidence.add(id);
                }
                var assetSpeech=observations.values().stream().filter(row->row.get("asset_id").toString().equals(clip.asset_id()) && "SPEECH".equals(row.get("source_kind")))
                    .sorted(Comparator.<Map<String,Object>>comparingLong(row->((Number)row.get("start_ms")).longValue()).thenComparingInt(row->((Number)row.get("ordinal")).intValue()).thenComparing(row->row.get("id").toString())).toList();
                var speech=assetSpeech.stream().filter(row->((Number)row.get("start_ms")).longValue()<end && ((Number)row.get("end_ms")).longValue()>start).toList();
                var selectedText=new StringBuilder();boolean clipSpeechValid=true;
                for(var row:speech) {
                    String id=row.get("id").toString();usedEvidence.add(id);
                    if(!reads.evidenceIds().contains(id))issue(issues,"EVIDENCE_NOT_READ",cp,key,"实际相交语音尚未读取",id,null);
                    var selection=timings.select(row,start,end,asset.path("audio_offset_ms").asLong());
                    if(selection.sentenceCut()) {clipSpeechValid=false;issue(issues,"MISSING_WORD_TIMING",cp,key,"缺少词级时间，不能切入原句",id,Map.of("sentence_start_ms",row.get("start_ms"),"sentence_end_ms",row.get("end_ms")));continue;}
                    for(var cut:selection.cuts()) {
                        var safe=cut.nearbySafeBoundaries().stream().filter(t->timings.safeBoundary(assetSpeech,t,asset.path("audio_offset_ms").asLong())).toList();
                        issue(issues,"CUT_INSIDE_WORD",cp+"."+cut.field(),key,"剪切点截断原词",cut.actual(),Map.of("nearby_safe_boundaries_ms",safe));
                    }
                    if(!selection.cuts().isEmpty())clipSpeechValid=false;
                    if(!selection.cuts().isEmpty() || selection.text().isEmpty())continue;
                    String caption=selection.text();
                    selectedText.append(caption);
                    if(input.requirements().subtitles_enabled()) {
                        if(caption.length()>2000 || caption.chars().anyMatch(value->value<32 && value!=10))issue(issues,"SUBTITLE_TEXT",cp,key,"原文不符合既有字幕文本限制",id,null);
                        cues.addObject().put("startMs",duration+selection.startMs()-start).put("endMs",duration+selection.endMs()-start).put("text",caption).put("evidenceId",id);
                    }
                }
                if(clipSpeechValid) {
                    for(String forbidden:input.requirements().forbidden_content())if(selectedText.toString().contains(forbidden)) {
                        issue(issues,"FORBIDDEN_CONTENT",cp,key,"实际入选转写包含明确禁用词句",forbidden,null);reportedForbidden.add(forbidden);
                    }
                } else outputSpeechValid=false;
                outputText.append(selectedText);
                var audioRanges=asset.path("coverage").path("audio_ranges");
                boolean audio=asset.path("has_audio").asBoolean() && (audioRanges.isEmpty() || java.util.stream.StreamSupport.stream(audioRanges.spliterator(),false)
                    .anyMatch(r->r.path("start_ms").asLong()<end && r.path("end_ms").asLong()>start));
                boolean processed=speechCovered(asset,start,end);
                if(audio && (!processed || speech.isEmpty())) {
                    var warning=warnings.addObject().put("code","SUBTITLE_GAP").put("field",cp).put("output_key",key)
                        .put("message",speech.isEmpty()?"选段没有转写，存在潜在字幕缺口；完整性仅针对已处理范围内的已识别语音，不保证没有漏识别":"ASR 处理覆盖不足，无法保证完整字幕");
                    warning.put("start_ms",start).put("end_ms",end);
                }
                if(audio && input.requirements().require_complete_subtitles() && !processed)
                    issue(issues,"INCOMPLETE_SUBTITLES",cp,key,"ASR 未就绪或处理覆盖不足，不能保证完整字幕",Map.of("start_ms",start,"end_ms",end),null);
                var normalized=clips.addObject().put("assetId",clip.asset_id()).put("sourceInMs",start).put("sourceOutMs",end).put("audio","original").put("reason",clip.reason());
                normalized.set("evidenceIds",json.valueToTree(clip.evidence_ids()));duration+=end-start;
            }
            durations.add(duration);
            if(calculable && outputSpeechValid)for(String forbidden:input.requirements().forbidden_content())
                if(!reportedForbidden.contains(forbidden) && outputText.toString().contains(forbidden))
                    issue(issues,"FORBIDDEN_CONTENT",op,key,"输出入选转写包含跨片段的明确禁用词句",forbidden,null);
            var condition=input.requirements().outputs().stream().filter(c->c.output_key().equals(key)).findFirst().orElseThrow();
            if(calculable && (condition.duration_min_ms()!=null && duration<condition.duration_min_ms() || condition.duration_max_ms()!=null && duration>condition.duration_max_ms())) {
                var expected=json.createObjectNode();expected.set("min_ms",json.valueToTree(condition.duration_min_ms()));expected.set("max_ms",json.valueToTree(condition.duration_max_ms()));
                issue(issues,"OUTPUT_DURATION",op,key,"输出时长不满足条件",duration,expected);
            }
            boolean portrait=input.requirements().profile()==Profile.PORTRAIT_720;
            var normalized=outputs.addObject().put("output_key",key).put("title",output.title()).put("durationMs",duration);
            normalized.putObject("render").put("container","mp4").put("videoCodec","h264").put("audioCodec","aac").put("width",portrait?720:1280).put("height",portrait?1280:720)
                .put("fit","contain").put("framesPerSecond",30).put("profile",input.requirements().profile().name()).put("configVersion",RULE_VERSION);
            normalized.set("clips",clips);normalized.putObject("subtitles").put("enabled",input.requirements().subtitles_enabled()).set("cues",cues);
        }
        var result=json.createObjectNode().put("validation_id",validationId.toString()).put("valid",issues.isEmpty()).put("draft_hash",input.draft_hash()).put("requirements_hash",input.requirements_hash()).put("issues_truncated",false);
        result.set("durations_ms",durations);result.set("issues",issues);result.set("warnings",warnings);
        var content=json.createObjectNode().put("schemaVersion","1.0");content.set("outputs",outputs);
        var sources=json.createObjectNode();sources.set("asset_ids",json.valueToTree(usedAssets));sources.set("evidence_ids",json.valueToTree(usedEvidence));sources.set("read_call_ids",json.valueToTree(reads.callIds()));
        return new Checked(result,issues.isEmpty()?content:null,sources);
    }
    private boolean speechCovered(JsonNode asset,long start,long end) {
        var audio=asset.path("coverage").path("audio_ranges");
        if(audio.isEmpty())return false; // A probed stream with unknown duration cannot prove ASR coverage.
        long requiredStart=Math.max(start,audio.get(0).path("start_ms").asLong());
        long requiredEnd=Math.min(end,audio.get(0).path("end_ms").asLong());
        if(requiredEnd<=requiredStart)return true;
        long through=requiredStart;
        for(var range:asset.path("coverage").path("speech_processed_ranges")) {
            if(range.path("start_ms").asLong()>through)break;
            through=Math.max(through,range.path("end_ms").asLong());if(through>=requiredEnd)return true;
        }
        return false;
    }
    private void issue(ArrayNode issues,String code,String field,String key,String message,Object actual,Object expected) {
        var issue=issues.addObject().put("code",code).put("field",field).put("output_key",key).put("message",message);
        if(actual!=null)issue.set("actual",json.valueToTree(actual));if(expected!=null)issue.set("expected",json.valueToTree(expected));
    }
}
