package com.fuli.data.okx;

import com.fuli.data.okx.entity.OkxOrderSuggestion;
import com.fuli.data.okx.mapper.OkxOrderSuggestionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 建议单测试（ADR-0010 决策 4）。
 *
 * <p>重点不是「能不能存」，而是**它只是一条建议**：状态固定 new、source 固定 agent、
 * 不产生任何订单副作用；参数非法时明确拒绝，不静默存一条垃圾建议。
 */
class OkxSuggestionServiceTest {

    private static final long USER = 1L;

    private OkxOrderSuggestionMapper mapper;

    private OkxSuggestionService service;

    @BeforeEach
    void setUp() {
        mapper = mock(OkxOrderSuggestionMapper.class);
        service = new OkxSuggestionService(mapper);
    }

    @Test
    @DisplayName("正常建议：instId 归一化、tdMode 固定 cash、status=new、source=agent")
    void recordsSuggestionWithoutTouchingOrders() {
        OkxSuggestionView view = service.submit(new OkxSuggestionRequest(USER, "BTC-USDT.CRYPTO",
                "BUY", null, new BigDecimal("0.01"), new BigDecimal("30000"),
                "回踩 30 日均线", new BigDecimal("0.6")));

        ArgumentCaptor<OkxOrderSuggestion> captor = ArgumentCaptor.forClass(OkxOrderSuggestion.class);
        verify(mapper).insert(captor.capture());
        OkxOrderSuggestion row = captor.getValue();
        assertEquals(USER, row.getUserId());
        assertEquals("BTC-USDT", row.getInstId(), ".CRYPTO 后缀要去掉，OKX 只认裸 instId");
        assertEquals("buy", row.getSide(), "side 统一小写");
        assertEquals("limit", row.getOrdType(), "默认限价");
        assertEquals("cash", row.getTdMode());
        assertEquals("new", row.getStatus());
        assertEquals("agent", row.getSource());
        assertEquals("buy", view.side());
    }

    @Test
    @DisplayName("市价建议不保留 px（限价字段与市价互斥，留着会被误读）")
    void marketSuggestionDropsPrice() {
        service.submit(new OkxSuggestionRequest(USER, "BTC-USDT", "sell", "market",
                new BigDecimal("0.02"), new BigDecimal("12345"), null, null));

        ArgumentCaptor<OkxOrderSuggestion> captor = ArgumentCaptor.forClass(OkxOrderSuggestion.class);
        verify(mapper).insert(captor.capture());
        assertNull(captor.getValue().getPx());
    }

    @Test
    @DisplayName("参数校验：缺 userId / 非加密标的 / side 非法 / 限价缺价 / 数量非正 / 置信度越界")
    void rejectsInvalidSuggestions() {
        assertThrows(IllegalArgumentException.class, () -> service.submit(null));
        assertThrows(IllegalArgumentException.class, () -> service.submit(new OkxSuggestionRequest(
                null, "BTC-USDT", "buy", "limit", BigDecimal.ONE, BigDecimal.ONE, null, null)));
        assertThrows(IllegalArgumentException.class, () -> service.submit(new OkxSuggestionRequest(
                USER, "600519.SH", "buy", "limit", BigDecimal.ONE, BigDecimal.ONE, null, null)));
        assertThrows(IllegalArgumentException.class, () -> service.submit(new OkxSuggestionRequest(
                USER, "BTC-USDT", "hold", "limit", BigDecimal.ONE, BigDecimal.ONE, null, null)));
        assertThrows(IllegalArgumentException.class, () -> service.submit(new OkxSuggestionRequest(
                USER, "BTC-USDT", "buy", "limit", BigDecimal.ONE, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> service.submit(new OkxSuggestionRequest(
                USER, "BTC-USDT", "buy", "limit", BigDecimal.ZERO, BigDecimal.ONE, null, null)));
        assertThrows(IllegalArgumentException.class, () -> service.submit(new OkxSuggestionRequest(
                USER, "BTC-USDT", "buy", "limit", BigDecimal.ONE, BigDecimal.ONE, null,
                new BigDecimal("1.5"))));
        verify(mapper, never()).insert(any(OkxOrderSuggestion.class));
    }

    @Test
    @DisplayName("理由超长会被裁剪，不因一条建议把 insert 撑爆")
    void truncatesReason() {
        service.submit(new OkxSuggestionRequest(USER, "BTC-USDT", "buy", "limit",
                BigDecimal.ONE, BigDecimal.ONE, "x".repeat(2000), null));

        ArgumentCaptor<OkxOrderSuggestion> captor = ArgumentCaptor.forClass(OkxOrderSuggestion.class);
        verify(mapper).insert(captor.capture());
        assertTrue(captor.getValue().getReason().length() <= 512);
    }

    @Test
    @DisplayName("只返回本人的建议，limit 有上限")
    void recentIsScopedToUser() {
        when(mapper.selectRecentByUser(USER, 100)).thenReturn(List.of());
        service.recent(USER, 9999);
        verify(mapper).selectRecentByUser(USER, 100);
    }
}
