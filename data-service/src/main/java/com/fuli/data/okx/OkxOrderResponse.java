package com.fuli.data.okx;

import com.fasterxml.jackson.databind.JsonNode;

/** OKX 成功响应中 data 数组的首条订单结果。 */
public record OkxOrderResponse(String ordId, String sCode, String sMsg) {

    /** 从 OkxClient 返回的 data 数组读取首条结果。 */
    public static OkxOrderResponse first(JsonNode data) {
        if (data == null || !data.isArray() || data.size() == 0) {
            return new OkxOrderResponse(null, null, null);
        }
        JsonNode item = data.get(0);
        if (item == null || !item.isObject()) {
            return new OkxOrderResponse(null, null, null);
        }
        return new OkxOrderResponse(
                textOrNull(item, "ordId"),
                textOrNull(item, "sCode"),
                textOrNull(item, "sMsg"));
    }

    public boolean accepted() {
        return "0".equals(sCode) && ordId != null && !ordId.isBlank();
    }

    private static String textOrNull(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? null : value;
    }
}
