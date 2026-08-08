package com.fuli.common.api.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * common-api 公共配置。
 *
 * <p>显式启用 {@link JwtProperties} 与 {@link InternalKeyProperties}，
 * 确保作为依赖被其他服务引入时，这两个配置类能被正确注册为 Spring Bean。
 */
@Configuration
@EnableConfigurationProperties({JwtProperties.class, InternalKeyProperties.class})
public class CommonLibConfig {
}
