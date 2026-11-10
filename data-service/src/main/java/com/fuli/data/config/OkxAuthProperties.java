package com.fuli.data.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * OKX 端点的鉴权配置（ADR-0009 决策 8 / ADR-0010 决策 2）。
 *
 * <p>安全默认值是「fail-closed」：没注入 {@code JWT_SECRET} 时所有 OKX 端点返回 503；
 * 查不到用户的 {@code okx_trade_enabled} 时一律按「无写权限」处理。
 *
 * <p>历史说明：本类曾有一个 {@code write-user-ids} 环境变量白名单，
 * 已由 {@code sys_user.okx_trade_enabled} 取代（ADR-0010 决策 2）——
 * 白名单是运维改配置，用户级开关是数据、可审计、可授权到个人。
 */
@Data
@Component
@ConfigurationProperties(prefix = "okx.auth")
public class OkxAuthProperties {

    /**
     * 与 gateway-service / auth-service 共用的 JWT 签名密钥，来自环境变量 {@code JWT_SECRET}。
     * 为空或过短时，受保护端点一律拒绝，而不是放行。
     */
    private String jwtSecret = "";

    /**
     * auth-service 基址，用于查询用户的 {@code okx_trade_enabled}。
     * 默认本机 8081；容器化部署时改成服务地址。
     */
    private String permissionBaseUrl = "http://127.0.0.1:8081";

    /**
     * 调用 auth-service 内部接口的密钥，必须与 auth-service 的 {@code fuli.internal-key} 一致
     * （环境变量 {@code INTERNAL_KEY}）。为空时写操作一律拒绝。
     */
    private String internalKey = "";

    /**
     * 权限查询缓存秒数。0 表示不缓存（每次都问 auth-service）。
     *
     * <p>缓存换来的是延迟与可用性，代价是「收回权限」最迟延迟这么久生效，见手册。
     */
    private long permissionCacheSeconds = 30;

    /** 服务令牌的受众标识，必须与 auth-service 签发时写入的 aud 一致（防 token passthrough）。 */
    private String serviceTokenAudience = "fuli-data";

    /** JWT 密钥未配置或过弱时返回 true，用于 fail-closed 判定。 */
    public boolean isJwtSecretMissing() {
        return jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32;
    }
}
