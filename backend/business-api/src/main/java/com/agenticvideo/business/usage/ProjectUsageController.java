package com.agenticvideo.business.usage;

import com.agenticvideo.business.project.ProjectAccess;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Project-owned measurements and optional cumulative warning thresholds. */
@RestController
class ProjectUsageController {
    private final ProjectAccess access;
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final ProjectStorage storage;
    private final ModelUsage models;
    ProjectUsageController(ProjectAccess access,JdbcTemplate db,ObjectMapper json,ProjectStorage storage,ModelUsage models){this.access=access;this.db=db;this.json=json;this.storage=storage;this.models=models;}
    record Thresholds(@Positive BigDecimal costCny,@Positive Long calls,@Positive Long tokens,@Positive BigDecimal audioSeconds) {}
    @GetMapping("/api/v1/projects/{project}/usage/models")
    Object modelUsage(@PathVariable UUID project,@RequestParam(defaultValue="0") int offset,Authentication account) {
        access.requireOwned(project,account);
        if(offset<0||offset>1000000)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"记录页码不合法");
        return envelope(models.measure(project,offset));
    }
    @GetMapping("/api/v1/projects/{project}/usage")
    Object usage(@PathVariable UUID project,@RequestParam(defaultValue="0") int offset,Authentication account) {
        access.requireOwned(project,account);
        if(offset<0||offset>1000000)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"记录页码不合法");
        var measured=json.valueToTree(models.measure(project,offset));var configured=json.valueToTree(thresholds(project));
        var warnings=new ArrayList<Object>();
        for(var pair:Map.of("costCny","estimatedCostCny","calls","callCount","tokens","tokens","audioSeconds","audioSeconds").entrySet())
            if(configured.path(pair.getKey()).isNumber()&&measured.path(pair.getValue()).decimalValue().compareTo(configured.path(pair.getKey()).decimalValue())>=0)
                warnings.add(Map.of("metric",pair.getKey(),"value",measured.path(pair.getValue()),"threshold",configured.path(pair.getKey())));
        warnings.sort(Comparator.comparing(value->((Map<?,?>)value).get("metric").toString()));
        return envelope(Map.of("storage",storage.measure(project),"models",measured,"thresholds",configured,"warnings",warnings));
    }
    @PatchMapping("/api/v1/projects/{project}/usage/thresholds")
    Object configure(@PathVariable UUID project,@Valid @RequestBody Thresholds value,Authentication account) {
        access.requireOwned(project,account);
        db.update("INSERT INTO project_usage_thresholds(project_id,thresholds_json) VALUES (?,?::jsonb) ON CONFLICT(project_id) DO UPDATE SET thresholds_json=excluded.thresholds_json,updated_at=now()",project,json.valueToTree(value).toString());
        return envelope(thresholds(project));
    }
    private Object thresholds(UUID project) {
        var rows=db.queryForList("SELECT thresholds_json FROM project_usage_thresholds WHERE project_id=?",project);
        try{return rows.isEmpty()?json.valueToTree(new Thresholds(null,null,null,null)):json.readTree(rows.get(0).get("thresholds_json").toString());}
        catch(java.io.IOException failure){throw new IllegalStateException(failure);}
    }
    private Object envelope(Object data){return Map.of("data",data,"requestId",UUID.randomUUID());}
}
