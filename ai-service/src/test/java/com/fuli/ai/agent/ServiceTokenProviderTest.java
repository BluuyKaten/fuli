package com.fuli.ai.agent;

import com.fuli.ai.config.AiAuthProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 服务令牌获取的降级行为（ADR-0010 决策 4）。
 *
 * <p>没有配置 client_secret 时必须返回 null 让上层降级，
 * 而不是抛异常把 /ai/ask 整个打断，更不能回退去用某个用户令牌。
 */
class ServiceTokenProviderTest {

    private static AiAuthProperties props(String clientSecret) {
        AiAuthProperties properties = new AiAuthProperties();
        properties.setClientId("ai-service");
        properties.setClientSecret(clientSecret);
        // 指向一个不可能有服务在听的端口：即使被调用也只会快速失败
        properties.setAuthServiceUrl("http://127.0.0.1:1");
        return properties;
    }

    @Test
    @DisplayName("未配置 client_secret：直接返回 null，不发请求")
    void unconfiguredClientYieldsNull() {
        ServiceTokenProvider provider = new ServiceTokenProvider(props(""));
        assertNull(provider.token());
        assertFalse(provider.hasValidToken());
    }

    @Test
    @DisplayName("auth-service 不可用：返回 null 而不是抛异常")
    void unreachableAuthServiceYieldsNull() {
        ServiceTokenProvider provider = new ServiceTokenProvider(props("some-secret"));
        assertNull(provider.token());
        assertFalse(provider.hasValidToken());
    }
}
