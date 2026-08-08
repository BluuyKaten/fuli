package com.fuli.common.api.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 内部服务密钥按需自动注册。
 *
 * <p>仅当配置了 {@code fuli.internal-key} 时才注册 {@link InternalKeyProperties}，
 * 避免不依赖内部服务调用的服务被强制要求配置密钥才能启动。
 */
@Configuration
@EnableConfigurationProperties(InternalKeyProperties.class)
@ConditionalOnProperty(prefix = "fuli", name = "internal-key")
public class InternalKeyAutoConfiguration {
}
