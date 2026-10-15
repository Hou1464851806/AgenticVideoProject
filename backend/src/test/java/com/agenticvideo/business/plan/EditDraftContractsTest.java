package com.agenticvideo.business.plan;

import com.agenticvideo.business.tools.ToolJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EditDraftContractsTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test void sharedMultilingualContractsAndFingerprints() throws Exception {
        var sample = json.readTree(getClass().getResourceAsStream("/contracts/m2-editing.json"));
        var input = EditDraftContracts.validation(sample.path("validation"));
        assertEquals(sample.path("validation"), json.readTree(json.writeValueAsString(input)));
        assertEquals(input.draft_hash(), ToolJson.hash(json.valueToTree(input.draft())));
        assertEquals(input.requirements_hash(), ToolJson.hash(json.valueToTree(input.requirements())));
        EditDraftContracts.read(sample.path("validation_result"), EditDraftContracts.ValidationResult.class);
        EditDraftContracts.read(sample.path("saved_result"), EditDraftContracts.SavedResult.class);
    }

    @Test void sharedInvalidValuesAreRejectedWithoutCoercion() throws Exception {
        var sample = json.readTree(getClass().getResourceAsStream("/contracts/m2-editing.json"));
        for (var item : sample.path("invalid")) {
            var value = sample.path("validation").deepCopy();
            var path = item.path("path");
            JsonNode node = value;
            for (int i=0; i<path.size()-1; i++) node = path.get(i).isInt() ? node.path(path.get(i).intValue()) : node.path(path.get(i).asText());
            var last = path.get(path.size()-1);
            if (last.isInt()) ((com.fasterxml.jackson.databind.node.ArrayNode)node).set(last.intValue(), item.get("value"));
            else if (item.path("remove").asBoolean()) ((com.fasterxml.jackson.databind.node.ObjectNode)node).remove(last.asText());
            else ((com.fasterxml.jackson.databind.node.ObjectNode)node).set(last.asText(), item.get("value"));
            assertThrows(IllegalArgumentException.class, () -> EditDraftContracts.validation(value), item.path("name").asText());
        }
    }
    @Test void payloadByteLimitIsExplicit() throws Exception {
        var sample = json.readTree(getClass().getResourceAsStream("/contracts/m2-editing.json")).path("validation");
        var clips = (com.fasterxml.jackson.databind.node.ArrayNode)sample.at("/draft/outputs/0/clips");
        var clip = (com.fasterxml.jackson.databind.node.ObjectNode)clips.get(0);
        clip.put("reason", "字".repeat(500));
        for (int i=1; i<100; i++) clips.add(clip.deepCopy());
        var error = assertThrows(IllegalArgumentException.class, () -> EditDraftContracts.validation(sample));
        assertTrue(error.getMessage().contains("byte limit"));
    }

    @Test void outputAndClipCountBoundariesAndReorderedKeys() throws Exception {
        var sample = json.readTree(getClass().getResourceAsStream("/contracts/m2-editing.json")).path("validation");
        var outputs = (com.fasterxml.jackson.databind.node.ArrayNode)sample.at("/draft/outputs");
        var conditions = (com.fasterxml.jackson.databind.node.ArrayNode)sample.at("/requirements/outputs");
        var output = (com.fasterxml.jackson.databind.node.ObjectNode)outputs.get(0).deepCopy();
        var condition = (com.fasterxml.jackson.databind.node.ObjectNode)conditions.get(0).deepCopy();
        outputs.removeAll(); conditions.removeAll();
        for (int n=0; n<10; n++) {
            outputs.insert(0,output.deepCopy().put("output_key","out-"+n));
            conditions.add(condition.deepCopy().put("output_key","out-"+n));
        }
        assertEquals(10,EditDraftContracts.validation(sample).draft().outputs().size());
        outputs.add(output.deepCopy().put("output_key","extra"));
        assertThrows(IllegalArgumentException.class, () -> EditDraftContracts.validation(sample));
        var clipsSample = json.readTree(getClass().getResourceAsStream("/contracts/m2-editing.json")).path("validation");
        var clips = (com.fasterxml.jackson.databind.node.ArrayNode)clipsSample.at("/draft/outputs/0/clips");
        for (int n=1; n<100; n++) clips.add(clips.get(0).deepCopy());
        assertEquals(100,EditDraftContracts.validation(clipsSample).draft().outputs().get(0).clips().size());
        clips.add(clips.get(0).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> EditDraftContracts.validation(clipsSample));
    }
    @Test void feedbackHasItsOwnByteLimit() throws Exception {
        var sample = json.readTree(getClass().getResourceAsStream("/contracts/m2-editing.json")).path("validation_result");
        var issues = (com.fasterxml.jackson.databind.node.ArrayNode)sample.path("issues");
        var issue = (com.fasterxml.jackson.databind.node.ObjectNode)issues.get(0);
        issue.put("message","字".repeat(200));
        for (int n=1; n<100; n++) issues.add(issue.deepCopy());
        assertThrows(IllegalArgumentException.class, () -> EditDraftContracts.read(sample,EditDraftContracts.ValidationResult.class));
    }
}
