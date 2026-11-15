package com.fuli.data.okx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OkxOrderResponseTest {

    @Test
    void firstAndFieldsReadFromArrayHead() {
        ObjectNode item = JsonNodeFactory.instance.objectNode()
                .put("ordId", "ord-1")
                .put("sCode", "0")
                .put("sMsg", "");

        OkxOrderResponse response = OkxOrderResponse.from(JsonNodeFactory.instance.arrayNode().add(item));

        assertEquals(item, response.first());
        assertEquals("ord-1", response.ordId());
        assertEquals("0", response.sCode());
        assertEquals("", response.sMsg());
    }

    @Test
    void nullAndEmptyResponsesHaveNullFirstAndFields() {
        for (JsonNode raw : new JsonNode[]{null, JsonNodeFactory.instance.arrayNode()}) {
            OkxOrderResponse response = OkxOrderResponse.from(raw);
            assertNull(response.first());
            assertNull(response.ordId());
            assertNull(response.sCode());
            assertNull(response.sMsg());
        }
    }

    @Test
    void missingFieldsHaveNullValues() {
        JsonNode raw = JsonNodeFactory.instance.arrayNode().add(JsonNodeFactory.instance.objectNode());

        OkxOrderResponse response = OkxOrderResponse.from(raw);

        assertEquals(raw.get(0), response.first());
        assertNull(response.ordId());
        assertNull(response.sCode());
        assertNull(response.sMsg());
    }
}
