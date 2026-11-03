package com.fuli.common.api.config;

import com.fuli.common.api.security.TokenBlacklistService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 令牌黑名单按需装配：仅 servlet 应用、引入 Redis 且显式开启 {@code fuli.token-blacklist.enabled=true}
 * 时才注册 {@link TokenBlacklistService}，避免不依赖 Redis 的服务被迫引入。
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(StringRedisTemplate.class)
@ConditionalOnProperty(prefix = "fuli.token-blacklist", name = "enabled", havingValue = "true")
public class TokenBlacklistAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TokenBlacklistService tokenBlacklistService(StringRedisTemplate redis) {
        return new TokenBlacklistService(redis);
    }
}
