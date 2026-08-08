package com.fuli.common.api.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 内部服务调用密钥统一配置。
 *
 * <p>trade-service / analysis-service 的 Feign 拦截器、auth-service 的过滤器共用此类，
 * 确保内部密钥只有一处定义，消除多服务间密钥漂移风险。
 *
 * <p>启动时会校验密钥强度：长度 {@code >= 16} 且不是已知弱默认值，
 * 不满足则直接启动失败。
 *
 * <p>通过 {@code InternalKeyAutoConfiguration} 按需注册：仅当配置了 {@code fuli.internal-key} 时才生效，
 * 避免不依赖内部服务调用的服务被强制要求配置。
 */
@Data
@ConfigurationProperties(prefix = "fuli")
public class InternalKeyProperties {

    /**
     * 内部服务调用密钥。<b>必须</b>通过环境变量 {@code INTERNAL_KEY} 注入，禁止依赖默认值。
     */
    private String internalKey;

    /**
     * 是否允许高敏感内部操作（如 {@code resetCash} 直接改写余额）。
     *
     * <p>默认 {@code false}（生产环境禁用），仅本地开发/测试环境可置为 {@code true}。
     * 配合 {@code application-local.yml} 使用，避免生产环境被直接操纵现金。
     */
    private boolean allowDangerousOperations = false;

    private static final String[] WEAK_DEFAULTS = {
            "fuli-stock-internal-2025-secure-key",
            "your-internal-key-here-change-me",
            "internal-key",
            "change-me",
            "secret"
    };

    @PostConstruct
    public void validate() {
        if (internalKey == null || internalKey.isBlank()) {
            throw new IllegalStateException(
                    "内部服务密钥未配置：fuli.internal-key 为空。请通过环境变量 INTERNAL_KEY 注入至少 16 字节的强密钥。");
        }
        if (internalKey.length() < 16) {
            throw new IllegalStateException(
                    "内部服务密钥过短：当前长度 " + internalKey.length() + "，要求 >= 16 字节。请更换更强的 INTERNAL_KEY。");
        }
        for (String weak : WEAK_DEFAULTS) {
            if (internalKey.equals(weak)) {
                throw new IllegalStateException(
                        "内部服务密钥为已知弱默认值，禁止用于生产。请更换环境变量 INTERNAL_KEY。");
            }
        }
    }
}
