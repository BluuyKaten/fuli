package com.fuli.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuli.ai.config.AiAuthProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 服务令牌的定时刷新与缓存（ADR-0010 决策 4）。
 *
 * <p><b>禁止 token passthrough</b>：ai-service 从不转发用户令牌。它用自己的
 * client_id / client_secret 换一张 {@code type=SERVICE}、{@code scope=orders:read}、
 * 15 分钟过期的票，data-service 只按这张票的 scope 放行读操作。
 * 因此「Agent 拿用户令牌去查账户」和「Agent 拿服务令牌去下单」两条路都走不通。
 *
 * <p>刷新的两个触发点：
 * <ol>
 *   <li>{@code @Scheduled} 每 {@code fuli.ai.token-refresh-ms}（默认 10 分钟）主动换新，
 *       避免请求到来时才发现过期；</li>
 *   <li>取用时若剩余寿命不足 2 分钟，同步再换一次（兜底：调度线程被阻塞或刚启动）。</li>
 * </ol>
 *
 * <p>失败处理：拿不到票就返回 null，调用方降级为「不含账户数据」，绝不带着空 token 发请求，
 * 也绝不回退去用某个用户令牌。
 */
@Slf4j
@Component
public class ServiceTokenProvider {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 剩余寿命低于这个值就提前换票。 */
    private static final long REFRESH_AHEAD_MILLIS = 120_000L;

    private final AiAuthProperties properties;

    private final RestClient authClient;

    private final AtomicBoolean warnedNotConfigured = new AtomicBoolean(false);

    private volatile Cached cached;

    private record Cached(String token, long expiresAtMillis) {
    }

    public ServiceTokenProvider(AiAuthProperties properties) {
        this.properties = properties;
        this.authClient = RestClient.builder().baseUrl(properties.getAuthServiceUrl()).build();
    }

    /** 定时刷新：失败只记日志，不影响下一次。 */
    @Scheduled(fixedDelayString = "${fuli.ai.token-refresh-ms:600000}", initialDelay = 5_000L)
    public void refresh() {
        if (!properties.isServiceAccountConfigured()) {
            return;
        }
        try {
            fetch();
        } catch (Exception e) {
            log.warn("[AI-TOKEN] 定时刷新服务令牌失败：{}", e.getMessage());
        }
    }

    /**
     * 取当前可用的服务令牌。
     *
     * @return null 表示暂时拿不到（未配置 / auth-service 不可用），调用方必须降级而不是重试风暴
     */
    public String token() {
        if (!properties.isServiceAccountConfigured()) {
            if (warnedNotConfigured.compareAndSet(false, true)) {
                log.warn("[AI-TOKEN] 未配置服务账号（fuli.ai.client-id / client-secret），"
                        + "受保护端点将不可调用；Agent 只读能力降级为「仅公共行情」");
            }
            return null;
        }
        Cached current = cached;
        if (current != null && current.expiresAtMillis() - System.currentTimeMillis() > REFRESH_AHEAD_MILLIS) {
            return current.token();
        }
        try {
            return fetch().token();
        } catch (Exception e) {
            log.warn("[AI-TOKEN] 换取服务令牌失败：{}", e.getMessage());
            // 旧票只要还没过期就先用着，别因为一次刷新失败把能力全掐掉
            return current != null && current.expiresAtMillis() > System.currentTimeMillis()
                    ? current.token() : null;
        }
    }

    private Cached fetch() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", properties.getClientId());
        form.add("client_secret", properties.getClientSecret());
        form.add("scope", "orders:read");

        String body = authClient.post()
                .uri("/auth/oauth/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class);
        JsonNode root;
        try {
            root = JSON.readTree(body == null ? "{}" : body);
        } catch (Exception parseFailure) {
            throw new IllegalStateException("auth-service 返回的不是合法 JSON：" + body, parseFailure);
        }
        String token = root.path("access_token").asText("");
        if (token.isBlank()) {
            throw new IllegalStateException("auth-service 未返回 access_token：" + body);
        }
        long expiresIn = root.path("expires_in").asLong(900);
        Cached fresh = new Cached(token, System.currentTimeMillis() + expiresIn * 1000L);
        cached = fresh;
        log.info("[AI-TOKEN] 服务令牌已刷新 clientId={} scope={} ttl={}s",
                properties.getClientId(), root.path("scope").asText(""), expiresIn);
        return fresh;
    }

    /** 供健康检查展示，不暴露令牌本身。 */
    public boolean hasValidToken() {
        Cached current = cached;
        return current != null && current.expiresAtMillis() > System.currentTimeMillis();
    }
}
