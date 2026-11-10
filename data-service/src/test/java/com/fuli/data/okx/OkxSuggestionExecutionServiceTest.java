package com.fuli.data.okx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuli.data.okx.entity.OkxOrderSuggestion;
import com.fuli.data.okx.mapper.OkxOrderSuggestionMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

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
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("code", "0");
        ObjectNode item = root.putArray("data").addObject();
        item.put("ordId", ordId);
        return root;
    }

    @Test
    void executesAndMarksExecuted() {
        OkxOrderSuggestionMapper mapper = mock(OkxOrderSuggestionMapper.class);
        when(mapper.selectById(100L)).thenReturn(suggestion("new"));
        OkxTradeService trade = mock(OkxTradeService.class);
        when(trade.placeOrder(eq(USER), eq("BTC-USDT"), eq("buy"), eq("limit"),
                eq("0.01"), eq("30000"), eq("sugg-100"))).thenReturn(orderResponse("ord-9"));

        OkxSuggestionExecutionService svc = new OkxSuggestionExecutionService(mapper, trade);
        Map<String, Object> result = svc.execute(USER, 100L);

        assertEquals("executed", result.get("status"));
        assertEquals("ord-9", result.get("ordId"));
        assertEquals("sugg-100", result.get("clOrdId"));

        ArgumentCaptor<OkxOrderSuggestion> cap = ArgumentCaptor.forClass(OkxOrderSuggestion.class);
        verify(mapper).updateById(cap.capture());
        assertEquals("executed", cap.getValue().getStatus());
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
        s.setUserId(8L); // 别人的
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
}
