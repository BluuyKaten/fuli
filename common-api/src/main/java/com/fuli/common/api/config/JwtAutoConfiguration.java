package com.fuli.common.api.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * JWT 配置按需自动注册。
 *
 * <p>仅当配置了 {@code jwt.secret} 时才注册 {@link JwtProperties}，
 * 避免不依赖 JWT 的服务（如 data-service）被强制要求配置 JWT 密钥才能启动。
 *
 * <p>需要 JWT 的服务（auth-service、gateway-service）只需在配置中提供 {@code jwt.secret}，
 * 行为与之前一致。
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
@ConditionalOnProperty(prefix = "jwt", name = "secret")
public class JwtAutoConfiguration {
}
