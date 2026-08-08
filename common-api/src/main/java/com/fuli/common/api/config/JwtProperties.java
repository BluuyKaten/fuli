package com.fuli.common.api.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 密钥统一配置。
 *
 * <p>auth-service 与 gateway-service 共用此类，确保签发与校验使用同一密钥，
 * 消除双 {@code jwt.secret} 默认值漂移风险。
 *
 * <p>启动时会校验密钥强度：长度 {@code >= 32} 且不是已知弱默认值，
 * 不满足则直接启动失败，避免生产环境使用弱密钥运行。
 */
@Data
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /**
     * JWT 签名密钥。<b>必须</b>通过环境变量 {@code JWT_SECRET} 注入，禁止依赖默认值。
     */
    private String secret;

    /** 过期时间（毫秒），默认 24 小时。 */
    private Long expiration = 86400000L;

    /**
     * 已知的弱默认值集合。启动时若 secret 命中其中之一，视为未配置，启动失败。
     */
    private static final String[] WEAK_DEFAULTS = {
            "your-256-bit-secret-key-here-must-be-at-least-32-characters-long",
            "default-secret-key-for-dev-only-please-change-in-production-32chars",
            "your-secret-key",
            "secret",
            "change-me"
    };

    @PostConstruct
    public void validate() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT 密钥未配置：jwt.secret 为空。请通过环境变量 JWT_SECRET 注入至少 32 字节的强密钥。");
        }
        if (secret.length() < 32) {
            throw new IllegalStateException(
                    "JWT 密钥过短：当前长度 " + secret.length() + "，要求 >= 32 字节。请更换更强的 JWT_SECRET。");
        }
        for (String weak : WEAK_DEFAULTS) {
            if (secret.equals(weak)) {
                throw new IllegalStateException(
                        "JWT 密钥为已知弱默认值，禁止用于生产。请更换环境变量 JWT_SECRET。");
            }
        }
    }
}
