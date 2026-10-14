package com.agenticvideo.business.bailian;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class BailianEmbedding {
    private final BailianHttp http;private final ObjectMapper json;
    public BailianEmbedding(BailianHttp http,ObjectMapper json){this.http=http;this.json=json;}
    public JsonNode embed(List<String> texts,String model,int dimension,String type) {
        if(texts.isEmpty()||texts.size()>10||!Set.of("query","document").contains(type))throw new IllegalArgumentException("Embedding 输入无效");
        return http.request("POST","/services/embeddings/text-embedding/text-embedding",Map.of("model",model,"input",Map.of("texts",texts),"parameters",Map.of("dimension",dimension,"text_type",type)),Map.of());
    }
    public JsonNode vectors(JsonNode response,int count) {
        var items=response.path("output").path("embeddings");
        if(!items.isArray()||items.size()!=count)throw new BailianHttp.Unavailable("Embedding 批次数量不符");
        var ordered=new TreeMap<Integer,JsonNode>();
        for(var item:items)if(!item.path("text_index").isIntegralNumber()||ordered.put(item.path("text_index").asInt(),item.path("embedding"))!=null)throw new BailianHttp.Unavailable("Embedding 批次身份重复");
        var result=json.createArrayNode();
        for(int i=0;i<count;i++){if(!ordered.containsKey(i))throw new BailianHttp.Unavailable("Embedding 批次身份缺失");result.add(ordered.get(i));}
        return result;
    }
}
