package com.fuli.data.okx.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuli.data.okx.OkxSigner;

import java.time.Instant;

/**
 * OKX WS 私有频道登录消息（实施计划 P3）。
 *
 * <p><b>与 REST 签名的唯一差别是时间戳格式</b>：REST 用 ISO-8601 毫秒 UTC，
 * WS 登录用 <b>Unix 秒字符串</b>。签名算法本身一致，因此直接复用 {@link OkxSigner#sign}：
 * <pre>
 *   prehash = timestamp + "GET" + "/users/self/verify" + ""   // body 恒为空串
 *   sign    = Base64(HMAC-SHA256(prehash, secretKey))
 * </pre>
 * 注意 timestamp 是秒（如 1704876947），不是毫秒、不是 ISO 字符串；签完发出去的
 * 必须是同一个字符串，重算一次时间就会报 60007 / 60009。请求 30 秒后过期。
 *
 * <p>本类只构造消息，不持有也不打印凭证。
 */
public final class OkxWsLogin {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String VERIFY_PATH = "/users/self/verify";

    private OkxWsLogin() {
    }

    /** 用当前 Unix 秒构造登录消息。 */
    public static String message(String apiKey, String passphrase, String secretKey) {
        return message(apiKey, passphrase, secretKey, Instant.now().getEpochSecond());
    }

    /** 显式传入时间戳，供测试用固定值验证签名；生产请用 {@link #message(String, String, String)}。 */
    public static String message(String apiKey, String passphrase, String secretKey, long epochSecond) {
        String ts = Long.toString(epochSecond);
        String sign = OkxSigner.sign(secretKey, ts, "GET", VERIFY_PATH, "");
        ObjectNode arg = JSON.createObjectNode();
        arg.put("apiKey", apiKey);
        arg.put("passphrase", passphrase);
        arg.put("timestamp", ts);
        arg.put("sign", sign);
        ObjectNode root = JSON.createObjectNode();
        root.put("op", "login");
        root.set("args", JSON.createArrayNode().add(arg));
        try {
            return JSON.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("WS 登录消息序列化失败", e);
        }
    }
}
