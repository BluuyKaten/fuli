package com.fuli.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * ai-service 的鉴权与出站配置（ADR-0010 决策 4）。
 *
 * <p>为什么不复用 common-api 的 {@code JwtProperties}：那个类在 {@code jwt.secret} 存在时
 * 就会做强校验，而 {@code ${JWT_SECRET:}} 会「存在但为空」，导致本地不设环境变量就起不来。
 * 这里改成 fail-closed 的运行时判定：缺密钥时只有受保护端点 503，服务本身照常启动，
 * 与 data-service 的 {@code OkxAuthProperties} 保持同一套语义。
 */
@Data
@Component
@ConfigurationProperties(prefix = "fuli.ai")
public class AiAuthProperties {

    /** 与 auth-service / gateway / data-service 共用的 JWT 密钥。缺失时 /ai/** 一律 503。 */
    private String jwtSecret = "";

    /** 调用 data-service 内部接口（写建议单）的密钥，须与 auth-service 的 fuli.internal-key 一致。 */
    private String internalKey = "";

    /** 服务账号 client_id，需在 auth-service 的 fuli.agent.clients 里登记。 */
    private String clientId = "ai-service";

    /** 服务账号密钥，来自环境变量 {@code AI_SERVICE_CLIENT_SECRET}。 */
    private String clientSecret = "";

    /** auth-service 基址，用于换取服务令牌。 */
    private String authServiceUrl = "http://127.0.0.1:8081";

    /** 定时刷新间隔（毫秒）。令牌有效期 15 分钟，默认 10 分钟刷一次，留 5 分钟余量。 */
    private long tokenRefreshMs = 600_000L;

    /** JWT 密钥未配置或过弱时返回 true。 */
    public boolean isJwtSecretMissing() {
        return jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32;
    }

    /** 服务账号是否已配置（缺任一项都不去换取令牌，直接降级）。 */
    public boolean isServiceAccountConfigured() {
        return clientId != null && !clientId.isBlank()
                && clientSecret != null && !clientSecret.isBlank();
    }
}
