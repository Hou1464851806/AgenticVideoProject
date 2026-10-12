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
    public record Profile(String model,int dimension,Map<String,Object> bm25,String collection) {}
    public static Profile profile(String model,Integer dimension,Integer average) {
        String name=model==null?MODEL:model;int size=dimension==null?DIMENSION:dimension,avg=average==null?256:average;
        if(!Set.of("text-embedding-v4","text-embedding-v3").contains(name)||!Set.of(64,128,256,512,768,1024,1536,2048).contains(size)||("text-embedding-v3".equals(name)&&size>1024)||avg<1||avg>8192)
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"Embedding 模型、维度或 BM25 配置不受支持");
        var options=new LinkedHashMap<String,Object>(BM25);options.put("avg_len",avg);
        String collection=MODEL.equals(name)&&size==DIMENSION&&avg==256?COLLECTION:"evidence_"+name.replace('-','_')+"_"+size+"_bm25_avg"+avg+"_v1";
        return new Profile(name,size,Map.copyOf(options),collection);
    }
    public Profile profile(Map<String,Object> generation) {
        try {return new Profile(generation.get("model").toString(),((Number)generation.get("dimension")).intValue(),json.readValue(generation.get("profile_json").toString(),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}),generation.get("collection_name").toString());}
        catch(java.io.IOException invalid){throw new IllegalStateException(invalid);}
    }
    private final ObjectMapper json;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String qdrant,key,provider;
    private final com.agenticvideo.business.usage.QueryModelCalls calls;
    SearchVectors(com.agenticvideo.business.usage.QueryModelCalls calls,ObjectMapper json,@Value("${QDRANT_URL:http://localhost:6333}") String qdrant,
            @Value("${DASHSCOPE_API_KEY:}") String key,@Value("${DASHSCOPE_BASE_URL:https://dashscope.aliyuncs.com/api/v1}") String provider) {
        this.calls=calls;this.json=json;this.qdrant=qdrant;this.key=key;this.provider=provider;
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
    public JsonNode embed(UUID project,String text,Profile profile) {
        return embed(project,text,profile,null);
    }
    public JsonNode embed(UUID project,String text,Profile profile,UUID task) {
        if(key.isBlank())throw new ResponseStatusException(SERVICE_UNAVAILABLE,"语义检索模型凭据尚未配置");
        UUID receipt=calls.begin(project,profile.model(),text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,task);
        JsonNode result;
        try {result=call("POST",provider+"/services/embeddings/text-embedding/text-embedding",
                Map.of("model",profile.model(),"input",Map.of("texts",List.of(text)),"parameters",Map.of("dimension",profile.dimension(),"text_type","query")),key);}
        catch(RuntimeException unavailable){calls.uncertain(receipt);throw unavailable;}
        calls.succeeded(receipt,result);
        var vector=result.path("output").path("embeddings").path(0).path("embedding");
        requireVector(vector,profile.dimension());return vector;
    }
    public void requireVector(JsonNode vector) {requireVector(vector,DIMENSION);}
    public void requireVector(JsonNode vector,int dimension) {
        if(!vector.isArray()||vector.size()!=dimension)throw new ResponseStatusException(SERVICE_UNAVAILABLE,"Embedding 维度不符合已发布索引配置");
        for(var value:vector)if(!value.isNumber()||!Double.isFinite(value.asDouble()))throw new ResponseStatusException(SERVICE_UNAVAILABLE,"Embedding 包含无效数值");
    }
    public Object document(String text) {return document(text,profile(null,null,null));}
    public Object document(String text,Profile profile) {return Map.of("text",text,"model","qdrant/bm25","options",profile.bm25());}
    public JsonNode query(Object query,String using,Object filter,int limit) {
        return query(query,using,filter,filter,limit);
    }
    public JsonNode query(Object query,String using,Object filter,Object corpus,int limit) {
        return query(query,using,filter,corpus,limit,COLLECTION);
    }
    public JsonNode query(Object query,String using,Object filter,Object corpus,int limit,String collection) {
        var body=new LinkedHashMap<String,Object>();
        body.put("query",query);body.put("using",using);body.put("filter",filter);body.put("limit",limit);body.put("with_payload",true);
        if("bm25".equals(using))body.put("params",Map.of("idf",Map.of("corpus",corpus)));
        return request("POST","/collections/"+collection+"/points/query",body).path("result").path("points");
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
