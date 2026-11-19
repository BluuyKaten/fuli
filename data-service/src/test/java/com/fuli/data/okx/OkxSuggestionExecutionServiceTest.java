package com.fuli.data.okx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fuli.data.okx.entity.OkxOrderSuggestion;
import com.fuli.data.okx.mapper.OkxOrderSuggestionMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OkxSuggestionExecutionServiceTest {

    private static final long USER = 7L;

    private static OkxOrderSuggestion suggestion(String status) {
        OkxOrderSuggestion s = new OkxOrderSuggestion();
        s.setId(100L);
        s.setUserId(USER);
        s.setInstId("BTC-USDT");
        s.setSide("buy");
        s.setOrdType("limit");
        s.setSz(new BigDecimal("0.01"));
        s.setPx(new BigDecimal("30000"));
        s.setStatus(status);
        return s;
    }

    private static JsonNode orderResponse(String ordId) {
        ArrayNode data = JsonNodeFactory.instance.arrayNode();
        data.addObject().put("ordId", ordId).put("sCode", "0").put("sMsg", "");
        return data;
    }

    @Test
    void executesAndMarksExecuted() {
        OkxOrderSuggestionMapper mapper = mock(OkxOrderSuggestionMapper.class);
        when(mapper.selectById(100L)).thenReturn(suggestion("new"));
        when(mapper.updateStatusIf(100L, USER, "new", "executing")).thenReturn(1);
        when(mapper.updateStatusIf(100L, USER, "executing", "executed")).thenReturn(1);
        OkxTradeService trade = mock(OkxTradeService.class);
        when(trade.placeOrder(eq(USER), eq("BTC-USDT"), eq("buy"), eq("limit"),
                eq("0.01"), eq("30000"), eq("sugg-100"))).thenReturn(orderResponse("ord-9"));

        OkxSuggestionExecutionService svc = new OkxSuggestionExecutionService(mapper, trade);
        Map<String, Object> result = svc.execute(USER, 100L);

        assertEquals("executed", result.get("status"));
        assertEquals("ord-9", result.get("ordId"));
        assertEquals("sugg-100", result.get("clOrdId"));
        verify(mapper).updateStatusIf(100L, USER, "executing", "executed");
    }

    @Test
    void rejectsWhenSuggestionMissing() {
        OkxOrderSuggestionMapper mapper = mock(OkxOrderSuggestionMapper.class);
        when(mapper.selectById(999L)).thenReturn(null);
        OkxSuggestionExecutionService svc = new OkxSuggestionExecutionService(mapper, mock(OkxTradeService.class));
        assertThrows(IllegalArgumentException.class, () -> svc.execute(USER, 999L));
    }

    @Test
    void rejectsWhenNotOwner() {
        OkxOrderSuggestionMapper mapper = mock(OkxOrderSuggestionMapper.class);
        OkxOrderSuggestion s = suggestion("new");
        s.setUserId(8L);
        when(mapper.selectById(100L)).thenReturn(s);
        OkxSuggestionExecutionService svc = new OkxSuggestionExecutionService(mapper, mock(OkxTradeService.class));
        assertThrows(IllegalArgumentException.class, () -> svc.execute(USER, 100L));
    }

    @Test
    void rejectsWhenAlreadyProcessed() {
        OkxOrderSuggestionMapper mapper = mock(OkxOrderSuggestionMapper.class);
        when(mapper.selectById(100L)).thenReturn(suggestion("executed"));
        OkxSuggestionExecutionService svc = new OkxSuggestionExecutionService(mapper, mock(OkxTradeService.class));
        assertThrows(IllegalArgumentException.class, () -> svc.execute(USER, 100L));
    }

    @Test
    void rejectsSuggestionWithoutPlacingAnyOrder() {
        OkxOrderSuggestionMapper mapper = mock(OkxOrderSuggestionMapper.class);
        when(mapper.selectById(100L)).thenReturn(suggestion("new"));
        when(mapper.updateStatusIf(100L, USER, "new", "rejected")).thenReturn(1);
        OkxTradeService trade = mock(OkxTradeService.class);

        OkxSuggestionExecutionService svc = new OkxSuggestionExecutionService(mapper, trade);
        Map<String, Object> result = svc.reject(USER, 100L);

        assertEquals("rejected", result.get("status"));
        verify(mapper).updateStatusIf(100L, USER, "new", "rejected");
        verifyNoInteractions(trade);
    }

    @Test
    void cannotRejectAfterExecuted() {
        OkxOrderSuggestionMapper mapper = mock(OkxOrderSuggestionMapper.class);
        when(mapper.selectById(100L)).thenReturn(suggestion("executed"));
        OkxSuggestionExecutionService svc = new OkxSuggestionExecutionService(mapper, mock(OkxTradeService.class));
        assertThrows(IllegalArgumentException.class, () -> svc.reject(USER, 100L));
        verify(mapper, never()).updateStatusIf(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void cannotRejectOthersSuggestion() {
        OkxOrderSuggestionMapper mapper = mock(OkxOrderSuggestionMapper.class);
        OkxOrderSuggestion s = suggestion("new");
        s.setUserId(8L);
        when(mapper.selectById(100L)).thenReturn(s);
        OkxSuggestionExecutionService svc = new OkxSuggestionExecutionService(mapper, mock(OkxTradeService.class));
        assertThrows(IllegalArgumentException.class, () -> svc.reject(USER, 100L));
        verify(mapper, never()).updateStatusIf(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void doesNotPlaceOrderWhenAnotherRequestClaimsSuggestion() {
        OkxOrderSuggestionMapper mapper = mock(OkxOrderSuggestionMapper.class);
        when(mapper.selectById(100L)).thenReturn(suggestion("new"));
        when(mapper.updateStatusIf(100L, USER, "new", "executing")).thenReturn(0);
        OkxTradeService trade = mock(OkxTradeService.class);
        OkxSuggestionExecutionService svc = new OkxSuggestionExecutionService(mapper, trade);

        assertThrows(IllegalArgumentException.class, () -> svc.execute(USER, 100L));
        verifyNoInteractions(trade);
    }

    @Test
    void knownOrderRejectionReturnsSuggestionToNew() {
        OkxOrderSuggestionMapper mapper = mock(OkxOrderSuggestionMapper.class);
        when(mapper.selectById(100L)).thenReturn(suggestion("new"));
        when(mapper.updateStatusIf(100L, USER, "new", "executing")).thenReturn(1);
        when(mapper.updateStatusIf(100L, USER, "executing", "new")).thenReturn(1);
        OkxTradeService trade = mock(OkxTradeService.class);
        when(trade.placeOrder(anyLong(), anyString(), anyString(), anyString(), anyString(), any(), anyString()))
                .thenThrow(new OkxOrderRejectedException("51008", "余额不足"));
        OkxSuggestionExecutionService svc = new OkxSuggestionExecutionService(mapper, trade);

        assertThrows(OkxOrderRejectedException.class, () -> svc.execute(USER, 100L));
        verify(mapper).updateStatusIf(100L, USER, "executing", "new");
    }

    @Test
    void executingSuggestionCannotBeRejected() {
        OkxOrderSuggestionMapper mapper = mock(OkxOrderSuggestionMapper.class);
        when(mapper.selectById(100L)).thenReturn(suggestion("executing"));
        OkxSuggestionExecutionService svc = new OkxSuggestionExecutionService(mapper, mock(OkxTradeService.class));

        assertThrows(IllegalArgumentException.class, () -> svc.reject(USER, 100L));
        verify(mapper, never()).updateStatusIf(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void cannotExecuteTwiceAfterRejected() {
        OkxOrderSuggestionMapper mapper = mock(OkxOrderSuggestionMapper.class);
        when(mapper.selectById(100L)).thenReturn(suggestion("rejected"));
        OkxTradeService trade = mock(OkxTradeService.class);
        OkxSuggestionExecutionService svc = new OkxSuggestionExecutionService(mapper, trade);

        assertThrows(IllegalArgumentException.class, () -> svc.execute(USER, 100L));
        verifyNoInteractions(trade);
    }
}
