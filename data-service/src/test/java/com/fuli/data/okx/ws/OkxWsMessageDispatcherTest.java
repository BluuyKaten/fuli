package com.fuli.data.okx.ws;

import com.fuli.data.config.OkxConfig;
import com.fuli.data.crypto.CryptoAccountService;
import com.fuli.data.crypto.CryptoOrderService;
import com.fuli.data.crypto.entity.CryptoFill;
import com.fuli.data.crypto.entity.CryptoPosition;
import com.fuli.data.crypto.mapper.CryptoPositionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** P4：orders / positions / account 频道解码、状态机联动、成交落库、余额快照去重。 */
@ExtendWith(MockitoExtension.class)
class OkxWsMessageDispatcherTest {

    @Mock
    private CryptoOrderService orderService;

    @Mock
    private CryptoPositionMapper positionMapper;

    @Mock
    private CryptoAccountService accountService;

    @Mock
    private OkxConfig okxConfig;

    @Mock
    private OkxWsClient wsClient;

    @InjectMocks
    private OkxWsMessageDispatcher dispatcher;

    private static final String ORDER_PUSH = "{\"arg\":{\"channel\":\"orders\",\"instType\":\"ANY\"},"
            + "\"data\":[{\"instId\":\"BTC-USDT\",\"ordId\":\"ord-1\",\"clOrdId\":\"c1\","
            + "\"state\":\"partially_filled\",\"accFillSz\":\"0.5\",\"fillSz\":\"0.5\","
            + "\"fillPx\":\"30000\",\"avgPx\":\"30000\",\"fee\":\"-0.001\",\"feeCcy\":\"USDT\","
            + "\"tradeId\":\"150\",\"posId\":\"p1\",\"side\":\"buy\","
            + "\"uTime\":\"1615170598022\",\"fillTime\":\"1615170598021\"}]}";

    private static final String POSITION_PUSH = "{\"arg\":{\"channel\":\"positions\",\"instType\":\"SWAP\"},"
            + "\"data\":[{\"posId\":\"p1\",\"instId\":\"BTC-USDT-SWAP\",\"instType\":\"SWAP\","
            + "\"pos\":\"1\",\"tradeId\":\"150\",\"uTime\":\"1615196199624\","
            + "\"mgnMode\":\"cross\",\"posSide\":\"net\",\"avgPx\":\"50912.4\",\"upl\":\"0.45\"}]}";

    @Test
    void ordersPushIsConvertedIntoStateMachineUpdate() {
        when(orderService.applyOrderUpdate(any())).thenReturn(false);

        dispatcher.accept(ORDER_PUSH);

        ArgumentCaptor<CryptoOrderService.OrderUpdate> captor =
                ArgumentCaptor.forClass(CryptoOrderService.OrderUpdate.class);
        verify(orderService).applyOrderUpdate(captor.capture());
        CryptoOrderService.OrderUpdate u = captor.getValue();
        assertEquals("ord-1", u.ordId());
        assertEquals("partially_filled", u.state());
        assertEquals(new BigDecimal("0.5"), u.accFillSz());
        assertEquals("150", u.tradeId());
        assertEquals(1615170598022L, u.uTime());
    }

    @Test
    void acceptedOrderUpdateAlsoRecordsFill() {
        when(orderService.applyOrderUpdate(any())).thenReturn(true);

        dispatcher.accept(ORDER_PUSH);

        ArgumentCaptor<CryptoFill> captor = ArgumentCaptor.forClass(CryptoFill.class);
        verify(orderService).recordFill(captor.capture());
        assertEquals("BTC-USDT", captor.getValue().getInstId());
        assertEquals("150", captor.getValue().getTradeId());
        assertEquals(new BigDecimal("30000"), captor.getValue().getFillPx());
    }

    @Test
    void ignoredOrderUpdateDoesNotRecordFill() {
        when(orderService.applyOrderUpdate(any())).thenReturn(false);
        dispatcher.accept(ORDER_PUSH);
        verify(orderService, never()).recordFill(any());
    }

    @Test
    void newPositionSnapshotIsInserted() {
        when(positionMapper.selectById(any())).thenReturn(null);
        when(orderService.isPositionStale(any(), any())).thenReturn(true);

        dispatcher.accept(POSITION_PUSH);

        ArgumentCaptor<CryptoPosition> captor = ArgumentCaptor.forClass(CryptoPosition.class);
        verify(positionMapper).insert(captor.capture());
        assertEquals("p1", captor.getValue().getPosId());
        assertEquals(new BigDecimal("1"), captor.getValue().getPos());
        assertEquals("cross", captor.getValue().getMgnMode());
    }

    @Test
    void staleExistingPositionIsUpdated() {
        CryptoPosition existing = new CryptoPosition();
        existing.setPosId("p1");
        when(positionMapper.selectById(any())).thenReturn(existing);
        when(orderService.isPositionStale(any(), any())).thenReturn(true);

        dispatcher.accept(POSITION_PUSH);

        verify(positionMapper).updateById(any(CryptoPosition.class));
        verify(positionMapper, never()).insert(any(CryptoPosition.class));
    }

    @Test
    void repeatedPositionSnapshotIsNotWritten() {
        CryptoPosition existing = new CryptoPosition();
        existing.setPosId("p1");
        when(positionMapper.selectById(any())).thenReturn(existing);
        when(orderService.isPositionStale(any(), any())).thenReturn(false);

        dispatcher.accept(POSITION_PUSH);

        verify(positionMapper, never()).insert(any(CryptoPosition.class));
        verify(positionMapper, never()).updateById(any(CryptoPosition.class));
    }

    @Test
    void controlMessagesAndAccountChannelDoNotTouchOrderOrPositionTables() {
        dispatcher.accept("{\"event\":\"subscribe\",\"arg\":{\"channel\":\"orders\"}}");
        dispatcher.accept("{\"event\":\"login\",\"code\":\"0\"}");
        dispatcher.accept("{\"arg\":{\"channel\":\"account\"},\"data\":[{\"totalEq\":\"100\"}]}");
        verifyNoInteractions(orderService);
        verifyNoInteractions(positionMapper);
    }

    @Test
    void malformedFrameIsIgnoredWithoutThrowing() {
        assertDoesNotThrow(() -> dispatcher.accept("not-json"));
        assertDoesNotThrow(() -> dispatcher.accept("{\"arg\":{},\"data\":\"not-an-array\"}"));
        verifyNoInteractions(orderService);
    }

    @Test
    void accountPushIsPersistedForConfiguredOwner() {
        when(okxConfig.getWsOwnerUserId()).thenReturn(3L);
        String push = "{\"arg\":{\"channel\":\"account\"},\"data\":[{\"totalEq\":\"12345.6\","
                + "\"uTime\":\"1794283000000\",\"details\":[{\"ccy\":\"USDT\",\"eq\":\"10000\"},"
                + "{\"ccy\":\"BTC\",\"eq\":\"2345.6\"}]}]}";

        dispatcher.accept(push);

        ArgumentCaptor<com.fasterxml.jackson.databind.JsonNode> captor =
                ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(accountService).recordSnapshot(org.mockito.ArgumentMatchers.eq(3L), captor.capture());
        assertEquals("12345.6", captor.getValue().path("totalEq").asText());
    }

    @Test
    void accountPushIsIgnoredWhenOwnerIsNotConfigured() {
        when(okxConfig.getWsOwnerUserId()).thenReturn(0L);
        dispatcher.accept("{\"arg\":{\"channel\":\"account\"},\"data\":[{\"uTime\":\"1\"}]}");
        verifyNoInteractions(accountService);
    }
}
