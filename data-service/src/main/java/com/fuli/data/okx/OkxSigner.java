package com.fuli.data.okx;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * OKX V5 签名。四个头里最容易错的是「签什么」：
 *
 * <pre>
 *   sign = Base64( HMAC-SHA256( timestamp + METHOD + requestPath + body, secretKey ) )
 * </pre>
 *
 * <ul>
 *   <li>{@code requestPath} 必须带 query（{@code /api/v5/account/balance?ccy=BTC}），
 *       query 放 body 里或忘记拼都会 50113；</li>
 *   <li>{@code timestamp} 就是发出去的 {@code OK-ACCESS-TIMESTAMP} 原字符串，精度到毫秒的 ISO-8601 UTC，
 *       自己再 format 一次就等于换了值；</li>
 *   <li>GET 的 body 传空串（不是 {@code "{}"}，也不是 null 拼成 "null"）。</li>
 * </ul>
 */
public final class OkxSigner {

    private static final DateTimeFormatter ISO_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private static final String HMAC_SHA256 = "HmacSHA256";

    private OkxSigner() {
    }

    /** 当前时刻的 OKX 时间戳（ISO-8601 毫秒 UTC），同一值必须同时用于签名与请求头。 */
    public static String timestamp() {
        return ISO_MILLIS.format(Instant.now());
    }

    /**
     * @param timestamp 与请求头 {@code OK-ACCESS-TIMESTAMP} 完全一致
     * @param method    HTTP 方法，大写
     * @param path      含 query 的路径
     * @param body      请求体；GET 传空串
     */
    public static String sign(String secretKey, String timestamp, String method,
                              String path, String body) {
        if (secretKey == null || secretKey.isEmpty()) {
            throw new IllegalStateException("OKX secretKey 未配置");
        }
        String payload = timestamp + method.toUpperCase() + path + (body == null ? "" : body);
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            return Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("OKX 签名失败: " + e.getMessage(), e);
        }
    }
}
