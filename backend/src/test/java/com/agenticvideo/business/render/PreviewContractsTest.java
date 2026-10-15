package com.agenticvideo.business.render;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PreviewContractsTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void sharedPreviewWireValues() throws Exception {
        var sample=json.readTree(getClass().getResourceAsStream("/contracts/m4-preview.json"));
        assertEquals("out-1",PreviewContracts.request(sample.path("request")).output_key());
        for(var input:sample.path("selection_requests"))
            assertEquals(input.has("output_key")?input.path("output_key").asText():null,PreviewContracts.request(input).output_key());
        for(var result:sample.path("results"))assertEquals(result,json.readTree(json.writeValueAsString(json.treeToValue(result,PreviewContracts.Result.class))));
        assertEquals(sample.path("list"),json.valueToTree(json.treeToValue(sample.path("list"),PreviewContracts.ListResult.class)));
    }
    @Test void invalidOutputSelectionCannotInjectRenderInputs() throws Exception {
        for(String input:new String[]{"null","[]","{\"output_id\":\"foreign\"}","{\"output_key\":1}","{\"output_key\":null}","{\"output_key\":\"\"}"})
            assertThrows(IllegalArgumentException.class,()->PreviewContracts.request(json.readTree(input)),input);
    }
}
