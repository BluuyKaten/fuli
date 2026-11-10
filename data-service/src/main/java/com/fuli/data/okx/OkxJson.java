package com.fuli.data.okx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Jackson 2 树模型 → MVC 可序列化的普通对象（ADR-0010 实施记录）。
 *
 * <p><b>为什么需要这一层</b>：Spring Boot 4 的 MVC 默认用 **Jackson 3**
 * （{@code tools.jackson.databind}），而 {@link OkxClient} 为了解析 OKX 回包用的是
 * Jackson 2（{@code com.fasterxml.jackson.databind}，随 nacos 传递进来）。
 * Jackson 3 不认识 Jackson 2 的 {@code JsonNode}，会退化成「按 Bean 反射」，
 * 于是 {@code /data/okx/trade/balance} 这类端点返回的是一堆
 * {@code {"array":true,"nodeType":"ARRAY",...}} 元数据，而不是行情/账户数据。
 *
 * <p>修法是在**控制器边界**把树模型转成 {@code Map/List/String/Number/Boolean}：
 * 两种 Jackson 都能序列化普通集合，且不需要为了一个客户端去改全局 MVC 序列化器。
 *
 * <p>为什么不把 OkxClient 迁到 Jackson 3：OKX 回包解析只在客户端内部用，
 * 迁移要重写树模型 API 调用，收益为零；把「版本差异」收敛到一个静态方法里更划算。
 */
public final class OkxJson {

    private static final ObjectMapper JSON = new ObjectMapper();

    private OkxJson() {
    }

    /**
     * @return {@code LinkedHashMap / ArrayList / String / Integer / Long / Double / Boolean / null}
     */
    public static Object toPlain(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return JSON.convertValue(node, Object.class);
    }
}
