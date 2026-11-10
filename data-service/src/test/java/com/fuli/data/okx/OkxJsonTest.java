package com.fuli.data.okx;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Jackson2 树模型 → 普通对象的转换（Boot 4 双 Jackson 版本问题）。
 *
 * <p>Spring Boot 4 的 MVC 用 Jackson 3（{@code tools.jackson}），而 {@code OkxClient} 用
 * Jackson 2（{@code com.fasterxml}）。如果不转换，OKX 端点会把 {@code JsonNode} 当 Bean
 * 序列化成 {@code {"array":true,"nodeType":"ARRAY",...}}——数据全丢。
 */
class OkxJsonTest {

    @Test
    @DisplayName("数组 → List<Map>，字段原样保留")
    void arrayBecomesListOfMaps() {
        var arr = JsonNodeFactory.instance.arrayNode();
        arr.addObject().put("instId", "BTC-USDT").put("last", "100.5");

        Object plain = OkxJson.toPlain(arr);

        assertTrue(plain instanceof List, "必须是 List，否则 MVC 会按 Bean 反射");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) plain;
        assertEquals("BTC-USDT", list.get(0).get("instId"));
        assertEquals("100.5", list.get(0).get("last"), "OKX 数值都是字符串，不要被转成 double");
    }

    @Test
    @DisplayName("对象 → Map；嵌套数组也一并转换")
    void objectBecomesMap() {
        var obj = JsonNodeFactory.instance.objectNode();
        obj.put("code", "0");
        obj.putArray("details").addObject().put("ccy", "USDT");

        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) OkxJson.toPlain(obj);

        assertEquals("0", map.get("code"));
        assertTrue(map.get("details") instanceof List);
    }

    @Test
    @DisplayName("null / MissingNode → null，不抛异常")
    void nullAndMissingBecomeNull() {
        assertNull(OkxJson.toPlain(null));
        assertNull(OkxJson.toPlain(JsonNodeFactory.instance.nullNode()));
        assertNull(OkxJson.toPlain(JsonNodeFactory.instance.missingNode()));
    }
}
