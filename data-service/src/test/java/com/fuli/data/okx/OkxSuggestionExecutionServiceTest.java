package com.fuli.data.okx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fuli.data.okx.entity.OkxOrderSuggestion;
import com.fuli.data.okx.mapper.OkxOrderSuggestionMapper;
import com.fuli.data.risk.RiskControlException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.util.Map;
import java.util.stream.Stream;

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
        ArrayNode response = JsonNodeFactory.instance.arrayNode();
        response.addObject().put("ordId", ordId).put("sCode", "0");
        return response;
    }

    @Test
    void executesAndMarksExecutedWithCompareAndSet() {
        OkxOrderSuggestionMapper mapper = mock(OkxOrderSuggestionMapper.class);
        when(mapper.selectById(100L)).thenReturn(suggestion("new"));
        when(mapper.claimNew(100L, USER)).thenReturn(1);
        when(mapper.markExecuted(100L, USER)).thenReturn(1);
        OkxTradeService trade = mock(OkxTradeService.class);
        when(trade.placeOrder(eq(USER), eq("BTC-USDT"), eq("buy"), eq("limit"),
                eq("0.01"), eq("30000"), eq("sugg-100"))).thenReturn(orderResponse("ord-9"));

        OkxSuggestionExecutionService svc = new OkxSuggestionExecutionService(mapper, trade);
        Map<String, Object> result = svc.execute(USER, 100L);

        assertEquals("executed", result.get("status"));
        assertEquals("ord-9", result.get("ordId"));
        assertEquals("sugg-100", result.get("clOrdId"));

        InOrder sequence = inOrder(mapper, trade);
        sequence.verify(mapper).claimNew(100L, USER);
        sequence.verify(trade).placeOrder(eq(USER), eq("BTC-USDT"), eq("buy"), eq("limit"),
                eq("0.01"), eq("30000"), eq("sugg-100"));
        sequence.verify(mapper).markExecuted(100L, USER);
        verify(mapper, never()).updateById(any(OkxOrderSuggestion.class));
    }

    @Test
    void doesNotPlaceOrderWhenClaimNewWasLost() {
        OkxOrderSuggestionMapper mapper = mock(OkxOrderSuggestionMapper.class);
        when(mapper.selectById(100L)).thenReturn(suggestion("new"));
        when(mapper.claimNew(100L, USER)).thenReturn(0);
        OkxTradeService trade = mock(OkxTradeService.class);

        OkxSuggestionExecutionService svc = new OkxSuggestionExecutionService(mapper, trade);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> svc.execute(USER, 100L));

        assertTrue(ex.getMessage().contains("已被其他请求处理"));
        verifyNoInteractions(trade);
        verify(mapper, never()).markExecuted(anyLong(), anyLong());
        verify(mapper, never()).resetExecuting(anyLong(), anyLong());
    }

    @Test
    void rejectsWhenSuggestionMissing() {
        OkxOrderSuggestionMapper mapper = mock(OkxOrderSuggestionMapper.class);
        when(mapper.selectById(999L)).thenReturn(null);
        OkxSuggestionExecutionService svc = new OkxSuggestionExecutionService(mapper, mock(OkxTradeService.class));
        assertThrows(IllegalArgumentException.class, () -> svc.execute(USER, 999L));
        verify(mapper, never()).claimNew(anyLong(), anyLong());
    }

    @Test
    void rejectsWhenNotOwner() {
        OkxOrderSuggestionMapper mapper = mock(OkxOrderSuggestionMapper.class);
        OkxOrderSuggestion s = suggestion("new");
        s.setUserId(8L); // 别人的
        when(mapper.selectById(100L)).thenReturn(s);
        OkxSuggestionExecutionService svc = new OkxSuggestionExecutionService(mapper, mock(OkxTradeService.class));
        assertThrows(IllegalArgumentException.class, () -> svc.execute(USER, 100L));
        verify(mapper, never()).claimNew(anyLong(), anyLong());
    }

    @Test
    void rejectsWhenAlreadyProcessed() {
        OkxOrderSuggestionMapper mapper = mock(OkxOrderSuggestionMapper.class);
        when(mapper.selectById(100L)).thenReturn(suggestion("executed"));
        OkxSuggestionExecutionService svc = new OkxSuggestionExecutionService(mapper, mock(OkxTradeService.class));
        assertThrows(IllegalArgumentException.class, () -> svc.execute(USER, 100L));
        verify(mapper, never()).claimNew(anyLong(), anyLong());
    }

    @ParameterizedTest
    @MethodSource("knownBusinessFailures")
    void resetsExecutingForKnownBusinessFailures(RuntimeException failure) {
        OkxOrderSuggestionMapper mapper = mock(OkxOrderSuggestionMapper.class);
        when(mapper.selectById(100L)).thenReturn(suggestion("new"));
        when(mapper.claimNew(100L, USER)).thenReturn(1);
        when(mapper.resetExecuting(100L, USER)).thenReturn(1);
        OkxTradeService trade = mock(OkxTradeService.class);
        when(trade.placeOrder(eq(USER), eq("BTC-USDT"), eq("buy"), eq("limit"),
                eq("0.01"), eq("30000"), eq("sugg-100"))).thenThrow(failure);

        OkxSuggestionExecutionService svc = new OkxSuggestionExecutionService(mapper, trade);
        RuntimeException actual = assertThrows(failure.getClass(), () -> svc.execute(USER, 100L));

        assertSame(failure, actual);
        verify(mapper).resetExecuting(100L, USER);
        verify(mapper, never()).markExecuted(anyLong(), anyLong());
    }

    private static Stream<Arguments> knownBusinessFailures() {
        return Stream.of(
                Arguments.of(new OkxOrderRejectedException("51008", "余额不足")),
                Arguments.of(new IllegalArgumentException("参数非法")),
                Arguments.of(new OkxCredentialException("凭证缺失")),
                Arguments.of(new RiskControlException("风控拒绝"))
        );
    }

    @Test
    void keepsExecutingForUnknownRuntimeFailure() {
        OkxOrderSuggestionMapper mapper = mock(OkxOrderSuggestionMapper.class);
        when(mapper.selectById(100L)).thenReturn(suggestion("new"));
        when(mapper.claimNew(100L, USER)).thenReturn(1);
        OkxTradeService trade = mock(OkxTradeService.class);
        RuntimeException failure = new RuntimeException("网络超时，结果未知");
        when(trade.placeOrder(eq(USER), eq("BTC-USDT"), eq("buy"), eq("limit"),
                eq("0.01"), eq("30000"), eq("sugg-100"))).thenThrow(failure);

        OkxSuggestionExecutionService svc = new OkxSuggestionExecutionService(mapper, trade);
        RuntimeException actual = assertThrows(RuntimeException.class, () -> svc.execute(USER, 100L));

        assertSame(failure, actual);
        verify(mapper, never()).resetExecuting(anyLong(), anyLong());
        verify(mapper, never()).markExecuted(anyLong(), anyLong());
    }

    // ---------------------------------------------------------------- 人工否决

    @Test
    void rejectsSuggestionWithoutPlacingAnyOrder() {
        OkxOrderSuggestionMapper mapper = mock(OkxOrderSuggestionMapper.class);
        when(mapper.selectById(100L)).thenReturn(suggestion("new"));
        when(mapper.rejectNew(100L, USER)).thenReturn(1);
        OkxTradeService trade = mock(OkxTradeService.class);

        OkxSuggestionExecutionService svc = new OkxSuggestionExecutionService(mapper, trade);
        Map<String, Object> result = svc.reject(USER, 100L);

        assertEquals("rejected", result.get("status"));

        verify(mapper).rejectNew(100L, USER);
        verify(mapper, never()).updateById(any(OkxOrderSuggestion.class));

        // 否决绝不能碰交易链路——这是「Agent 只读」边界在人工侧的对应约束
        verifyNoInteractions(trade);
    }

    @Test
    void cannotRejectAfterExecuted() {
        OkxOrderSuggestionMapper mapper = mock(OkxOrderSuggestionMapper.class);
        when(mapper.selectById(100L)).thenReturn(suggestion("executed"));
        OkxSuggestionExecutionService svc = new OkxSuggestionExecutionService(mapper, mock(OkxTradeService.class));
        assertThrows(IllegalArgumentException.class, () -> svc.reject(USER, 100L));
        verify(mapper, never()).updateById(any(OkxOrderSuggestion.class));
        verify(mapper, never()).rejectNew(anyLong(), anyLong());
    }

    @Test
    void cannotRejectOthersSuggestion() {
        OkxOrderSuggestionMapper mapper = mock(OkxOrderSuggestionMapper.class);
        OkxOrderSuggestion s = suggestion("new");
        s.setUserId(8L);
        when(mapper.selectById(100L)).thenReturn(s);
        OkxSuggestionExecutionService svc = new OkxSuggestionExecutionService(mapper, mock(OkxTradeService.class));
        assertThrows(IllegalArgumentException.class, () -> svc.reject(USER, 100L));
        verify(mapper, never()).updateById(any(OkxOrderSuggestion.class));
        verify(mapper, never()).rejectNew(anyLong(), anyLong());
    }

    @Test
    void doesNotPlaceOrderWhenRejectCasWasLost() {
        OkxOrderSuggestionMapper mapper = mock(OkxOrderSuggestionMapper.class);
        when(mapper.selectById(100L)).thenReturn(suggestion("new"));
        when(mapper.rejectNew(100L, USER)).thenReturn(0);
        OkxTradeService trade = mock(OkxTradeService.class);

        OkxSuggestionExecutionService svc = new OkxSuggestionExecutionService(mapper, trade);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> svc.reject(USER, 100L));

        assertTrue(ex.getMessage().contains("已被其他请求处理"));
        verifyNoInteractions(trade);
        verify(mapper).rejectNew(100L, USER);
        verify(mapper, never()).updateById(any(OkxOrderSuggestion.class));
    }

    @Test
    void cannotExecuteTwiceAfterRejected() {
        OkxOrderSuggestionMapper mapper = mock(OkxOrderSuggestionMapper.class);
        when(mapper.selectById(100L)).thenReturn(suggestion("rejected"));
        OkxTradeService trade = mock(OkxTradeService.class);
        OkxSuggestionExecutionService svc = new OkxSuggestionExecutionService(mapper, trade);

        // 否决过的单子不能再被执行——否则「拒绝」只是装饰
        assertThrows(IllegalArgumentException.class, () -> svc.execute(USER, 100L));
        verifyNoInteractions(trade);
    }
}
