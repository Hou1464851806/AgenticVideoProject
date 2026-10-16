package com.agenticvideo.business.agentrun;

import com.agenticvideo.business.tools.ToolJson;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentRunContractsTest {
    private final ObjectMapper json = new ObjectMapper();
    private JsonNode sample() throws Exception {
        return json.readTree(getClass().getResourceAsStream("/contracts/conversation-lifecycle.json"));
    }
    @Test void sharedWireRoundtripAndFingerprints() throws Exception {
        for (String key : new String[]{"inputs", "results"}) {
            for (var item : sample().path(key)) {
                var value = item.path("value");
                Object parsed = key.equals("inputs")
                        ? AgentRunContracts.read(value, AgentRunContracts.RunInput.class)
                        : AgentRunContracts.read(value, AgentRunContracts.RunResult.class);
                assertEquals(value, json.readTree(json.writeValueAsString(parsed)));
                assertEquals(item.path("hash").asText(), ToolJson.hash(json.valueToTree(parsed)));
            }
        }
        for (var value : sample().path("parsed"))
            assertEquals(value, json.readTree(json.writeValueAsString(AgentRunContracts.read(value, AgentRunContracts.ParsedInput.class))));
    }
    @Test void sharedInvalidWireValues() throws Exception {
        var sample = sample();
        for (var item : sample.path("invalid")) {
            String target = item.path("target").asText();
            var value = (target.equals("parsed") ? sample.path("parsed").get(item.path("baseIndex").asInt(0))
                    : sample.path(target.equals("input") ? "inputs" : "results").get(item.path("baseIndex").asInt(0)).path("value")).deepCopy();
            var path = item.path("path");
            JsonNode node = value;
            for (int i=0; i<path.size()-1; i++) node = node.path(path.get(i).asText());
            var last = path.get(path.size()-1).asText();
            if (item.path("remove").asBoolean()) ((ObjectNode)node).remove(last);
            else ((ObjectNode)node).set(last, item.get("value"));
            Class<?> type = switch(target) {
                case "input" -> AgentRunContracts.RunInput.class;
                case "result" -> AgentRunContracts.RunResult.class;
                default -> AgentRunContracts.ParsedInput.class;
            };
            assertThrows(IllegalArgumentException.class, () -> AgentRunContracts.read(value, type), item.path("name").asText());
        }
    }
    @Test void totalByteBudgetIncludesHistoryAndReferences() throws Exception {
        var value = (ObjectNode)sample().path("inputs").get(0).path("value").deepCopy();
        var message = ((ObjectNode)value.path("history").get(0)).deepCopy().put("content", "字".repeat(16000));
        var history = value.putArray("history");
        for (int i=0; i<30; i++) history.add(message);
        var error = assertThrows(IllegalArgumentException.class, () -> AgentRunContracts.read(value, AgentRunContracts.RunInput.class));
        assertTrue(error.getMessage().contains("byte limit"));
    }
}
