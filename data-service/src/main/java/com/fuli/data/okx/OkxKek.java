package com.fuli.data.okx;

import java.util.Base64;

/**
 * {@code OKX_CRED_KEK} 的格式解析（ADR-0010 决策 1）。
 *
 * <p>独立成类是为了让「格式容错」和「加解密」分开测试：KEK 来自运维，
 * 格式写错的概率远高于算法用错，报错信息必须直接告诉对方该怎么生成。
 */
public final class OkxKek {

    /** AES-256 要求 32 字节。 */
    public static final int KEY_BYTES = 32;

    private OkxKek() {
    }

    /**
     * 解析 KEK。推荐 {@code openssl rand -base64 32}。
     *
     * @throws IllegalStateException 缺失、长度不对或格式无法识别（启动期 fail-fast）
     */
    public static byte[] parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException(
                    "OKX 凭证加密密钥未配置：请设置环境变量 OKX_CRED_KEK（32 字节，"
                            + "推荐 `openssl rand -base64 32` 生成）。"
                            + "该密钥是凭证密文的唯一解密入口，缺失时服务不允许启动。");
        }
        String value = raw.trim();

        // ① 64 位十六进制
        if (value.matches("^[0-9a-fA-F]{64}$")) {
            return hex(value);
        }
        // ② Base64（44 字符带 = 或 43 字符无填充）
        byte[] decoded = tryBase64(value);
        if (decoded != null && decoded.length == KEY_BYTES) {
            return decoded;
        }
        // ③ 恰好 32 字节原始 ASCII（本地调试便利，不推荐生产）
        byte[] utf8 = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (utf8.length == KEY_BYTES) {
            return utf8;
        }
        throw new IllegalStateException(
                "OKX_CRED_KEK 格式不合法：需要 32 字节密钥，当前解析结果 "
                        + (decoded == null ? utf8.length + " 字节（非 Base64/Hex）" : decoded.length + " 字节（Base64）")
                        + "。支持 Base64(32B) / 64 位 Hex / 32 字节 ASCII，推荐 `openssl rand -base64 32`。");
    }

    private static byte[] tryBase64(String value) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException notBase64) {
            return null;
        }
    }

    private static byte[] hex(String value) {
        byte[] out = new byte[KEY_BYTES];
        for (int i = 0; i < KEY_BYTES; i++) {
            out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
