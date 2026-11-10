package com.fuli.ai.security;

import com.fuli.ai.config.AiAuthProperties;
import com.fuli.common.api.security.AuthenticatedUser;
import com.fuli.common.api.security.RequestPaths;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * ai-service 的身份入口（ADR-0010 决策 4）。
 *
 * <p>为什么不直接信网关塞的 {@code X-User-Id}：网关会剥离外部伪造的身份头，但
 * ai-service 也可能被内网直连（8085）。系统里「身份只信服务端 attribute」这条规则，
 * 靠的正是**每个服务自己验一遍签名**，而不是信任上游的转发结果。
 *
 * <p>只接受用户令牌：{@code type=SERVICE} 的令牌在这里一律拒绝。
 * 服务身份用于「ai-service 调 data-service」，不是用来调自己的用户端点，
 * 混用会让审计里出现「某个服务以用户身份提建议」这种说不清主体记录。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AiAuthenticationFilter extends OncePerRequestFilter {

    private final AiAuthProperties properties;

    public AiAuthenticationFilter(AiAuthProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = RequestPaths.applicationPath(request);
        // 健康检查要能在没有密钥时也被探活，否则部署平台永远看不到 UP
        return path.equals("/ai/health") || path.startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (properties.isJwtSecretMissing()) {
            deny(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "ai-service 鉴权未配置：请注入与网关一致的 JWT_SECRET（>=32 字节）");
            return;
        }
        Long userId;
        try {
            String authorization = request.getHeader("Authorization");
            if (authorization == null || !authorization.startsWith("Bearer ")) {
                throw new IllegalArgumentException("Missing bearer");
            }
            Claims claims = Jwts.parser()
                    .verifyWith(Keys.hmacShaKeyFor(
                            properties.getJwtSecret().getBytes(StandardCharsets.UTF_8)))
                    .build().parseSignedClaims(authorization.substring(7)).getPayload();
            if ("SERVICE".equalsIgnoreCase(claims.get("type", String.class))) {
                throw new IllegalArgumentException("Service token cannot call user endpoints");
            }
            userId = claims.get("userId", Long.class);
            if (claims.getExpiration() == null || !claims.getExpiration().after(new Date())
                    || userId == null || userId <= 0) {
                throw new IllegalArgumentException("Invalid identity");
            }
        } catch (RuntimeException invalidToken) {
            deny(response, HttpServletResponse.SC_UNAUTHORIZED, "令牌无效或未提供");
            return;
        }
        request.setAttribute(AuthenticatedUser.ATTRIBUTE, userId);
        chain.doFilter(request, response);
    }

    private static void deny(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + status + ",\"message\":\"" + message + "\"}");
    }
}
