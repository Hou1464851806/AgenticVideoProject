package com.agenticvideo.business.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Python fingerprint: sorted keys, compact UTF-8 JSON; tool arguments contain only integers and strings. */
public final class ToolJson {
    private static final ObjectMapper JSON = JsonMapper.builder().disable(JsonWriteFeature.WRITE_HEX_UPPER_CASE).build();
    private ToolJson() {}
    public static String hash(JsonNode value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JSON.writeValueAsString(sorted(value)).getBytes(StandardCharsets.UTF_8)));
        } catch(java.security.NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException impossible) {throw new IllegalStateException(impossible);}
    }
    private static JsonNode sorted(JsonNode node) {
        if(node.isObject()) {
            var result=JsonNodeFactory.instance.objectNode();var keys=new TreeSet<String>();node.fieldNames().forEachRemaining(keys::add);
            for(String key:keys)result.set(key,sorted(node.get(key)));return result;
        }
        if(node.isArray()){var result=JsonNodeFactory.instance.arrayNode();node.forEach(item->result.add(sorted(item)));return result;}
        return node;
    }
}
