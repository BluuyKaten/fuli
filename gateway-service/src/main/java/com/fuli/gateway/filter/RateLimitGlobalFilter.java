package com.fuli.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuli.common.api.Result;
import com.fuli.gateway.ratelimit.RateLimitProperties;
import com.fuli.gateway.ratelimit.RateLimitRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * 全局限流过滤器。
 *
 * <p>运行在 {@link JwtGlobalFilter}（order -100）之后：已认证请求的 {@code X-User-Id}
 * 已由 JWT 过滤器注入，故<b>已认证请求按 userId 限流</b>；登录/注册等白名单路径无 userId，
 * 则<b>按客户端 IP 限流</b>（防爆破）。健康检查 {@code /actuator/**} 永不限流。
 *
 * <p>超限返回 429 + {@code Retry-After}；任何限流器内部异常都放行（fail-open）——
 * 限流是保护性能力，不应成为新的单点。
 */
@Slf4j
@Component
public class RateLimitGlobalFilter implements GlobalFilter, Ordered {

    private static final String RATE_LIMITED_MESSAGE = "请求过于频繁，请稍后再试";

    private final RateLimitProperties properties;
    private final RateLimitRegistry registry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RateLimitGlobalFilter(RateLimitProperties properties, RateLimitRegistry registry) {
        this.properties = properties;
        this.registry = registry;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!properties.isEnabled()) {
            return chain.filter(exchange);
        }

        String path = exchange.getRequest().getURI().getPath();
        if (isExempt(path)) {
            return chain.filter(exchange);
        }

        RateLimitProperties.Rule rule = properties.ruleFor(path);
        String key = resolveKey(exchange.getRequest());

        boolean allowed;
        try {
            allowed = registry.acquire(key, rule.getReplenishRate(), rule.getBurstCapacity());
        } catch (Exception e) {
            log.warn("限流器异常，按放行处理（fail-open）: {}", e.getMessage());
            return chain.filter(exchange);
        }
        if (allowed) {
            return chain.filter(exchange);
        }

        log.warn("触发限流: key={} path={}", key, path);
        return tooManyRequests(exchange.getResponse());
    }

    @Override
    public int getOrder() {
        // 必须在 JwtGlobalFilter(-100) 之后，才能读到注入的 X-User-Id。
        return -90;
    }

    /** 健康检查永不限流；登录/注册虽在白名单里，但正需要防爆破，故不豁免。 */
    private boolean isExempt(String path) {
        return path.startsWith("/actuator/");
    }

    /** 已认证 → userId；未认证 → 客户端 IP。 */
    private String resolveKey(ServerHttpRequest request) {
        String userId = request.getHeaders().getFirst("X-User-Id");
        if (StringUtils.hasText(userId)) {
            return "user:" + userId;
        }
        return "ip:" + clientIp(request);
    }

    private String clientIp(ServerHttpRequest request) {
        String xff = request.getHeaders().getFirst("X-Forwarded-For");
        if (StringUtils.hasText(xff)) {
            int comma = xff.indexOf(',');
            String first = (comma < 0 ? xff : xff.substring(0, comma)).trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        InetSocketAddress remote = request.getRemoteAddress();
        if (remote != null && remote.getAddress() != null) {
            return remote.getAddress().getHostAddress();
        }
        return "unknown";
    }

    private Mono<Void> tooManyRequests(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set("Retry-After", "1");

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(Result.error(HttpStatus.TOO_MANY_REQUESTS.value(),
                    RATE_LIMITED_MESSAGE));
        } catch (Exception e) {
            bytes = ("{\"code\":429,\"message\":\"" + RATE_LIMITED_MESSAGE + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }
}
