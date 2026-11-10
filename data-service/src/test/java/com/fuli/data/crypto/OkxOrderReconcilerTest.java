package com.fuli.data.crypto;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fuli.data.crypto.entity.CryptoOrder;
import com.fuli.data.okx.OkxClient;
import com.fuli.data.okx.OkxCredentialService;
import com.fuli.data.okx.OkxCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** P4：交易所 orders-pending 与本地未完成单、孤儿单的只读差异报告。 */
@ExtendWith(MockitoExtension.class)
class OkxOrderReconcilerTest {

    private static final String PATH = "/api/v5/trade/orders-pending";

    @Mock
    private OkxClient client;

    @Mock
    private CryptoOrderService orderService;

    @Mock
    private OkxCredentialService credentials;

    @InjectMocks
    private OkxOrderReconciler reconciler;

    private static final long USER = 7L;

    private static final OkxCredentials CREDS = new OkxCredentials("k", "s", "p");

    @org.junit.jupiter.api.BeforeEach
    void stubCredentials() {
        // 对账只是一个使用方：凭证怎么来不是本测试的重点，但必须走 userId 解析这条路
        lenient().when(credentials.resolve(anyLong())).thenReturn(CREDS);
    }

    private static ArrayNode exchange(String... ordIds) {
        ArrayNode arr = JsonNodeFactory.instance.arrayNode();
        for (String id : ordIds) {
            arr.addObject().put("ordId", id);
        }
        return arr;
    }

    private static CryptoOrder local(String ordId) {
        CryptoOrder o = new CryptoOrder();
        o.setOrdId(ordId);
        return o;
    }

    private static CryptoOrder orphan(long id) {
        CryptoOrder o = new CryptoOrder();
        o.setId(id);
        return o;
    }

    @Test
    void reportsBothSidesDifferencesAndOrphansWithoutWritingAnything() {
        when(client.privateGet(any(OkxCredentials.class), eq(PATH), any())).thenReturn(exchange("e1", "e2"));
        when(orderService.findOpenOrders(100)).thenReturn(List.of(local("e2"), local("l1")));
        when(orderService.findOrphans()).thenReturn(List.of(orphan(9L)));

        OkxOrderReconciler.Result r = reconciler.reconcile(USER, "SPOT", 100);

        assertEquals(2, r.exchangeCount());
        assertEquals(2, r.localCount());
        assertEquals(List.of("e1"), r.exchangeOnly());
        assertEquals(List.of("l1"), r.localOnly());
        assertEquals(List.of(9L), r.orphanIds());
        assertFalse(r.clean());
    }

    @Test
    void cleanWhenBothSidesMatchAndNoOrphans() {
        when(client.privateGet(any(OkxCredentials.class), eq(PATH), any())).thenReturn(exchange("e1", "e2"));
        when(orderService.findOpenOrders(50)).thenReturn(List.of(local("e1"), local("e2")));
        when(orderService.findOrphans()).thenReturn(List.of());

        OkxOrderReconciler.Result r = reconciler.reconcile(USER, "SWAP", 50);

        assertTrue(r.clean());
        assertTrue(r.exchangeOnly().isEmpty());
        assertTrue(r.localOnly().isEmpty());
        assertTrue(r.orphanIds().isEmpty());
    }

    @Test
    void nullExchangeResponseIsTreatedAsEmptyNotAnError() {
        when(client.privateGet(any(OkxCredentials.class), eq(PATH), any())).thenReturn(null);
        when(orderService.findOpenOrders(10)).thenReturn(List.of(local("l1")));
        when(orderService.findOrphans()).thenReturn(List.of());

        OkxOrderReconciler.Result r = reconciler.reconcile(USER, null, 10);

        assertEquals(0, r.exchangeCount());
        assertEquals(List.of("l1"), r.localOnly());
    }
}
