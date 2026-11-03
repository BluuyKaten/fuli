package com.fuli.trade.websocket;

import com.fuli.common.api.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/**
 * WebSocket 握手鉴权拦截器：建立连接前校验 JWT，未认证的握手直接以 401 拒绝。
 *
 * <p>令牌优先从 {@code Authorization: Bearer ...} 头读取（供可自定义头的客户端使用），
 * 否则回退到握手 URL 的 {@code token} 查询参数（浏览器原生 WebSocket 无法自定义请求头）。
 * 注意：token 走查询参数会出现在访问日志中，生产环境建议配合短期令牌或网关侧脱敏。
 */
@Slf4j
@RequiredArgsConstructor
public class AuthHandshakeInterceptor implements HandshakeInterceptor {

    private final JwtProperties jwtProperties;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        Long userId = parseUserId(extractToken(request));
        if (userId == null) {
            log.warn("[WebSocket] 握手鉴权失败，拒绝连接: {}", request.getURI().getPath());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put("userId", userId);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }

    private String extractToken(ServerHttpRequest request) {
        String authorization = request.getHeaders().getFirst("Authorization");
        if (StringUtils.hasText(authorization) && authorization.startsWith("Bearer ")) {
            return authorization.substring(7);
        }
        String query = request.getURI().getQuery();
        if (StringUtils.hasText(query)) {
            for (String pair : query.split("&")) {
                int idx = pair.indexOf('=');
                if (idx > 0 && "token".equals(pair.substring(0, idx))) {
                    return pair.substring(idx + 1);
                }
            }
        }
        return null;
    }

    private Long parseUserId(String token) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8)))
                    .build().parseSignedClaims(token).getPayload();
            Long userId = claims.get("userId", Long.class);
            if (claims.getExpiration() == null || !claims.getExpiration().after(new Date())
                    || userId == null || userId <= 0) {
                return null;
            }
            return userId;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
