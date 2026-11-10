package com.fuli.data.okx.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 订阅参数解析与重连重放（实施计划 P3）。
 *
 * <p>配置里用简写避免 YAML 里塞一堆 JSON：{@code account}、{@code orders:ANY}、
 * {@code positions:SWAP}、{@code tickers:BTC-USDT}；也允许直接写完整 JSON 原样透传。
 *
 * <p><b>第二段按频道解释</b>（这里搞错会被交易所直接拒订，且错误信息很难懂）：
 * <ul>
 *   <li>{@code account} → {@code ccy}</li>
 *   <li>{@code positions} / {@code orders} → {@code instType}</li>
 *   <li>其余（tickers / candle* / books* / trades / funding-rate / mark-price / open-interest
 *       等公共频道）→ {@code instId}</li>
 * </ul>
 */
public final class OkxWsSubscription {

    private static final ObjectMapper JSON = new ObjectMapper();

    private OkxWsSubscription() {
    }

    /** 把一条配置解析成订阅参数对象（不含 op / args 外壳）。 */
    public static ObjectNode parse(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("空的订阅配置");
        }
        String s = spec.trim();
        if (s.startsWith("{")) {
            try {
                JsonNode node = JSON.readTree(s);
                if (!node.isObject() || !node.hasNonNull("channel")) {
                    throw new IllegalArgumentException("订阅 JSON 缺 channel：" + s);
                }
                return (ObjectNode) node;
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException("订阅 JSON 解析失败：" + s, e);
            }
        }

        String[] parts = s.split(":", 2);
        String channel = parts[0].trim();
        if (channel.isEmpty()) {
            throw new IllegalArgumentException("订阅缺 channel：" + s);
        }
        ObjectNode node = JSON.createObjectNode();
        node.put("channel", channel);
        if (parts.length > 1 && !parts[1].isBlank()) {
            node.put(paramName(channel), parts[1].trim());
        }
        return node;
    }

    /** 频道 → 第二段参数名。私有频道按类型，公共频道按标的。 */
    static String paramName(String channel) {
        return switch (channel) {
            case "account" -> "ccy";
            case "positions", "orders" -> "instType";
            default -> "instId";
        };
    }

    /** 生成可直接发送的 {@code {"op":"subscribe","args":[...]}}。 */
    public static String subscribeMessage(List<String> specs) {
        List<ObjectNode> args = new ArrayList<>();
        for (String spec : specs) {
            args.add(parse(spec));
        }
        ObjectNode root = JSON.createObjectNode();
        root.put("op", "subscribe");
        root.set("args", JSON.valueToTree(args));
        try {
            return JSON.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("订阅消息序列化失败", e);
        }
    }
}
