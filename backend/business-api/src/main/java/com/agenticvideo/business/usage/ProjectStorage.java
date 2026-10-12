package com.agenticvideo.business.usage;

import com.agenticvideo.business.upload.MultipartStorage;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Physical bytes include multipart parts and objects from unpublished attempts. */
@Service
class ProjectStorage {
    private final JdbcTemplate db;
    private final MultipartStorage storage;
    ProjectStorage(JdbcTemplate db,MultipartStorage storage){this.db=db;this.storage=storage;}
    private static final class Category {
        long stored,reserved,cleanup;
        Map<String,Long> view(){return Map.of("storedBytes",stored,"reservedBytes",reserved,"pendingCleanupBytes",cleanup);}
    }
    Object measure(UUID project) {
        var categories=new LinkedHashMap<String,Category>();
        for(String name:List.of("ORIGINAL","DERIVED","PREVIEW","EXPORT"))categories.put(name,new Category());
        var original=categories.get("ORIGINAL");var derived=categories.get("DERIVED");
        var measured=new HashSet<String>();
        for(var upload:db.queryForList("SELECT u.*,r.bytes,r.future_bytes,r.state AS reservation_state,c.status AS cleanup_status FROM upload_sessions u LEFT JOIN storage_reservations r ON r.upload_id=u.id LEFT JOIN upload_cleanup_jobs c ON c.upload_id=u.id WHERE u.project_id=?",project)) {
            String key=upload.get("object_key").toString();long bytes=storage.uploadBytes(key);original.stored+=bytes;measured.add(key);
            if("PENDING".equals(upload.get("cleanup_status")))original.cleanup+=bytes;
            if("RESERVED".equals(upload.get("reservation_state"))) {
                original.reserved+=Math.max(0,((Number)upload.get("bytes")).longValue()-bytes);
                derived.reserved+=Math.max(0,((Number)upload.get("future_bytes")).longValue()-((Number)upload.get("bytes")).longValue());
            }else if("ALLOCATED".equals(upload.get("reservation_state"))&&db.queryForObject("SELECT count(*) FROM tasks WHERE status IN ('PENDING','RUNNING','CANCEL_REQUESTED') AND (id=? OR type='CONTENT_ANALYZE' AND input_json->>'assetId'=?)",Integer.class,upload.get("validation_task_id"),Objects.toString(upload.get("asset_id"),""))>0)
                derived.reserved+=((Number)upload.get("future_bytes")).longValue();
        }
        for(var asset:db.queryForList("SELECT a.id,a.object_key,c.status AS cleanup_status FROM assets a LEFT JOIN object_cleanup_jobs c ON c.asset_id=a.id WHERE a.project_id=?",project)) {
            String key=asset.get("object_key").toString();Long bytes=storage.objectSize(key);
            if(measured.add(key)&&bytes!=null)original.stored+=bytes;
            if("PENDING".equals(asset.get("cleanup_status"))&&bytes!=null)original.cleanup+=bytes;
            for(String kind:List.of("audio","frames"))for(long size:storage.objectSizes("derived/"+kind+"/"+asset.get("id")+"/").values())derived.stored+=size;
        }
        derived.reserved+=db.queryForObject("SELECT coalesce(sum(disk_budget_bytes),0) FROM tasks WHERE project_id=? AND type IN ('VISION_PREPARE','INSPECTION_PREPARE') AND status IN ('PENDING','RUNNING','CANCEL_REQUESTED')",Long.class,project);
        for(var request:db.queryForList("SELECT r.id,t.type,t.status,t.disk_budget_bytes FROM render_requests r JOIN tasks t ON t.id=r.task_id WHERE r.project_id=?",project)) {
            boolean export="EXPORT_RENDER".equals(request.get("type"));var category=categories.get(export?"EXPORT":"PREVIEW");
            var objects=storage.objectSizes((export?"exports/":"previews/")+request.get("id")+"/");
            for(long size:objects.values())category.stored+=size;
            if(Set.of("PENDING","RUNNING","CANCEL_REQUESTED").contains(request.get("status")))category.reserved+=((Number)request.get("disk_budget_bytes")).longValue();
            for(var artifact:db.queryForList("SELECT a.object_key,a.state,x.status AS attempt_status FROM artifacts a JOIN task_attempts x ON x.id=a.attempt_id WHERE a.request_id=?",request.get("id")))
                if("STAGED".equals(artifact.get("state"))&&Set.of("FAILED","EXPIRED","CANCELLED").contains(artifact.get("attempt_status")))category.cleanup+=objects.getOrDefault(artifact.get("object_key").toString(),0L);
        }
        var result=new LinkedHashMap<String,Object>();categories.forEach((name,value)->result.put(name,value.view()));return result;
    }
}
