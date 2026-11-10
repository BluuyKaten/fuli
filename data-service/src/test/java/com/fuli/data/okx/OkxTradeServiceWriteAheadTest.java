package com.fuli.data.okx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuli.data.config.OkxConfig;
import com.fuli.data.crypto.CryptoOrderService;
import com.fuli.data.crypto.CryptoOrderState;
import com.fuli.data.crypto.entity.CryptoOrder;
import com.fuli.data.risk.RiskControlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P4：验证「先落库 → 再发网络 → 再登记」的顺序确实生效，且幂等命中不会重复发单。
 *
 * <p>ADR-0010 之后凭证是方法内的局部变量（按 userId 解析），因此
 * {@code privatePost} 的第一个参数用类型化 matcher，不再依赖全局配置。
 */
class OkxTradeServiceWriteAheadTest {

    private static final long USER = 7L;

    private static final String ORDER_PATH = "/api/v5/trade/order";

    private OkxClient client;
    private CryptoOrderService orderLog;
    private OkxCredentialService credentials;

    private RiskControlService riskControl;
    private OkxTradeService service;

    @BeforeEach
    void setUp() {
        OkxConfig config = new OkxConfig();
        config.setTradeEnabled(true);
        client = mock(OkxClient.class);
        orderLog = mock(CryptoOrderService.class);
        credentials = mock(OkxCredentialService.class);
        riskControl = mock(RiskControlService.class);
        when(credentials.resolve(USER)).thenReturn(new OkxCredentials("key", "01234567890123456789012345678901", "pass"));
        when(orderLog.createPending(any())).thenAnswer(inv -> {
            CryptoOrderService.NewOrder req = inv.getArgument(0);
            CryptoOrder o = new CryptoOrder();
            o.setClOrdId(req.clOrdId());
            o.setState(CryptoOrderState.PENDING.getCode());
            return o;
        });
        when(client.privatePost(any(OkxCredentials.class), eq(ORDER_PATH), any()))
                .thenReturn(response("ord-1", "0", ""));
        // 市价单风控需要最新价：mock 公共 ticker 返回 last
        when(client.publicGet(eq("/api/v5/market/ticker"), any()))
                .thenReturn(tickerResponse("30000"));
        service = new OkxTradeService(config, client, orderLog, credentials, riskControl);
    }

    private static JsonNode tickerResponse(String last) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode item = root.putArray("data").addObject();
        item.put("last", last);
        return root;
    }

    private static JsonNode response(String ordId, String sCode, String sMsg) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("code", "0");
        ObjectNode item = root.putArray("data").addObject();
        item.put("ordId", ordId);
        item.put("clOrdId", "c1");
        item.put("sCode", sCode);
        item.put("sMsg", sMsg);
        return root;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> capturedBody(ArgumentCaptor<Object> captor) {
        return (Map<String, Object>) captor.getValue();
    }

    @Test
    void writesAheadBeforeNetworkAndRegistersResult() {
        service.placeOrder(USER, "BTC-USDT.CRYPTO", "buy", "limit", "0.01", "30000", "c1");

        InOrder order = inOrder(orderLog, client);
        order.verify(orderLog).createPending(any());
        order.verify(client).privatePost(any(OkxCredentials.class), eq(ORDER_PATH), any());
        order.verify(orderLog).markSubmitted("c1", "ord-1", "0", "");
    }

    @Test
    void credentialsAreResolvedBeforeTheWriteAheadLog() {
        service.placeOrder(USER, "BTC-USDT.CRYPTO", "buy", "limit", "0.01", "30000", "c1");

        // 先解析凭证再落 pending：否则没凭证的用户会在库里留下永远不会发出的 pending 行
        InOrder order = inOrder(credentials, orderLog);
        order.verify(credentials).resolve(USER);
        order.verify(orderLog).createPending(any());
    }

    @Test
    void generatesThirtyTwoCharClOrdIdWhenMissingAndPutsItInRequestBody() {
        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        service.placeOrder(USER, "BTC-USDT", "buy", "market", "0.01", null, null);
        verify(client).privatePost(any(OkxCredentials.class), eq(ORDER_PATH), bodyCaptor.capture());

        String generated = capturedBody(bodyCaptor).get("clOrdId").toString();
        assertEquals(32, generated.length());
        assertTrue(generated.matches("[a-f0-9]{32}"));
        verify(orderLog).markSubmitted(eq(generated), eq("ord-1"), eq("0"), eq(""));
    }

    @Test
    void idempotentHitSkipsNetworkRequestEntirely() {
        CryptoOrder live = new CryptoOrder();
        live.setClOrdId("c1");
        live.setOrdId("ord-1");
        live.setState(CryptoOrderState.LIVE.getCode());
        // 重复 stub 必须用 doReturn：when(...) 会先触发 setUp 里的 answer，而 any() 传的是 null
        doReturn(live).when(orderLog).createPending(any());

        JsonNode result = service.placeOrder(USER, "BTC-USDT", "buy", "market", "0.01", null, "c1");

        verify(client, never()).privatePost(any(OkxCredentials.class), any(), any());
        verify(orderLog, never()).markSubmitted(any(), any(), any(), any());
        assertTrue(result.path("data").get(0).path("sMsg").asText().contains("skipped"));
    }

    @Test
    void rejectedEntryCodeIsForwardedToWriteAheadLog() {
        when(client.privatePost(any(OkxCredentials.class), eq(ORDER_PATH), any()))
                .thenReturn(response("", "51008", "insufficient balance"));

        service.placeOrder(USER, "BTC-USDT", "buy", "market", "0.01", null, "c1");

        verify(orderLog).markSubmitted("c1", "", "51008", "insufficient balance");
    }

    @Test
    void cancelStillSendsWhenLocalOrderIsMissing() {
        when(orderLog.findByOrdId("ord-9")).thenReturn(null);

        service.cancelOrder(USER, "BTC-USDT", "ord-9");

        verify(client).privatePost(any(OkxCredentials.class), eq("/api/v5/trade/cancel-order"), any());
    }

    @Test
    void invalidParamsAreRejectedBeforeAnyWriteOrNetworkCall() {
        assertThrows(IllegalArgumentException.class,
                () -> service.placeOrder(USER, "BTC-USDT", "hold", "limit", "0.01", "30000", "c1"));
        verify(orderLog, never()).createPending(any());
        verify(client, never()).privatePost(any(OkxCredentials.class), any(), any());
    }
}
