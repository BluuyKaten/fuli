package com.fuli.data.security;

import com.fuli.data.config.OkxConfig;
import com.fuli.data.okx.OkxClient;
import com.fuli.data.okx.OkxCredentials;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Q12「实盘凭证不进 Java 服务」的代码级保证（ADR-0009 决策 4）。
 *
 * <p>验证三件事：模拟盘是常量而非开关、旧实盘配置会 fail-fast、
 * 每个私有请求无条件携带模拟盘头（不存在可以走实盘的分支）。
 */
class OkxSimulatedGuardTest {

    @Test
    void simulatedIsAConstantWithNoSetter() throws Exception {
        OkxConfig config = new OkxConfig();
        assertTrue(config.isSimulated());
        assertThrows(NoSuchMethodException.class,
                () -> OkxConfig.class.getMethod("setSimulated", boolean.class));
    }

    @Test
    void legacyLiveSwitchFailsFastAtStartup() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new OkxSimulatedGuard(
                        new MockEnvironment().withProperty("okx.simulated", "false")));
        assertTrue(e.getMessage().contains("ADR-0009"));
    }

    @Test
    void legacyTrueOrNoConfigStartsNormally() {
        assertDoesNotThrow(() -> new OkxSimulatedGuard(
                new MockEnvironment().withProperty("okx.simulated", "true")));
        assertDoesNotThrow(() -> new OkxSimulatedGuard(new MockEnvironment()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void everySignedRequestCarriesSimulatedTradingHeader() throws Exception {
        OkxConfig config = new OkxConfig();
        OkxClient client = new OkxClient(config);

        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.body()).thenReturn("{\"code\":\"0\",\"data\":[]}");
        doReturn(response).when(http).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        ReflectionTestUtils.setField(client, "http", http);

        // ADR-0010：凭证按调用传入，但「恒定带模拟盘头」这条不能因此放松
        client.privateGet(new OkxCredentials("key", "01234567890123456789012345678901", "pass"),
                "/api/v5/account/balance", Map.of());

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(captor.capture(), any());
        assertEquals("1", captor.getValue().headers()
                .firstValue("x-simulated-trading").orElse(null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void publicRequestNeedsNoCredentialsAndCarriesNoSimulatedHeader() throws Exception {
        OkxClient client = new OkxClient(new OkxConfig());
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.body()).thenReturn("{\"code\":\"0\",\"data\":[]}");
        doReturn(response).when(http).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        ReflectionTestUtils.setField(client, "http", http);

        client.publicGet("/api/v5/market/ticker", Map.of("instId", "BTC-USDT"));

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(captor.capture(), any());
        assertTrue(captor.getValue().headers().firstValue("x-simulated-trading").isEmpty(),
                "公共接口不需要也不应该带模拟盘头");
        assertTrue(captor.getValue().headers().firstValue("OK-ACCESS-KEY").isEmpty());
    }
}
