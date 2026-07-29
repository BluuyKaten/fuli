package com.fuli.common.api.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class StatisticsVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long userId;
    private String stockCode;
    private String stockName;
    private Integer totalTrades;
    private Integer buyCount;
    private Integer sellCount;
    private BigDecimal totalBuyAmount;
    private BigDecimal totalSellAmount;
    private BigDecimal totalProfitLoss;
    private BigDecimal winRate;
    private BigDecimal profitLossRatio;
    private BigDecimal avgProfit;
    private BigDecimal avgLoss;
    private BigDecimal maxProfit;
    private BigDecimal maxLoss;
    private LocalDate startDate;
    private LocalDate endDate;
}
