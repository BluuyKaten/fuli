package com.fuli.data.security;

import com.fuli.data.config.OkxAuthProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 用户级交易权限判定的 fail-closed 语义（ADR-0010 决策 2）。
 *
 * <p>「查不到就放行」在这个场景里等于把可用性故障升级成资金风险，所以三个方向都必须返回 false：
 * auth-service 不可达、内部密钥没配、userId 非法。
 */
class OkxTradePermissionClientTest {

    private static OkxTradePermissionClient client(String internalKey, String baseUrl) {
        OkxAuthProperties properties = new OkxAuthProperties();
        properties.setInternalKey(internalKey);
        properties.setPermissionBaseUrl(baseUrl);
        return new OkxTradePermissionClient(properties);
    }

    @Test
    @DisplayName("内部密钥未配置：拒绝（并只告警一次）")
    void missingInternalKeyDenies() {
        assertFalse(client("", "http://127.0.0.1:8081").isTradeEnabled(1L));
    }

    @Test
    @DisplayName("auth-service 不可达：拒绝，不缓存失败结果")
    void unreachableAuthServiceDenies() {
        OkxTradePermissionClient client = client("some-internal-key", "http://127.0.0.1:1");
        assertFalse(client.isTradeEnabled(1L));
        // 第二次仍然尝试（不缓存失败），仍然拒绝
        assertFalse(client.isTradeEnabled(1L));
    }

    @Test
    @DisplayName("非法 userId：直接拒绝，连请求都不发")
    void invalidUserIdDenies() {
        assertFalse(client("some-internal-key", "http://127.0.0.1:1").isTradeEnabled(0L));
        assertFalse(client("some-internal-key", "http://127.0.0.1:1").isTradeEnabled(-3L));
    }
}
