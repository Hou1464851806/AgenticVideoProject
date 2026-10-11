package com.agenticvideo.business.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

/** Native BM25 and external dense vectors share an immutable, versioned profile. */
@Component
public class SearchVectors {
    public static final String COLLECTION="evidence_qwen_v4_1024_bm25_v1";
    public static final String MODEL="text-embedding-v4";
    public static final int DIMENSION=1024;
    public static final Map<String,Object> BM25=Map.of("tokenizer","multilingual","stemmer",Map.of("type","none"),"stopwords",Map.of(),"ascii_folding",true,"k",1.2,"b",0.75,"avg_len",256);
    private final ObjectMapper json;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String qdrant,key,provider;
    SearchVectors(ObjectMapper json,@Value("${QDRANT_URL:http://localhost:6333}") String qdrant,
            @Value("${DASHSCOPE_API_KEY:}") String key,@Value("${DASHSCOPE_BASE_URL:https://dashscope.aliyuncs.com/api/v1}") String provider) {
        this.json=json;this.qdrant=qdrant;this.key=key;this.provider=provider;
    }
    public JsonNode request(String method,String path,Object body) {
        return call(method,qdrant+path,body,null);
    }
    private JsonNode call(String method,String address,Object body,String bearer) {
        try {
            var request=HttpRequest.newBuilder(URI.create(address)).timeout(Duration.ofSeconds(45)).header("Content-Type","application/json")
                    .method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            if(bearer!=null)request.header("Authorization","Bearer "+bearer);
            var response=client.send(request.build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()/100!=2)throw new ResponseStatusException(SERVICE_UNAVAILABLE,"检索提供方暂不可用，原文查找仍可使用");
            return json.readTree(response.body());
        } catch(java.io.IOException failure) {throw new ResponseStatusException(SERVICE_UNAVAILABLE,"检索连接暂不可用");}
        catch(InterruptedException stopped) {Thread.currentThread().interrupt();throw new ResponseStatusException(SERVICE_UNAVAILABLE,"检索已中断");}
    }
    public JsonNode embed(String text) {
        if(key.isBlank())throw new ResponseStatusException(SERVICE_UNAVAILABLE,"语义检索模型凭据尚未配置");
        var result=call("POST",provider+"/services/embeddings/text-embedding/text-embedding",
                Map.of("model",MODEL,"input",Map.of("texts",List.of(text)),"parameters",Map.of("dimension",DIMENSION,"text_type","query")),key);
        var vector=result.path("output").path("embeddings").path(0).path("embedding");
        requireVector(vector);return vector;
    }
    public void requireVector(JsonNode vector) {
        if(!vector.isArray()||vector.size()!=DIMENSION)throw new ResponseStatusException(SERVICE_UNAVAILABLE,"Embedding 维度不符合已发布索引配置");
        for(var value:vector)if(!value.isNumber()||!Double.isFinite(value.asDouble()))throw new ResponseStatusException(SERVICE_UNAVAILABLE,"Embedding 包含无效数值");
    }
    public Object document(String text) {return Map.of("text",text,"model","qdrant/bm25","options",BM25);}
    public JsonNode query(Object query,String using,Object filter,int limit) {
        return query(query,using,filter,filter,limit);
    }
    public JsonNode query(Object query,String using,Object filter,Object corpus,int limit) {
        var body=new LinkedHashMap<String,Object>();
        body.put("query",query);body.put("using",using);body.put("filter",filter);body.put("limit",limit);body.put("with_payload",true);
        if("bm25".equals(using))body.put("params",Map.of("idf",Map.of("corpus",corpus)));
        return request("POST","/collections/"+COLLECTION+"/points/query",body).path("result").path("points");
    }
    public static Object scope(UUID project,List<String> generations) {return scope(project,generations,null);}
    public static Object scope(UUID project,List<String> generations,List<String> kinds) {
        var must=new java.util.ArrayList<Object>();
        must.add(Map.of("key","projectId","match",Map.of("value",project.toString())));
        must.add(Map.of("key","generationId","match",Map.of("any",generations)));
        if(kinds!=null)must.add(Map.of("key","sourceKind","match",Map.of("any",kinds)));
        return Map.of("must",must);
    }
}
