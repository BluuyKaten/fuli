package com.fuli.auth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 服务账号（client_credentials）配置（ADR-0010 决策 4）。
 *
 * <p>为什么需要它：ai-service 要调用 data-service 的受保护只读端点，但它没有「某个用户的令牌」。
 * 三条路可选：① 让 Agent 复用用户令牌（**token passthrough**，MCP 规范禁止，也把用户权限
 * 借给了后台进程）；② 免鉴权内网直连（等于没有鉴权）；③ 独立服务身份 + 最小 scope。
 * 选③。
 *
 * <p>安全默认值：{@code clients} 为空 = 没有任何服务账号可以取令牌（fail-closed）；
 * {@code secret} 为空 = 该客户端不可用。都不需要额外开关，配置缺失本身就是关闭。
 *
 * <p>本期只允许 {@code orders:read}。**不要**给服务账号加 {@code orders:write}：
 * data-service 侧对 SERVICE 主体的写操作是硬拒绝（{@code OkxAuthz.requireWrite}），
 * 加了 scope 也调不动，只会在排障时制造误解。
 */
@Data
@Component
@ConfigurationProperties(prefix = "fuli.agent")
public class ServiceClientProperties {

    /** client_id → 客户端定义。 */
    private Map<String, Client> clients = new LinkedHashMap<>();

    /** 服务令牌有效期（秒），默认 15 分钟。ai-service 会在到期前定时刷新。 */
    private long tokenTtlSeconds = 900;

    /** 受众标识，必须与 data-service 的 {@code okx.auth.service-token-audience} 一致。 */
    private String tokenAudience = "fuli-data";

    @Data
    public static class Client {

        /** 共享密钥，来自环境变量（如 {@code AI_SERVICE_CLIENT_SECRET}），不进代码库。 */
        private String secret = "";

        /** 允许申请的 scope，空格或逗号分隔。默认只有读。 */
        private String scopes = "orders:read";

        /** 显式停用某个客户端，不需要删配置。 */
        private boolean enabled = true;
    }
}
