package com.agenticvideo.business.search;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Service;

/** Rebuildable Qdrant writes use persisted vectors; publication remains in SearchIndexes. */
@Service
public class SearchIndexBuilder {
    private final SearchVectors vectors;
    public SearchIndexBuilder(SearchVectors vectors){this.vectors=vectors;}
    public void write(JsonNode profile,BooleanSupplier stopped) throws InterruptedException {
        String path="/collections/"+profile.path("collection").asText();
        check(stopped);
        // Idempotent collection creation; 404 is distinct from an unavailable Qdrant.
        try {vectors.request("GET",path,null);}
        catch(SearchVectors.MissingCollection missing) {
            vectors.request("PUT",path,Map.of("vectors",Map.of("dense",Map.of("size",profile.path("dimension").asInt(),"distance","Cosine")),"sparse_vectors",Map.of("bm25",Map.of("modifier","idf"))));
        }
        for(String field:List.of("projectId","generationId","assetId","sourceKind")) {
            check(stopped);vectors.request("PUT",path+"/index?wait=true",Map.of("field_name",field,"field_schema","keyword"));
        }
        for(var batch:profile.path("batches")) {
            check(stopped);var points=new ArrayList<Object>();
            for(int i=0;i<batch.path("units").size();i++) {
                var unit=batch.path("units").get(i);var payload=new LinkedHashMap<String,Object>();
                for(String key:List.of("projectId","generationId","assetId","analysisId"))payload.put(key,profile.path(key).asText());
                payload.put("unitId",unit.path("unitId").asText());payload.put("sourceKind",unit.path("sourceKind").asText());
                points.add(Map.of("id",unit.path("id").asText(),"vector",Map.of("dense",batch.path("vectors").get(i),"bm25",Map.of("text",unit.path("text").asText(),"model","qdrant/bm25","options",profile.path("bm25"))),"payload",payload));
            }
            vectors.request("PUT",path+"/points?wait=true",Map.of("points",points));
        }
        check(stopped);
    }
    private static void check(BooleanSupplier stopped) throws InterruptedException {if(stopped.getAsBoolean())throw new InterruptedException("索引执行已停止");}
}
