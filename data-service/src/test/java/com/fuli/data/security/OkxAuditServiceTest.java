package com.fuli.data.security;

import com.fuli.data.okx.entity.OkxAuditLog;
import com.fuli.data.okx.mapper.OkxAuditLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 审计双写测试（ADR-0010 决策 3）。
 *
 * <p>核心断言是反向的：**落表失败不能把业务打成异常**。
 * 审计是事后追责手段，不是业务正确性的前提；因为数据库抖动让一笔已发到交易所的订单返回 500，
 * 调用方重试才会真的产生重复下单。
 */
class OkxAuditServiceTest {

    private OkxAuditLogMapper mapper;

    private OkxAuditService service;

    @BeforeEach
    void setUp() {
        mapper = mock(OkxAuditLogMapper.class);
        service = new OkxAuditService(mapper);
    }

    @Test
    @DisplayName("成功路径：字段一一落到表里")
    void recordsAllFields() {
        service.record(new OkxAuditService.Event("placeOrder", "orders:write", "SUCCESS", 7L,
                "BTC-USDT", "BTC-USDT.CRYPTO", "buy", new BigDecimal("0.01"),
                new BigDecimal("30000"), "ord-1", "c1", "ordType=limit"));

        ArgumentCaptor<OkxAuditLog> captor = ArgumentCaptor.forClass(OkxAuditLog.class);
        verify(mapper).insert(captor.capture());
        OkxAuditLog row = captor.getValue();
        assertEquals(7L, row.getUserId());
        assertEquals("placeOrder", row.getAction());
        assertEquals("orders:write", row.getScope());
        assertEquals("SUCCESS", row.getOutcome());
        assertEquals("BTC-USDT", row.getInstId());
        assertEquals("BTC-USDT.CRYPTO", row.getSymbol());
        assertEquals("buy", row.getSide());
        assertEquals(new BigDecimal("0.01"), row.getSz());
        assertEquals(new BigDecimal("30000"), row.getPx());
        assertEquals("ord-1", row.getOrdId());
        assertEquals("c1", row.getClOrdId());
        assertEquals("ordType=limit", row.getDetail());
        assertNotNull(row.getCreateTime());
    }

    @Test
    @DisplayName("服务账号（无 userId）落 NULL，而不是编一个 0 出来")
    void serviceAccountWritesNullUserId() {
        service.record(OkxAuditService.Event.of("capability.market_ticker", "orders:read",
                "ALLOWED", null, "principal=service:ai-service"));

        ArgumentCaptor<OkxAuditLog> captor = ArgumentCaptor.forClass(OkxAuditLog.class);
        verify(mapper).insert(captor.capture());
        assertNull(captor.getValue().getUserId());
    }

    @Test
    @DisplayName("落表失败：吞掉异常，不影响主流程")
    void persistFailureIsSwallowed() {
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"))
                .when(mapper).insert(any(OkxAuditLog.class));

        // 不抛异常即为通过；日志里仍有 [OKX-AUDIT] 那一行兜底
        service.record(OkxAuditService.Event.of("placeOrder", "orders:write", "SUCCESS", 7L, "-"));
    }

    @Test
    @DisplayName("清洗与截断：换行变下划线，超长按列宽裁剪（否则严格模式会直接丢审计）")
    void sanitizesAndTruncates() {
        String evil = "line1\nline2\tline3" + "x".repeat(2000);
        service.record(OkxAuditService.Event.of("a".repeat(200), "s".repeat(100), "SUCCESS", 1L, evil));

        ArgumentCaptor<OkxAuditLog> captor = ArgumentCaptor.forClass(OkxAuditLog.class);
        verify(mapper).insert(captor.capture());
        OkxAuditLog row = captor.getValue();
        assertTrue(row.getDetail().length() <= 512);
        assertTrue(row.getAction().length() <= 64);
        assertTrue(row.getScope().length() <= 32);
        assertTrue(!row.getDetail().contains("\n") && !row.getDetail().contains("\t"));
    }

    @Test
    @DisplayName("null 事件：静默忽略，不 NPE")
    void nullEventIsIgnored() {
        service.record(null);
        verify(mapper, org.mockito.Mockito.never()).insert(any(OkxAuditLog.class));
    }
}
