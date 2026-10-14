package com.agenticvideo.business.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolJsonTest {
    @Test void matchesPythonUtf8Fingerprints() throws Exception {
        try(var input=getClass().getResourceAsStream("/contracts/python-fingerprints.json")) {
            for(var fixture:new ObjectMapper().readTree(input))assertEquals(fixture.path("hash").asText(),ToolJson.hash(fixture.path("value")));
        }
    }
}
