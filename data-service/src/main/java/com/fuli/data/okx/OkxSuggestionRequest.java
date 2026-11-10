package com.fuli.data.okx;

import java.math.BigDecimal;

/**
 * Agent 提交下单建议的请求（内部接口 {@code POST /internal/okx/suggestion}）。
 *
 * <p>{@code userId} 是**必填**的：这条内部接口只允许服务账号调用，服务侧没有 JWT 可取身份，
 * 因此由调用方（ai-service）在验完用户令牌后显式传入。接口本身只做「记录」，
 * 即使被伪造，后果也只是多一条建议单，不会产生任何下单请求。
 */
public record OkxSuggestionRequest(Long userId, String instId, String side, String ordType,
                                   BigDecimal sz, BigDecimal px, String reason, BigDecimal confidence) {
}
