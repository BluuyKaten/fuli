package com.fuli.data.okx;

import com.fuli.data.config.OkxConfig;
import com.fuli.data.crypto.CryptoOrderService;
import com.fuli.data.crypto.CryptoOrderState;
import com.fuli.data.crypto.entity.CryptoOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 下单的闸门必须真的挡住，而不是「日志里 warn 一下然后照发」。
 *
 * <p>尤其第一条：OKX 模拟盘与实盘同 host，只差 {@code x-simulated-trading} 头，
 * 所以「关掉下单」这件事只能在**发请求之前**拦住——发出去就没有撤回的余地了。
 *
 * <p>ADR-0010 之后多了两条闸：凭证按用户取（没有全局回退），
 * 以及「没有凭证时连写前日志都不该留」。
 */
class OkxTradeGuardTest {

    private static final long USER = 7L;

    private OkxConfig config;

    private OkxClient client;

    private CryptoOrderService orderLog;

    private OkxCredentialService credentials;

    private OkxTradeService service;

    @BeforeEach
    void setUp() {
        config = new OkxConfig();
        client = mock(OkxClient.class);
        orderLog = mock(CryptoOrderService.class);
        credentials = mock(OkxCredentialService.class);
        when(credentials.resolve(USER)).thenReturn(new OkxCredentials("key", "secret", "pass"));
        // 写前日志默认返回一笔刚落库的 pending 单，正常下单路径才能继续走
        when(orderLog.createPending(any())).thenAnswer(inv -> {
            CryptoOrderService.NewOrder req = inv.getArgument(0);
            CryptoOrder o = new CryptoOrder();
            o.setClOrdId(req.clOrdId());
            o.setState(CryptoOrderState.PENDING.getCode());
            return o;
        });
        service = new OkxTradeService(config, client, orderLog, credentials);
    }

    @Test
    @DisplayName("全局总闸关着：抛异常，且一个请求都不发")
    void refusesWhenDisabled() {
        config.setTradeEnabled(false);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.placeOrder(USER, "BTC-USDT.CRYPTO", "buy", "limit", "0.01", "30000", null));
        assertTrue(e.getMessage().contains("trade-enabled"), "消息要指路到配置项");
        verifyNoInteractions(client);
        verifyNoInteractions(orderLog);
    }

    @Test
    @DisplayName("开闸但该用户没配凭证：拦住，且不留 pending 记录")
    void refusesWithoutUserCredentials() {
        config.setTradeEnabled(true);
        when(credentials.resolve(USER)).thenThrow(new OkxCredentialException("尚未配置凭证"));

        OkxCredentialException e = assertThrows(OkxCredentialException.class,
                () -> service.placeOrder(USER, "BTC-USDT.CRYPTO", "buy", "limit", "0.01", "30000", null));
        assertTrue(e.getMessage().contains("凭证"));
        verifyNoInteractions(client);
        // 没有凭证就不该写前日志，否则库里会堆一堆永远不会发出去的 pending
        verifyNoInteractions(orderLog);
    }

    @Test
    @DisplayName("OkxConfig 里不存在任何凭证字段（禁止回退到全局环境变量）")
    void configHasNoCredentialFields() {
        String[] forbidden = {"apiKey", "secretKey", "passphrase"};
        for (Field field : OkxConfig.class.getDeclaredFields()) {
            assertFalse(Arrays.asList(forbidden).contains(field.getName()),
                    "OkxConfig 不应再有 " + field.getName() + "：凭证只能按用户从 okx_account 解密");
        }
    }

    @Test
    @DisplayName("OkxCredentials 的 toString 不泄露 secret / passphrase")
    void credentialsToStringIsMasked() {
        OkxCredentials creds = new OkxCredentials("AKID1234567890ABCD", "SECRET-MUST-NOT-LEAK", "PASS-MUST-NOT-LEAK");
        String text = creds.toString();
        assertFalse(text.contains("SECRET-MUST-NOT-LEAK"));
        assertFalse(text.contains("PASS-MUST-NOT-LEAK"));
        assertTrue(text.contains("AKID****ABCD"));
    }

    @Test
    @DisplayName("超过单笔上限：拒绝")
    void refusesOversizedOrder() {
        config.setTradeEnabled(true);
        config.setMaxOrderSize("0.5");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.placeOrder(USER, "BTC-USDT.CRYPTO", "buy", "limit", "1", "30000", null));
        assertTrue(e.getMessage().contains("超过上限"));
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("参数校验：side / ordType / 市价带价 / 数量非正")
    void validatesParams() {
        config.setTradeEnabled(true);
        assertThrows(IllegalArgumentException.class,
                () -> service.placeOrder(USER, "BTC-USDT.CRYPTO", "hold", "limit", "0.01", "1", null));
        assertThrows(IllegalArgumentException.class,
                () -> service.placeOrder(USER, "BTC-USDT.CRYPTO", "buy", "stop", "0.01", "1", null));
        assertThrows(IllegalArgumentException.class,
                () -> service.placeOrder(USER, "BTC-USDT.CRYPTO", "buy", "market", "0.01", "30000", null));
        assertThrows(IllegalArgumentException.class,
                () -> service.placeOrder(USER, "BTC-USDT.CRYPTO", "buy", "limit", "0", "30000", null));
        assertThrows(IllegalArgumentException.class,
                () -> service.placeOrder(USER, "BTC-USDT.CRYPTO", "buy", "limit", "0.01", null, null));
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("非 CRYPTO 符号不许发给 OKX")
    void refusesForeignMarket() {
        config.setTradeEnabled(true);
        assertThrows(IllegalArgumentException.class,
                () -> service.placeOrder(USER, "600519.SH", "buy", "limit", "0.01", "30000", null));
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("放行时：tdMode 固定 cash、instId 去后缀、clOrdId 透传、凭证来自当前用户")
    @SuppressWarnings("unchecked")
    void sendsCashSpotOrder() {
        config.setTradeEnabled(true);
        when(client.privatePost(any(OkxCredentials.class), anyString(), any())).thenReturn(null);

        service.placeOrder(USER, "BTC-USDT.CRYPTO", "buy", "limit", "0.0100", "30000.00", "cli-1");

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<OkxCredentials> used = ArgumentCaptor.forClass(OkxCredentials.class);
        verify(client).privatePost(used.capture(), eq("/api/v5/trade/order"), body.capture());
        assertEquals("key", used.getValue().apiKey(), "必须用 userId 解析出来的凭证");
        Map<String, Object> sent = (Map<String, Object>) body.getValue();
        assertEquals("BTC-USDT", sent.get("instId"));
        assertEquals("cash", sent.get("tdMode"));
        assertEquals("buy", sent.get("side"));
        assertEquals("limit", sent.get("ordType"));
        assertEquals("0.01", sent.get("sz"), "尾部零要去掉，OKX 对格式敏感");
        assertEquals("30000", sent.get("px"));
        assertEquals("cli-1", sent.get("clOrdId"));
    }

    @Test
    @DisplayName("撤单也要过闸（否则开关只是挡住了买，没挡住撤）")
    void cancelAlsoGuarded() {
        config.setTradeEnabled(false);
        assertThrows(IllegalStateException.class, () -> service.cancelOrder(USER, "BTC-USDT.CRYPTO", "1"));
        verify(client, never()).privatePost(any(OkxCredentials.class), anyString(), any());
    }

    @Test
    @DisplayName("撤单的凭证也来自调用者本人")
    void cancelUsesCallerCredentials() {
        config.setTradeEnabled(true);
        when(client.privatePost(any(OkxCredentials.class), anyString(), any())).thenReturn(null);
        when(orderLog.findByOrdId("42")).thenReturn(null);

        service.cancelOrder(USER, "BTC-USDT.CRYPTO", "42");

        verify(credentials).resolve(USER);
        verify(client).privatePost(any(OkxCredentials.class), eq("/api/v5/trade/cancel-order"), any());
    }
}
