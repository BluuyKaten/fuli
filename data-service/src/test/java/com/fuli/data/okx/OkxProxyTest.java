package com.fuli.data.okx;

import com.fuli.data.config.OkxConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 代理配置解析（运维最容易踩的一个格式坑）。
 *
 * <p>手册里写的是 {@code 127.0.0.1:7897}（Clash 的混合口），而 {@code URI.create}
 * 会把它当 scheme 解析并抛 "Illegal character in scheme name"，表现为服务启动失败。
 * 两种写法都必须能起服务。
 */
class OkxProxyTest {

    @Test
    @DisplayName("裸 host:port 与带协议两种写法等价")
    void acceptsBothForms() {
        InetSocketAddress bare = OkxClient.proxyAddress("127.0.0.1:7897");
        InetSocketAddress withScheme = OkxClient.proxyAddress("http://127.0.0.1:7897");
        assertEquals("127.0.0.1", bare.getHostString());
        assertEquals(7897, bare.getPort());
        assertEquals(bare, withScheme);
    }

    @Test
    void blankMeansDirectConnection() {
        assertNull(OkxClient.proxyAddress(null));
        assertNull(OkxClient.proxyAddress(""));
        assertNull(OkxClient.proxyAddress("   "));
    }

    @Test
    @DisplayName("缺端口或格式乱：启动期给出可操作的错误，而不是 StackTrace 里一句 scheme 错")
    void invalidProxyFailsWithGuidance() {
        assertThrows(IllegalStateException.class, () -> OkxClient.proxyAddress("127.0.0.1"));
        assertThrows(IllegalStateException.class, () -> OkxClient.proxyAddress("http://:7897"));
        assertThrows(IllegalStateException.class, () -> OkxClient.proxyAddress("ht tp://1.2.3.4:1"));
    }

    @Test
    @DisplayName("构造 OkxClient 时就能发现代理写错（不留到第一次请求）")
    void constructorValidatesProxy() {
        OkxConfig config = new OkxConfig();
        config.setProxy("127.0.0.1");
        assertThrows(IllegalStateException.class, () -> new OkxClient(config));
    }
}
