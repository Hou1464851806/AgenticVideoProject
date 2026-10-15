package com.agenticvideo.business.render;

import com.agenticvideo.business.plan.ProductionDrafts;
import com.agenticvideo.business.plan.ProductionSaves;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import static org.springframework.http.HttpStatus.*;

/** Internal production binding; all rendering inputs come from the authoritative saved revision. */
@RestController
@RequestMapping("/internal/v1")
public class InternalPreviewController {
    private final ProductionDrafts productions;
    private final ProductionSaves saves;
    private final PreviewRequests previews;
    private final ObjectMapper json;
    public InternalPreviewController(ProductionDrafts productions,ProductionSaves saves,PreviewRequests previews,ObjectMapper json) {
        this.productions=productions;this.saves=saves;this.previews=previews;this.json=json;
    }
    private JsonNode saved(UUID requirement) {
        try {productions.get(requirement);}
        catch(ResponseStatusException missing) {
            if(missing.getStatusCode()!=NOT_FOUND)throw missing;
            throw new PreviewRequests.Rejected(NOT_FOUND,"PRODUCTION_NOT_FOUND","制作需求不存在");
        }
        try {return saves.result(requirement);}
        catch(ResponseStatusException missing) {
            if(missing.getStatusCode()!=NOT_FOUND)throw missing;
            throw new PreviewRequests.Rejected(CONFLICT,"PRODUCTION_NOT_SAVED","请先完成显式保存");
        }
    }
    @PostMapping("/productions/{requirementId}/previews")
    public ResponseEntity<PreviewContracts.Result> request(@PathVariable UUID requirementId,@RequestBody JsonNode body) {
        final PreviewContracts.Request selection;
        try {selection=PreviewContracts.request(body);}
        catch(IllegalArgumentException invalid){throw new PreviewRequests.Rejected(BAD_REQUEST,"INVALID_REQUEST",invalid.getMessage());}
        var saved=saved(requirementId);var outputs=saved.path("outputs");
        String key=selection.output_key();
        if(key==null) {
            if(outputs.size()!=1)throw new PreviewRequests.Rejected(BAD_REQUEST,"OUTPUT_SELECTION_REQUIRED","请指定 output_key，可选："+outputs.findValuesAsText("output_key"));
            key=outputs.get(0).path("output_key").asText();
        }
        JsonNode output=null;
        for(var item:outputs)if(key.equals(item.path("output_key").asText()))output=item;
        if(output==null)throw new PreviewRequests.Rejected(NOT_FOUND,"OUTPUT_NOT_FOUND","成片定义不存在");
        var admission=previews.requestSaved(UUID.fromString(saved.path("revision_id").asText()),UUID.fromString(output.path("output_id").asText()));
        UUID request=UUID.fromString(json.valueToTree(admission.result()).path("requestId").asText());
        return ResponseEntity.status(admission.created()?ACCEPTED:OK).body(previews.readPreview(request));
    }
    @GetMapping("/productions/{requirementId}/previews")
    public PreviewContracts.ListResult list(@PathVariable UUID requirementId) {
        var saved=saved(requirementId);UUID revision=UUID.fromString(saved.path("revision_id").asText());
        var keys=new HashMap<String,String>();
        for(var output:saved.path("outputs"))keys.put(output.path("output_id").asText(),output.path("output_key").asText());
        var items=new ArrayList<PreviewContracts.ListItem>();
        for(var row:json.valueToTree(previews.list(revision)).path("items")) {
            UUID request=UUID.fromString(row.path("requestId").asText());previews.previewRow(request);
            items.add(new PreviewContracts.ListItem(request,keys.get(row.path("outputId").asText()),UUID.fromString(row.path("outputId").asText()),
                    UUID.fromString(row.path("taskId").asText()),PreviewContracts.TaskStatus.valueOf(row.path("status").asText()),row.path("createdAt").asText()));
        }
        return new PreviewContracts.ListResult(requirementId,revision,items);
    }
    @ExceptionHandler(software.amazon.awssdk.core.exception.SdkException.class)
    @ResponseStatus(SERVICE_UNAVAILABLE)
    public Object storageUnavailable() {
        return Map.of("error",new PreviewContracts.Error("PREVIEW_STORAGE_UNAVAILABLE","预览存储暂时不可访问，请稍后核对"));
    }
    @ExceptionHandler(PreviewRequests.Rejected.class)
    public ResponseEntity<?> rejected(PreviewRequests.Rejected failure) {
        return ResponseEntity.status(failure.getStatusCode()).body(Map.of("error",new PreviewContracts.Error(failure.code,failure.getReason())));
    }
    @ExceptionHandler({MethodArgumentTypeMismatchException.class,HttpMessageNotReadableException.class})
    @ResponseStatus(BAD_REQUEST)
    public Object invalid(Exception failure) {
        return Map.of("error",new PreviewContracts.Error("INVALID_REQUEST","预览请求格式无效"));
    }
}
