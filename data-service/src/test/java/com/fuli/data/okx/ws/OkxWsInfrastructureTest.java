package com.fuli.data.okx.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuli.data.config.OkxConfig;
import com.fuli.data.okx.OkxCredentialService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P3 WS 基础设施测试：背压、订阅重放、登录签名、模拟盘域强制、重连退避、消息分发。
 * 全程不连网络；需要 socket 的地方用 mock + 反射注入。
 */
class OkxWsInfrastructureTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final long FIXED_TS = 1704876947L;

    private static OkxWsClient client() {
        // ADR-0010：WS 的凭证按 okx.ws-owner-user-id 从 okx_account 取；
        // 本测试只验证连接/背压/订阅逻辑，不涉及登录，给一个空实现的凭据服务即可。
        return new OkxWsClient(new OkxWsProperties(), new OkxConfig(), mock(OkxCredentialService.class));
    }

    @Test
    void bufferEvictsOldestAndMakesBackpressureObservable() {
        OkxWsFrameBuffer<String> buffer = new OkxWsFrameBuffer<>(3);
        for (int i = 1; i <= 5; i++) {
            buffer.offer("f" + i);
        }
        assertEquals(3, buffer.size());
        assertEquals(2, buffer.droppedCount());
        assertEquals(List.of("f3", "f4", "f5"), buffer.drain(10));
        assertEquals(0, buffer.size());
    }

    @Test
    void bufferDrainRespectsMaxAndRejectsInvalidCapacity() {
        OkxWsFrameBuffer<String> buffer = new OkxWsFrameBuffer<>(5);
        buffer.offer("a");
        buffer.offer("b");
        buffer.offer("c");
        assertEquals(List.of("a", "b"), buffer.drain(2));
        assertEquals(List.of("c"), buffer.drain(2));
        assertThrows(IllegalArgumentException.class, () -> new OkxWsFrameBuffer<String>(0));
        assertThrows(NullPointerException.class, () -> buffer.offer(null));
    }

    @Test
    void subscriptionShortFormAndRawJsonAreBothSupported() throws Exception {
        assertEquals("account", OkxWsSubscription.parse("account").path("channel").asText());
        assertEquals("ANY", OkxWsSubscription.parse("orders:ANY").path("instType").asText());
        assertEquals("BTC", OkxWsSubscription.parse("account:BTC").path("ccy").asText());
        assertEquals("BTC-USDT-SWAP",
                OkxWsSubscription.parse("{\"channel\":\"orders\",\"instId\":\"BTC-USDT-SWAP\"}")
                        .path("instId").asText());
        assertThrows(IllegalArgumentException.class, () -> OkxWsSubscription.parse(" "));
        assertThrows(IllegalArgumentException.class, () -> OkxWsSubscription.parse("{\"foo\":1}"));
    }

    @Test
    void subscribeMessageCarriesOpAndAllArgs() throws Exception {
        String msg = OkxWsSubscription.subscribeMessage(List.of("account", "positions:SWAP"));
        JsonNode root = JSON.readTree(msg);
        assertEquals("subscribe", root.path("op").asText());
        assertEquals(2, root.path("args").size());
        assertEquals("positions", root.path("args").get(1).path("channel").asText());
        assertEquals("SWAP", root.path("args").get(1).path("instType").asText());
    }

    @Test
    void loginSignatureMatchesManualHmacAndUsesUnixSeconds() throws Exception {
        String secret = "test-secret-key-0123456789";
        String prehash = FIXED_TS + "GET" + "/users/self/verify";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = Base64.getEncoder().encodeToString(mac.doFinal(prehash.getBytes(StandardCharsets.UTF_8)));

        JsonNode root = JSON.readTree(OkxWsLogin.message("api-key", "passphrase", secret, FIXED_TS));
        assertEquals("login", root.path("op").asText());
        JsonNode arg = root.path("args").get(0);
        assertEquals("api-key", arg.path("apiKey").asText());
        assertEquals("passphrase", arg.path("passphrase").asText());
        assertEquals("1704876947", arg.path("timestamp").asText());
        assertEquals(expected, arg.path("sign").asText());
    }

    @Test
    void loginMessageNeverLeaksSecretKey() {
        String secret = "super-secret-value";
        assertFalse(OkxWsLogin.message("k", "p", secret, FIXED_TS).contains(secret));
    }

    @Test
    void propertiesRejectLiveWsDomainAndAcceptDemoDomain() {
        OkxWsProperties props = new OkxWsProperties();
        props.validate();  // 默认 wspap 域

        props.setUrl("wss://ws.okx.com/ws/v5/private");
        IllegalStateException e = assertThrows(IllegalStateException.class, props::validate);
        assertTrue(e.getMessage().contains("wspap.okx.com"));

        props.setUrl("wss://wspap.okx.com/ws/v5/business");
        props.validate();
    }

    @Test
    void propertiesRejectInvalidBufferAndTiming() {
        OkxWsProperties props = new OkxWsProperties();
        props.setBufferCapacity(0);
        assertThrows(IllegalStateException.class, props::validate);
        props.setBufferCapacity(10);
        props.setMaxReconnectDelayMs(1L);  // 1 < reconnectDelayMs(2000) → 非法
        assertThrows(IllegalStateException.class, props::validate);
        props.setMaxReconnectDelayMs(60000L);
        props.validate();
    }

    @Test
    void reconnectDelayIsExponentialAndCappedWithoutOverflow() {
        assertEquals(2000L, OkxWsClient.reconnectDelay(0, 2000L, 60000L));
        assertEquals(4000L, OkxWsClient.reconnectDelay(1, 2000L, 60000L));
        assertEquals(8000L, OkxWsClient.reconnectDelay(2, 2000L, 60000L));
        assertEquals(60000L, OkxWsClient.reconnectDelay(100, 2000L, 60000L));
        assertEquals(2000L, OkxWsClient.reconnectDelay(-5, 2000L, 60000L));
    }

    @Test
    void clientBuffersMessagesAndDispatchesToAllHandlers() {
        OkxWsClient client = client();
        List<String> seenA = new ArrayList<>();
        List<String> seenB = new ArrayList<>();
        client.addHandler(seenA::add);
        client.addHandler(text -> {
            throw new IllegalStateException("故意抛错");
        });
        client.addHandler(seenB::add);

        client.onText("{\"event\":\"subscribe\"}");

        assertEquals(1, client.getBuffer().size());
        assertEquals(List.of("{\"event\":\"subscribe\"}"), seenA);
        assertEquals(List.of("{\"event\":\"subscribe\"}"), seenB);
        client.stop();
    }

    @Test
    @SuppressWarnings("unchecked")
    void loginAckReplaysSubscriptions() {
        OkxWsClient client = client();
        WebSocket ws = mock(WebSocket.class);
        when(ws.sendText(anyString(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(ws));
        when(ws.sendClose(org.mockito.ArgumentMatchers.anyInt(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(ws));
        ReflectionTestUtils.setField(client, "socket", ws);
        ReflectionTestUtils.setField(client, "running", true);

        client.onText("{\"event\":\"login\",\"code\":\"0\"}");

        verify(ws).sendText(argThat(s -> s != null && s.toString().contains("\"op\":\"subscribe\"")), eq(true));
        assertEquals(OkxWsClient.Status.LOGGED_IN, client.getStatus());
        client.stop();
    }
}
