package com.fuli.data.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fuli.data.crypto.CryptoOrderService;
import com.fuli.data.crypto.CryptoOrderState;
import com.fuli.data.crypto.entity.CryptoOrder;
import com.fuli.data.okx.OkxClient;
import com.fuli.data.okx.OkxCredentialException;
import com.fuli.data.okx.OkxCredentialService;
import com.fuli.data.okx.OkxCredentials;
import com.fuli.data.okx.OkxOrderRejectedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** P6：七模块清单、读写分流、写能力的写前日志顺序（ADR-0010 后凭证也按 userId 取）。 */
@ExtendWith(MockitoExtension.class)
class OkxCapabilityServiceTest {

    private static final long USER = 7L;

    private static final OkxCredentials CREDS = new OkxCredentials("k", "s", "p");

    @Mock
    private OkxClient client;

    @Mock
    private CryptoOrderService orderLog;

    @Mock
    private OkxCredentialService credentials;

    @InjectMocks
    private OkxCapabilityService service;

    @BeforeEach
    void stubCredentials() {
        lenient().when(credentials.resolve(anyLong())).thenReturn(CREDS);
    }

    @Test
    void catalogCoversAllSevenModulesAndMarksWrites() {
        assertEquals(7, OkxModuleCatalog.MODULES.size());
        assertTrue(OkxModuleCatalog.MODULES.containsAll(
                java.util.Set.of("market", "spot", "swap", "futures", "option", "account", "bot")));
        assertTrue(OkxModuleCatalog.require("spot_place_order").write());
        assertTrue(!OkxModuleCatalog.require("market_ticker").write());
        assertTrue(!OkxModuleCatalog.require("market_ticker").signed());
    }

    @Test
    void readCapabilityUsesPublicGetForPublicEndpoints() {
        when(client.publicGet(eq("/api/v5/market/ticker"), any()))
                .thenReturn(JsonNodeFactory.instance.arrayNode());
        service.execute("market_ticker", Map.of("instId", "BTC-USDT"), null, USER);
        verifyNoInteractions(orderLog);
    }

    @Test
    void writeCapabilityFollowsWriteAheadLogOrder() {
        CryptoOrder pending = new CryptoOrder();
        pending.setState(CryptoOrderState.PENDING.getCode());
        when(orderLog.createPending(any())).thenReturn(pending);
        JsonNode response = JsonNodeFactory.instance.arrayNode()
                .add(JsonNodeFactory.instance.objectNode().put("ordId", "ord-1")
                        .put("sCode", "0").put("sMsg", ""));
        when(client.privatePost(any(OkxCredentials.class), eq("/api/v5/trade/order"), any()))
                .thenReturn(response);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instId", "BTC-USDT");
        body.put("side", "buy");
        body.put("ordType", "limit");
        body.put("sz", "0.01");
        body.put("px", "30000");
        service.execute("spot_place_order", Map.of(), body, USER);

        InOrder order = inOrder(credentials, orderLog, client);
        order.verify(credentials).resolve(USER);
        order.verify(orderLog).createPending(any());
        order.verify(client).privatePost(any(OkxCredentials.class), eq("/api/v5/trade/order"), any());
        order.verify(orderLog).markSubmitted(any(), eq("ord-1"), eq("0"), eq(""));
    }

    @Test
    void idempotentHitSkipsNetworkForCapabilityWrites() {
        CryptoOrder live = new CryptoOrder();
        live.setState(CryptoOrderState.LIVE.getCode());
        when(orderLog.createPending(any())).thenReturn(live);

        service.execute("spot_place_order", Map.of(), Map.of("instId", "BTC-USDT", "sz", "0.01"), USER);

        verifyNoInteractions(client);
    }

    @Test
    void rejectedWriteMarksSubmittedAndThrows() {
        CryptoOrder pending = new CryptoOrder();
        pending.setState(CryptoOrderState.PENDING.getCode());
        when(orderLog.createPending(any())).thenReturn(pending);
        JsonNode response = JsonNodeFactory.instance.arrayNode()
                .add(JsonNodeFactory.instance.objectNode()
                        .put("ordId", "")
                        .put("sCode", "51008")
                        .put("sMsg", "insufficient balance"));
        when(client.privatePost(any(OkxCredentials.class), eq("/api/v5/trade/order"), any()))
                .thenReturn(response);

        Map<String, Object> body = Map.of(
                "instId", "BTC-USDT",
                "side", "buy",
                "ordType", "limit",
                "sz", "0.01",
                "px", "30000");

        assertThrows(OkxOrderRejectedException.class,
                () -> service.execute("spot_place_order", Map.of(), body, USER));

        verify(orderLog).markSubmitted(any(), eq(""), eq("51008"), eq("insufficient balance"));
    }

    @Test
    void emptyWriteResponseDoesNotRegisterOrderAndFailsClearly() {
        CryptoOrder pending = new CryptoOrder();
        pending.setState(CryptoOrderState.PENDING.getCode());
        when(orderLog.createPending(any())).thenReturn(pending);
        when(client.privatePost(any(OkxCredentials.class), eq("/api/v5/trade/order"), any()))
                .thenReturn(JsonNodeFactory.instance.arrayNode());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.execute("spot_place_order", Map.of(), Map.of(
                        "instId", "BTC-USDT", "sz", "0.01"), USER));

        assertTrue(error.getMessage().contains("订单响应"));
        verify(orderLog, never()).markSubmitted(any(), any(), any(), any());
    }

    @Test
    void missingSCodeDoesNotRegisterOrderAndFailsClearly() {
        CryptoOrder pending = new CryptoOrder();
        pending.setState(CryptoOrderState.PENDING.getCode());
        when(orderLog.createPending(any())).thenReturn(pending);
        JsonNode response = JsonNodeFactory.instance.arrayNode()
                .add(JsonNodeFactory.instance.objectNode().put("ordId", "ord-1"));
        when(client.privatePost(any(OkxCredentials.class), eq("/api/v5/trade/order"), any()))
                .thenReturn(response);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.execute("spot_place_order", Map.of(), Map.of(
                        "instId", "BTC-USDT", "sz", "0.01"), USER));

        assertTrue(error.getMessage().contains("sCode"));
        verify(orderLog, never()).markSubmitted(any(), any(), any(), any());
    }

    @Test
    void acceptedWriteWithoutOrdIdDoesNotRegisterOrderAndFailsClearly() {
        CryptoOrder pending = new CryptoOrder();
        pending.setState(CryptoOrderState.PENDING.getCode());
        when(orderLog.createPending(any())).thenReturn(pending);
        JsonNode response = JsonNodeFactory.instance.arrayNode()
                .add(JsonNodeFactory.instance.objectNode().put("sCode", "0"));
        when(client.privatePost(any(OkxCredentials.class), eq("/api/v5/trade/order"), any()))
                .thenReturn(response);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.execute("spot_place_order", Map.of(), Map.of(
                        "instId", "BTC-USDT", "sz", "0.01"), USER));

        assertTrue(error.getMessage().contains("ordId"));
        verify(orderLog, never()).markSubmitted(any(), any(), any(), any());
    }

    @Test
    void unregisteredCapabilityIsRejectedBeforeAnyCall() {
        assertThrows(IllegalArgumentException.class,
                () -> service.execute("admin_drop_tables", Map.of(), Map.of(), USER));
        verifyNoInteractions(client);
        verifyNoInteractions(orderLog);
    }

    @Test
    void serviceAccountWithoutUserIdCannotCallSignedCapabilities() {
        // 服务账号（type=SERVICE）没有 userId：私有能力必须明确拒绝，而不是 NPE
        assertThrows(OkxCredentialException.class,
                () -> service.execute("account_balance", Map.of(), Map.of(), null));
        verifyNoInteractions(client);
    }
}
