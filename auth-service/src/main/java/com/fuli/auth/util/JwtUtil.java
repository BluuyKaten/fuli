package com.fuli.auth.util;

import com.fuli.common.api.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * JWT 工具类（auth-service 端）。
 *
 * <p>密钥统一由 {@link JwtProperties} 注入，启动时会校验强度，
 * 与 gateway-service 端共用同一密钥，消除双密钥漂移风险。
 */
@Slf4j
@Component
public class JwtUtil {

    private final JwtProperties jwtProperties;

    /** @param jwtProperties 统一 JWT 密钥配置（来自 common-api） */
    public JwtUtil(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(Long userId, String username) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("username", username);

        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtProperties.getExpiration());

        return Jwts.builder()
                .claims(claims)
                .subject(username)
                .issuedAt(now)
                .expiration(expiryDate)
                .id(UUID.randomUUID().toString())
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * 服务令牌（client_credentials，ADR-0010 决策 4）。
     *
     * <p>与用户令牌的区别全在 claim 上，签名密钥与算法完全相同：
     * <ul>
     *   <li>没有 {@code userId}——它不是任何一个用户；</li>
     *   <li>{@code type=SERVICE}——data-service 据此走「只读」分支；</li>
     *   <li>{@code scope} / {@code aud}——按最小权限与受众签发，data-service 会校验 aud，
     *       因此这张票拿到别的服务不被承认（这是「禁止 token passthrough」的另一半）。</li>
     * </ul>
     *
     * @param ttlSeconds 有效期，默认 15 分钟；短有效期让「泄露后重放」的窗口足够小
     */
    public String generateServiceToken(String clientId, String scope, long ttlSeconds,
                                       String audience) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + Math.max(1, ttlSeconds) * 1000L);
        return Jwts.builder()
                .subject(clientId)
                .claim("type", "SERVICE")
                .claim("scope", scope)
                .audience().add(audience).and()
                .issuedAt(now)
                .expiration(expiry)
                .id(UUID.randomUUID().toString())
                .signWith(getSigningKey())
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean validateToken(String token) {
        try {
            Claims claims = parseToken(token);
            return !claims.getExpiration().before(new Date());
        } catch (Exception e) {
            log.warn("JWT validation failed: {}", e.getMessage());
            return false;
        }
    }

    public String getUsernameFromToken(String token) {
        return parseToken(token).getSubject();
    }

    public Long getUserIdFromToken(String token) {
        return parseToken(token).get("userId", Long.class);
    }

    /** 过期时间（秒），供登录响应返回，与签发逻辑保持单一事实来源。 */
    public long getExpirationSeconds() {
        return jwtProperties.getExpiration() / 1000;
    }
}
