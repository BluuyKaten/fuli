package com.fuli.trade.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;

/**
 * 交易删除事件（用于回滚持仓与资金）
 */
@Getter
public class TradeDeletedEvent extends ApplicationEvent {

    private final Long tradeId;
    private final Long userId;
    private final Integer tradeType;
    private final String stockCode;
    private final String stockName;
    private final BigDecimal amount;
    private final Integer tradeQuantity;

    public TradeDeletedEvent(Object source, Long tradeId, Long userId, Integer tradeType,
                             String stockCode, String stockName,
                             BigDecimal amount, Integer tradeQuantity) {
        super(source);
        this.tradeId = tradeId;
        this.userId = userId;
        this.tradeType = tradeType;
        this.stockCode = stockCode;
        this.stockName = stockName;
        this.amount = amount;
        this.tradeQuantity = tradeQuantity;
    }
}
