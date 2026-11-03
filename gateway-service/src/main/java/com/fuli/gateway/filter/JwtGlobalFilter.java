package com.fuli.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuli.common.api.Result;
import com.fuli.common.api.util.TraceId;
import com.fuli.gateway.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
public class JwtGlobalFilter implements GlobalFilter, Ordered {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String TOKEN_PREFIX = "Bearer ";
    private static final String BLACKLIST_PREFIX = "auth:blacklist:";

    private final JwtUtil jwtUtil;
    private final ObjectProvider<ReactiveStringRedisTemplate> redisProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JwtGlobalFilter(JwtUtil jwtUtil, ObjectProvider<ReactiveStringRedisTemplate> redisProvider) {
        this.jwtUtil = jwtUtil;
        this.redisProvider = redisProvider;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 生成/透传请求追踪 ID：优先沿用客户端传入（清洗防日志注入），否则生成一个。
        String requestId = TraceId.sanitize(request.getHeaders().getFirst(TraceId.HEADER));
        if (!StringUtils.hasText(requestId)) {
            requestId = TraceId.generate();
        }
        final String traceId = requestId;
        exchange.getResponse().getHeaders().set(TraceId.HEADER, traceId);

        // Never expose service credentials or internal routes to external callers.
        if (path.contains("%") || path.contains("\\") || java.util.Arrays.stream(path.split("/"))
                .anyMatch(segment -> segment.split(";", 2)[0].equals("internal"))) {
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            return exchange.getResponse().setComplete();
        }
        request = request.mutate().headers(headers -> {
            headers.remove("X-Internal-Key");
            headers.remove("X-User-Id");
            headers.remove("X-Username");
            headers.set(TraceId.HEADER, traceId);
        }).build();
        exchange = exchange.mutate().request(request).build();

        if (isWhiteList(path)) {
            return chain.filter(exchange);
        }

        String token = getTokenFromRequest(request);
        if (!StringUtils.hasText(token)) {
            return unauthorized(exchange.getResponse(), "未提供认证令牌");
        }

        if (!jwtUtil.validateToken(token)) {
            return unauthorized(exchange.getResponse(), "令牌无效或已过期");
        }

        try {
            var claims = jwtUtil.parseToken(token);
            ServerHttpRequest mutatedRequest = request.mutate()
                    .header("X-User-Id", claims.get("userId", Long.class).toString())
                    .header("X-Username", claims.getSubject())
                    .build();
            ServerWebExchange mutatedExchange = exchange.mutate().request(mutatedRequest).build();
            String jti = claims.getId();
            ReactiveStringRedisTemplate redis = redisProvider.getIfAvailable();
            if (jti != null && redis != null) {
                return redis.hasKey(BLACKLIST_PREFIX + jti)
                        .onErrorResume(e -> {
                            log.warn("查询令牌黑名单失败，按未拉黑处理（fail-open）: {}", e.getMessage());
                            return Mono.just(false);
                        })
                        .flatMap(blacklisted -> blacklisted
                                ? unauthorized(mutatedExchange.getResponse(), "令牌已失效，请重新登录")
                                : chain.filter(mutatedExchange));
            }
            return chain.filter(mutatedExchange);
        } catch (Exception e) {
            log.error("JWT parse error: {}", e.getMessage());
            return unauthorized(exchange.getResponse(), "令牌解析失败");
        }
    }

    @Override
    public int getOrder() {
        return -100;
    }

    /**
     * 白名单路径（精确匹配前缀，避免 startsWith 绕过）。
     * 例如 /api/auth/login 放行，但 /api/auth/loginxxx 不放行。
     */
    private boolean isWhiteList(String path) {
        return path.equals("/api/auth/login")
                || path.equals("/api/auth/register")
                || path.equals("/api/auth/captcha")
                || path.startsWith("/api/auth/login/")
                || path.startsWith("/api/auth/register/")
                || path.startsWith("/api/auth/captcha/")
                || path.startsWith("/actuator/health")
                || path.startsWith("/actuator/info");
    }

    private String getTokenFromRequest(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(AUTHORIZATION_HEADER);
        if (StringUtils.hasText(header) && header.startsWith(TOKEN_PREFIX)) {
            return header.substring(TOKEN_PREFIX.length());
        }
        return null;
    }

    private Mono<Void> unauthorized(ServerHttpResponse response, String message) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json;charset=UTF-8");

        Result<?> result = Result.unauthorized(message);
        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(result);
        } catch (Exception e) {
            bytes = ("{\"code\":401,\"message\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        }

        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }
}
