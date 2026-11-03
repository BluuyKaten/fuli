package com.fuli.common.api.util;

import java.util.UUID;

/**
 * 请求追踪 ID 的统一常量与工具，供网关与各 servlet 服务共用，避免键名/格式漂移。
 *
 * <p>链路：网关生成/透传 {@code X-Request-Id} → 各服务 {@code TraceIdFilter} 落到 MDC
 * （键 {@code traceId}）→ 日志 pattern 用 {@code %X{traceId:-}} 打印，从而按请求串联日志。
 */
public final class TraceId {

    /** HTTP 头名，用于网关与各服务间透传追踪 ID。 */
    public static final String HEADER = "X-Request-Id";

    /** MDC 键名，日志 pattern 中以 {@code %X{traceId:-}} 引用。 */
    public static final String MDC_KEY = "traceId";

    private TraceId() {
    }

    /** 生成一个无连字符的 UUID 作为 traceId。 */
    public static String generate() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 清洗客户端传入值：仅保留字母数字与 ._-，最长 64 字符，防止日志注入。 */
    public static String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("[^A-Za-z0-9._-]", "");
        return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
    }
}
