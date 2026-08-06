package com.fuli.trade.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 交易创建事件（用于异步处理资金变动）
 */
@Getter
public class TradeCreatedEvent extends ApplicationEvent {

    private final Long tradeId;
    private final Long userId;
    private final Integer tradeType;
    private final String stockCode;
    private final String stockName;
    private final java.math.BigDecimal amount;
    private final java.math.BigDecimal tradePrice;
    private final Integer tradeQuantity;

    public TradeCreatedEvent(Object source, Long tradeId, Long userId, Integer tradeType,
                             String stockCode, String stockName,
                             java.math.BigDecimal amount,
                             java.math.BigDecimal tradePrice,
                             Integer tradeQuantity) {
        super(source);
        this.tradeId = tradeId;
        this.userId = userId;
        this.tradeType = tradeType;
        this.stockCode = stockCode;
        this.stockName = stockName;
        this.amount = amount;
        this.tradePrice = tradePrice;
        this.tradeQuantity = tradeQuantity;
    }
}
