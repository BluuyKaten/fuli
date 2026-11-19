package com.fuli.data.okx;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OkxOrderResponseTest {

    @Test
    void readsOrderFieldsFromClientDataArray() {
        ArrayNode data = JsonNodeFactory.instance.arrayNode();
        data.addObject().put("ordId", "ord-7").put("sCode", "0").put("sMsg", "");

        OkxOrderResponse response = OkxOrderResponse.first(data);

        assertEquals("ord-7", response.ordId());
        assertEquals("0", response.sCode());
        assertTrue(response.accepted());
    }

    @Test
    void emptyOrEnvelopeResponseIsNotAccepted() {
        assertFalse(OkxOrderResponse.first(JsonNodeFactory.instance.arrayNode()).accepted());
        assertFalse(OkxOrderResponse.first(JsonNodeFactory.instance.objectNode()).accepted());
        assertNull(OkxOrderResponse.first(null).ordId());
    }
}
