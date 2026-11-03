package com.fuli.trade.event;

import com.fuli.common.api.exception.BusinessException;
import com.fuli.common.api.enums.TradeTypeEnum;
import com.fuli.trade.service.CashChangeService;
import com.fuli.trade.service.LocalMessageService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 事务提交后的异步资金投递：首次成功必须回写消息状态，失败则保持待重试。
 */
class TradeEventListenerTest {

    private final CashChangeService cash = mock(CashChangeService.class);
    private final LocalMessageService messages = mock(LocalMessageService.class);
    private final TradeEventListener listener = new TradeEventListener(cash, messages);

    @Test
    void successfulCashChangeAcknowledgesMessage() {
        listener.handleTradeCreated(event(TradeTypeEnum.SELL.getCode(), "sell-1"));

        verify(cash).addCash(1L, new BigDecimal("100.00"), "sell-1");
        verify(messages).markSuccessByMsgId("sell-1");
    }

    @Test
    void failedCashChangeKeepsMessageRetryable() {
        doThrow(new BusinessException("资金服务不可用"))
                .when(cash).addCash(any(), any(), anyString());

        assertThrows(BusinessException.class,
                () -> listener.handleTradeCreated(event(TradeTypeEnum.SELL.getCode(), "sell-2")));

        verify(messages, never()).markSuccessByMsgId(anyString());
    }

    @Test
    void deletedTradeAlsoAcknowledgesReverseMessage() {
        listener.handleTradeDeleted(new TradeDeletedEvent(this, 9L, 1L, TradeTypeEnum.BUY.getCode(),
                "600519", "贵州茅台", new BigDecimal("100.00"), 100, "rev-1"));

        verify(cash).addCash(1L, new BigDecimal("100.00"), "rev-1");
        verify(messages).markSuccessByMsgId("rev-1");
    }

    private TradeCreatedEvent event(int tradeType, String msgId) {
        return new TradeCreatedEvent(this, 7L, 1L, tradeType, "600519", "贵州茅台",
                new BigDecimal("100.00"), new BigDecimal("10.00"), 10, msgId);
    }
}
