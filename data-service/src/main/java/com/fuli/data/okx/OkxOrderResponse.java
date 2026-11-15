package com.fuli.data.okx;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * OKX 下单成功响应的数组契约边界。
 *
 * <p>{@link OkxClient} 成功时只返回响应 envelope 中的 {@code data} 数组，
 * 所有交易调用方都通过本类读取首项，避免再次误把数组当成完整 envelope。</p>
 */
public final class OkxOrderResponse {

    private final JsonNode first;

    private OkxOrderResponse(JsonNode first) {
        this.first = first;
    }

    /** 从客户端返回的 data 数组解析首项。null、空数组和非对象首项都按缺少结果处理。 */
    public static OkxOrderResponse from(JsonNode response) {
        if (response == null || !response.isArray() || response.isEmpty()) {
            return new OkxOrderResponse(null);
        }
        JsonNode first = response.get(0);
        return new OkxOrderResponse(first != null && first.isObject() ? first : null);
    }

    /** 首项对象；没有首项时返回 null。 */
    public JsonNode first() {
        return first;
    }

    /** 交易所订单 ID；字段缺失或 null 时返回 null。 */
    public String ordId() {
        return text("ordId");
    }

    /** 入口业务状态码；字段缺失或 null 时返回 null。 */
    public String sCode() {
        return text("sCode");
    }

    /** 入口业务消息；字段缺失或 null 时返回 null。 */
    public String sMsg() {
        return text("sMsg");
    }

    private String text(String field) {
        if (first == null || !first.has(field) || first.get(field).isNull()) {
            return null;
        }
        return first.get(field).asText(null);
    }
}
