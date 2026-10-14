package com.agenticvideo.business.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Python fingerprint: sorted keys, compact UTF-8 JSON; tool arguments contain only integers and strings. */
public final class ToolJson {
    private ToolJson() {}
    public static String hash(JsonNode value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical(value).getBytes(StandardCharsets.UTF_8)));
        } catch(java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
    private static String canonical(JsonNode node) {return sorted(node).toString();}
    private static JsonNode sorted(JsonNode node) {
        if(node.isObject()) {
            var result=JsonNodeFactory.instance.objectNode();var keys=new TreeSet<String>();node.fieldNames().forEachRemaining(keys::add);
            for(String key:keys)result.set(key,sorted(node.get(key)));return result;
        }
        if(node.isArray()){var result=JsonNodeFactory.instance.arrayNode();node.forEach(item->result.add(sorted(item)));return result;}
        return node;
    }
}
