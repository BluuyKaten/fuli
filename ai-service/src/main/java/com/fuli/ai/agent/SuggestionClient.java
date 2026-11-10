package com.fuli.ai.agent;

import com.fuli.ai.config.AiAuthProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把 Agent 下单建议写到 data-service（ADR-0010 决策 4）。
 *
 * <p>走的是 {@code /internal/okx/suggestion} + {@code X-Internal-Key}，不是 service token：
 * 服务令牌的 scope 被刻意限死在 {@code orders:read}，如果为了写建议单给它加写 scope，
 * 「Agent 只读」这条边界就从「协议禁止」退化成「约定不写」。
 *
 * <p>接口只落建议单，**不会触发任何下单**——data-service 侧那个 controller 只依赖
 * {@code OkxSuggestionService}，连 {@code OkxTradeService} 都没有注入。
 */
@Slf4j
@Component
public class SuggestionClient {

    private final RestClient dataClient;

    private final AiAuthProperties properties;

    public SuggestionClient(@Value("${fuli.data-service-url}") String dataServiceUrl,
                            AiAuthProperties properties) {
        this.dataClient = RestClient.builder().baseUrl(dataServiceUrl).build();
        this.properties = properties;
    }

    /**
     * @return data-service 的原始响应体（含建议单 id 与状态）
     * @throws IllegalStateException 未配置内部密钥或对端拒绝
     */
    public String submit(long userId, String instId, String side, String ordType,
                         String sz, String px, String reason) {
        if (properties.getInternalKey() == null || properties.getInternalKey().isBlank()) {
            throw new IllegalStateException(
                    "ai-service 未配置 fuli.ai.internal-key，无法写建议单（请注入与 auth-service 一致的 INTERNAL_KEY）");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        payload.put("instId", instId);
        payload.put("side", side);
        payload.put("ordType", ordType);
        payload.put("sz", sz);
        if (px != null && !px.isBlank()) {
            payload.put("px", px);
        }
        if (reason != null && !reason.isBlank()) {
            payload.put("reason", reason);
        }

        String body = dataClient.post()
                .uri("/internal/okx/suggestion")
                .header("X-Internal-Key", properties.getInternalKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(String.class);
        log.info("[AI-SUGGEST] 已提交建议 userId={} {} {} sz={} px={}", userId, instId, side, sz, px);
        return body;
    }
}
