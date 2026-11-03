package com.fuli.trade.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuli.common.api.feign.AuthFeignClient;
import com.fuli.trade.dto.CashChangeMessage;
import com.fuli.trade.mapper.LocalMessageMapper;
import com.fuli.trade.service.impl.CashChangeServiceImpl;
import com.fuli.trade.service.impl.CashCompensationServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 买入扣款后本地事务失败时的退款补偿：方向必须反转，且同一原始消息只登记一次。
 */
class CashCompensationTest {

    private final LocalMessageService messages = mock(LocalMessageService.class);
    private final LocalMessageMapper mapper = mock(LocalMessageMapper.class);
    private final CashChangeService cash = new CashChangeServiceImpl(mock(AuthFeignClient.class));
    private final CashCompensationServiceImpl compensation =
            new CashCompensationServiceImpl(messages, mapper, cash);

    @Test
    void schedulesRefundInReverseDirection() throws Exception {
        when(mapper.selectCount(any())).thenReturn(0L);

        compensation.scheduleRefund(7L, new BigDecimal("1005.00"), "BUY-abc");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(messages).createPendingMessage(eq("REF-BUY-abc"), eq("TRADE_COMPENSATE"), payload.capture());

        CashChangeMessage message = new ObjectMapper().readValue(payload.getValue(), CashChangeMessage.class);
        assertEquals("REF-BUY-abc", message.getMsgId());
        assertEquals(7L, message.getUserId());
        assertEquals(0, new BigDecimal("1005.00").compareTo(message.getAmount()));
        // 买入是扣款(1)，补偿必须是入账(2)，否则会把钱再扣一次
        assertEquals(2, message.getDirection());
    }

    @Test
    void duplicateRefundIsIgnored() {
        when(mapper.selectCount(any())).thenReturn(1L);

        compensation.scheduleRefund(7L, new BigDecimal("1005.00"), "BUY-abc");

        verify(messages, never()).createPendingMessage(anyString(), anyString(), anyString());
    }

    @Test
    void invalidArgumentsAreRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> compensation.scheduleRefund(7L, BigDecimal.ZERO, "BUY-abc"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> compensation.scheduleRefund(7L, new BigDecimal("10"), null));
    }
}
