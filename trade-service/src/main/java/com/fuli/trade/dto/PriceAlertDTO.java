package com.fuli.trade.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class PriceAlertDTO {
    private String stockCode;
    private String stockName;
    /**
     * 1 - 价格上限/止盈 (当前价 >= targetValue)
     * 2 - 价格下限/止损 (当前价 <= targetValue)
     * 3 - 当日涨幅超过 % (涨幅 >= targetValue)
     * 4 - 当日跌幅超过 % (跌幅 <= -targetValue)
     */
    private Integer alertType;
    private BigDecimal targetValue;
    private String notes;
}
