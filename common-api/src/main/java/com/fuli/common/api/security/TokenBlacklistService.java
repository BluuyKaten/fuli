package com.fuli.common.api.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * 令牌黑名单服务（基于 Redis）：登出后把 JWT 的 jti 加入黑名单，
 * 使令牌在自然过期前立即失效。
 *
 * <p>黑名单键 {@code auth:blacklist:<jti>}，TTL 设为令牌剩余有效期，到期自动清理。
 * 查询失败按「未拉黑」处理（fail-open），避免 Redis 故障导致全员被拒。
 */
@Slf4j
public class TokenBlacklistService {

    private static final String KEY_PREFIX = "auth:blacklist:";

    private final StringRedisTemplate redis;

    public TokenBlacklistService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void blacklist(String jti, Duration ttl) {
        try {
            redis.opsForValue().set(KEY_PREFIX + jti, "1", ttl);
        } catch (Exception e) {
            log.warn("令牌加入黑名单失败（该令牌将有效至自然过期）: {}", e.getMessage());
        }
    }

    public boolean isBlacklisted(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + jti));
        } catch (Exception e) {
            log.warn("查询令牌黑名单失败，按未拉黑处理（fail-open）: {}", e.getMessage());
            return false;
        }
    }
}
