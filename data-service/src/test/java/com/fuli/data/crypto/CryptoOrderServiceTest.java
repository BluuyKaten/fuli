package com.fuli.data.crypto;

import com.fuli.data.crypto.entity.CryptoFill;
import com.fuli.data.crypto.entity.CryptoOrder;
import com.fuli.data.crypto.entity.CryptoPosition;
import com.fuli.data.crypto.mapper.CryptoFillMapper;
import com.fuli.data.crypto.mapper.CryptoOrderMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2 订单域服务的边界测试：写前日志、clOrdId 幂等、状态机、乱序推送、持仓对账。
 * 全程 mock Mapper，不连数据库、不连交易所。
 */
@ExtendWith(MockitoExtension.class)
class CryptoOrderServiceTest {

    @Mock
    private CryptoOrderMapper orderMapper;

    @Mock
    private CryptoFillMapper fillMapper;

    @InjectMocks
    private CryptoOrderService service;

    private static final String CL_ORD_ID = "testBTC0123";

    private static CryptoOrderService.NewOrder newOrder() {
        return new CryptoOrderService.NewOrder(CL_ORD_ID, "BTC-USDT", "SPOT", "buy", "limit",
                new BigDecimal("30000"), new BigDecimal("0.01"), "cash");
    }

    private static CryptoOrder order(String state, Long uTime) {
        CryptoOrder o = new CryptoOrder();
        o.setId(1L);
        o.setClOrdId(CL_ORD_ID);
        o.setOrdId("ord-1");
        o.setInstId("BTC-USDT");
        o.setState(state);
        o.setUTime(uTime);
        return o;
    }

    private static CryptoOrderService.OrderUpdate update(String state, Long uTime) {
        return new CryptoOrderService.OrderUpdate("ord-1", state, null, null, null, null,
                null, null, null, null, uTime, null);
    }

    @Test
    void createPendingWritesBeforeAnyNetworkCall() {
        when(orderMapper.selectLatestByClOrdId(CL_ORD_ID)).thenReturn(null);

        CryptoOrder result = service.createPending(newOrder());

        ArgumentCaptor<CryptoOrder> captor = ArgumentCaptor.forClass(CryptoOrder.class);
        verify(orderMapper).insert(captor.capture());
        assertEquals(CryptoOrderState.PENDING.getCode(), captor.getValue().getState());
        assertEquals(CL_ORD_ID, captor.getValue().getClOrdId());
        assertEquals("SPOT", captor.getValue().getInstType());
        assertEquals("cash", captor.getValue().getTdMode());
        assertEquals(CryptoOrderState.PENDING.getCode(), result.getState());
    }

    @Test
    void reusedClOrdIdOnLiveOrderDoesNotInsertAgain() {
        CryptoOrder live = order(CryptoOrderState.LIVE.getCode(), 100L);
        when(orderMapper.selectLatestByClOrdId(CL_ORD_ID)).thenReturn(live);

        CryptoOrder result = service.createPending(newOrder());

        assertEquals(live, result);
        verify(orderMapper, never()).insert(any(CryptoOrder.class));
    }

    @Test
    void reusedClOrdIdOnFilledOrderIsAllowedBecauseOkxOnlyGuaranteesUniquenessAmongOpenOrders() {
        when(orderMapper.selectLatestByClOrdId(CL_ORD_ID))
                .thenReturn(order(CryptoOrderState.FILLED.getCode(), 100L));

        service.createPending(newOrder());

        verify(orderMapper).insert(any(CryptoOrder.class));
    }

    @Test
    void createPendingRejectsBlankOrOverlongClOrdId() {
        assertThrows(IllegalArgumentException.class, () -> service.createPending(
                new CryptoOrderService.NewOrder("", "BTC-USDT", "SPOT", "buy", "limit",
                        BigDecimal.ONE, BigDecimal.ONE, "cash")));
        assertThrows(IllegalArgumentException.class, () -> service.createPending(
                new CryptoOrderService.NewOrder("x".repeat(33), "BTC-USDT", "SPOT", "buy", "limit",
                        BigDecimal.ONE, BigDecimal.ONE, "cash")));
    }

    @Test
    void markSubmittedAcceptedMovesPendingToLive() {
        when(orderMapper.selectLatestByClOrdId(CL_ORD_ID))
                .thenReturn(order(CryptoOrderState.PENDING.getCode(), null));

        CryptoOrder result = service.markSubmitted(CL_ORD_ID, "ord-1", "0", "");

        assertEquals(CryptoOrderState.LIVE.getCode(), result.getState());
        assertEquals("ord-1", result.getOrdId());
        verify(orderMapper).updateById(result);
    }

    @Test
    void markSubmittedRejectedIsTerminalBecauseNoWsPushWillArrive() {
        when(orderMapper.selectLatestByClOrdId(CL_ORD_ID))
                .thenReturn(order(CryptoOrderState.PENDING.getCode(), null));

        CryptoOrder result = service.markSubmitted(CL_ORD_ID, null, "51008", "insufficient balance");

        assertEquals(CryptoOrderState.REJECTED.getCode(), result.getState());
        assertTrue(CryptoOrderState.of(result.getState()).isTerminal());
    }

    @Test
    void markSubmittedRejectsMissingSCodeAndLeavesPendingUnchanged() {
        CryptoOrder pending = order(CryptoOrderState.PENDING.getCode(), null);
        when(orderMapper.selectLatestByClOrdId(CL_ORD_ID)).thenReturn(pending);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.markSubmitted(CL_ORD_ID, "ord-1", null, ""));

        assertTrue(error.getMessage().contains("sCode"));
        assertEquals(CryptoOrderState.PENDING.getCode(), pending.getState());
        verify(orderMapper, never()).updateById(any(CryptoOrder.class));
    }

    @Test
    void markSubmittedRejectsAcceptedOrderWithoutOrdIdAndLeavesPendingUnchanged() {
        CryptoOrder pending = order(CryptoOrderState.PENDING.getCode(), null);
        when(orderMapper.selectLatestByClOrdId(CL_ORD_ID)).thenReturn(pending);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.markSubmitted(CL_ORD_ID, null, "0", ""));

        assertTrue(error.getMessage().contains("ordId"));
        assertEquals(CryptoOrderState.PENDING.getCode(), pending.getState());
        verify(orderMapper, never()).updateById(any(CryptoOrder.class));
    }

    @Test
    void markSubmittedThrowsWhenWriteAheadLogIsMissing() {
        when(orderMapper.selectLatestByClOrdId(CL_ORD_ID)).thenReturn(null);
        assertThrows(IllegalStateException.class,
                () -> service.markSubmitted(CL_ORD_ID, "ord-1", "0", ""));
    }

    @Test
    void markSubmittedIsIdempotentOnRetry() {
        CryptoOrder live = order(CryptoOrderState.LIVE.getCode(), 100L);
        when(orderMapper.selectLatestByClOrdId(CL_ORD_ID)).thenReturn(live);

        assertEquals(live, service.markSubmitted(CL_ORD_ID, "ord-1", "0", ""));
        verify(orderMapper, never()).updateById(any(CryptoOrder.class));
    }

    @Test
    void happyPathStateTransitionsAreAccepted() {
        when(orderMapper.selectByOrdId("ord-1"))
                .thenReturn(order(CryptoOrderState.LIVE.getCode(), 100L));
        assertTrue(service.applyOrderUpdate(update(CryptoOrderState.PARTIALLY_FILLED.getCode(), 101L)));

        when(orderMapper.selectByOrdId("ord-1"))
                .thenReturn(order(CryptoOrderState.PARTIALLY_FILLED.getCode(), 101L));
        assertTrue(service.applyOrderUpdate(update(CryptoOrderState.FILLED.getCode(), 102L)));
    }

    @Test
    void terminalStateRejectsRollbackAndEvenSameStateRepush() {
        when(orderMapper.selectByOrdId("ord-1"))
                .thenReturn(order(CryptoOrderState.FILLED.getCode(), 200L));

        assertFalse(service.applyOrderUpdate(update(CryptoOrderState.LIVE.getCode(), 300L)));
        assertFalse(service.applyOrderUpdate(update(CryptoOrderState.FILLED.getCode(), 300L)));
        verify(orderMapper, never()).updateById(any(CryptoOrder.class));
    }

    @Test
    void stalePushIsIgnored() {
        when(orderMapper.selectByOrdId("ord-1"))
                .thenReturn(order(CryptoOrderState.LIVE.getCode(), 200L));

        assertFalse(service.applyOrderUpdate(update(CryptoOrderState.FILLED.getCode(), 150L)));
        verify(orderMapper, never()).updateById(any(CryptoOrder.class));
    }

    @Test
    void unknownOrdIdIsIgnoredPendingRestReconciliation() {
        when(orderMapper.selectByOrdId("ord-1")).thenReturn(null);
        assertFalse(service.applyOrderUpdate(update(CryptoOrderState.LIVE.getCode(), 100L)));
        verify(orderMapper, never()).updateById(any(CryptoOrder.class));
    }

    @Test
    void unknownStateIsIgnored() {
        when(orderMapper.selectByOrdId("ord-1"))
                .thenReturn(order(CryptoOrderState.LIVE.getCode(), 100L));
        assertFalse(service.applyOrderUpdate(update("some_new_okx_state", 101L)));
    }

    @Test
    void findOrphansReturnsWriteAheadRows() {
        List<CryptoOrder> pending = List.of(order(CryptoOrderState.PENDING.getCode(), null));
        when(orderMapper.selectPending()).thenReturn(pending);
        assertEquals(pending, service.findOrphans());
    }

    @Test
    void recordFillTreatsDuplicateKeyAsAlreadyProcessed() {
        CryptoFill fill = new CryptoFill();
        fill.setInstId("BTC-USDT");
        fill.setTradeId("150");
        assertTrue(service.recordFill(fill));

        when(fillMapper.insert(any(CryptoFill.class))).thenThrow(new DuplicateKeyException("dup"));
        assertFalse(service.recordFill(fill));
    }

    @Test
    void positionReconciliationDetectsAdlEvenWhenTradeIdDoesNotAdvance() {
        CryptoPosition existing = new CryptoPosition();
        existing.setPos(new BigDecimal("20"));
        existing.setUTime(1000L);
        existing.setTradeId("163");

        CryptoPosition adl = new CryptoPosition();
        adl.setPos(new BigDecimal("6"));       // 强减/ADL：tradeId 不变、pos 变
        adl.setUTime(2000L);
        adl.setTradeId("163");

        assertTrue(service.isPositionStale(existing, adl));
    }

    @Test
    void positionReconciliationIgnoresExactRepeat() {
        CryptoPosition existing = new CryptoPosition();
        existing.setPos(new BigDecimal("10"));
        existing.setUTime(1000L);
        existing.setTradeId("163");

        CryptoPosition repeat = new CryptoPosition();
        repeat.setPos(new BigDecimal("10"));
        repeat.setUTime(1000L);
        repeat.setTradeId("163");

        assertFalse(service.isPositionStale(existing, repeat));
        assertTrue(service.isPositionStale(null, repeat));
    }

    @Test
    void compareTradeIdHandlesNegativeLiquidationIdsAndInvalidStrings() {
        assertTrue(CryptoOrderService.compareTradeId("164", "163") > 0);
        assertTrue(CryptoOrderService.compareTradeId("-1", "163") < 0);
        assertEquals(0, CryptoOrderService.compareTradeId(null, "163"));
        assertTrue(CryptoOrderService.compareTradeId("163", null) > 0);
    }
}
