package com.fuli.data.okx;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 签名是「静默失败」的典型：错一个字符，OKX 只回 50113，不会告诉你是 query 没进签名还是
 * timestamp 精度不对。这里用**独立实现算出的基准值**（Python hmac/base64，不是拿本类自证），
 * 把算法钉死：
 *
 * <pre>
 *   sign = Base64( HMAC-SHA256( timestamp + METHOD + requestPath + body ) )
 * </pre>
 */
class OkxSignerTest {

    private static final String SECRET = "22582BD0CFF14C41EDBF1AB98506286D";

    private static final String TS = "2020-12-08T09:08:57.715Z";

    @Test
    @DisplayName("GET：query 必须进签名串（基准值由独立实现算出）")
    void signsGetWithQuery() {
        String sign = OkxSigner.sign(SECRET, TS, "GET", "/api/v5/account/balance?ccy=BTC", "");
        assertEquals("HiZhvSfMtWJA3uUIVXV3a/bSXNPCWvYFXoGCVS8V4zY=", sign);
    }

    @Test
    @DisplayName("POST：body 原样参与签名，紧凑 JSON 不能重新格式化")
    void signsPostWithExactBody() {
        String body = "{\"instId\":\"BTC-USDT\",\"tdMode\":\"cash\",\"side\":\"buy\","
                + "\"ordType\":\"limit\",\"px\":\"30000\",\"sz\":\"0.01\"}";
        String sign = OkxSigner.sign(SECRET, TS, "POST", "/api/v5/trade/order", body);
        assertEquals("y5z1i+XgTRJPve/SN5k+H4148I9cp7IgBzgfgqITM9E=", sign);
    }

    @Test
    @DisplayName("method 传小写也要按大写签（HTTP 方法大小写不该影响结果）")
    void methodIsCaseInsensitive() {
        assertEquals(
                OkxSigner.sign(SECRET, TS, "GET", "/api/v5/public/time", ""),
                OkxSigner.sign(SECRET, TS, "get", "/api/v5/public/time", ""));
    }

    @Test
    @DisplayName("时间戳/路径/body 任一变化都必须改变签名")
    void anyComponentChangeChangesSignature() {
        String base = OkxSigner.sign(SECRET, TS, "GET", "/api/v5/account/balance", "");
        assertNotEquals(base, OkxSigner.sign(SECRET, TS, "GET", "/api/v5/account/balance?ccy=BTC", ""));
        assertNotEquals(base, OkxSigner.sign(SECRET, "2020-12-08T09:08:57.716Z", "GET", "/api/v5/account/balance", ""));
        assertNotEquals(base, OkxSigner.sign(SECRET, TS, "POST", "/api/v5/account/balance", ""));
    }

    @Test
    @DisplayName("空 secret 直接抛，不要发出一份没有签名的请求")
    void emptySecretFailsFast() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> OkxSigner.sign("", TS, "GET", "/api/v5/public/time", ""));
        assertTrue(e.getMessage().contains("secretKey"));
    }

    @Test
    @DisplayName("timestamp 是 ISO-8601 毫秒 UTC，长度与结尾固定")
    void timestampFormat() {
        String ts = OkxSigner.timestamp();
        assertEquals(24, ts.length(), "yyyy-MM-ddTHH:mm:ss.SSSZ 共 24 字符");
        assertTrue(ts.endsWith("Z"));
        assertEquals('T', ts.charAt(10));
        assertEquals('.', ts.charAt(19));
    }
}
