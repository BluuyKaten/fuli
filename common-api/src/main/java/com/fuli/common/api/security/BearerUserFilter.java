package com.fuli.common.api.security;

import com.fuli.common.api.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/** Verifies the original signed token even when a service is reached without the gateway. */
public class BearerUserFilter extends OncePerRequestFilter {
    private final JwtProperties properties;
    private TokenBlacklistService tokenBlacklistService;

    public BearerUserFilter(JwtProperties properties) {
        this.properties = properties;
    }

    /** 可选注入：仅当服务启用了令牌黑名单（Redis + fuli.token-blacklist.enabled）时才校验。 */
    @Autowired(required = false)
    public void setTokenBlacklistService(TokenBlacklistService tokenBlacklistService) {
        this.tokenBlacklistService = tokenBlacklistService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Long userId;
        try {
            String authorization = request.getHeader("Authorization");
            if (authorization == null || !authorization.startsWith("Bearer ")) {
                throw new IllegalArgumentException("Missing bearer");
            }
            Claims claims = Jwts.parser()
                    .verifyWith(Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8)))
                    .build().parseSignedClaims(authorization.substring(7)).getPayload();
            userId = claims.get("userId", Long.class);
            if (claims.getExpiration() == null || !claims.getExpiration().after(new Date())
                    || userId == null || userId <= 0) {
                throw new IllegalArgumentException("Invalid identity");
            }
            if (tokenBlacklistService != null && tokenBlacklistService.isBlacklisted(claims.getId())) {
                throw new IllegalArgumentException("Token revoked");
            }
        } catch (RuntimeException invalidToken) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"令牌无效或未提供\"}");
            return;
        }
        request.setAttribute(AuthenticatedUser.ATTRIBUTE, userId);
        chain.doFilter(request, response);
    }
}
