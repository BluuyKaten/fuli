package com.fuli.common.api.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class TradeVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long userId;
    private String stockCode;
    private String stockName;
    private Integer tradeType;
    private String tradeTypeName;
    private BigDecimal tradePrice;
    private Integer tradeQuantity;
    private BigDecimal tradeAmount;
    private BigDecimal commission;
    private BigDecimal tax;
    private BigDecimal totalCost;
    private BigDecimal profitLoss;
    private BigDecimal profitLossRatio;
    private LocalDate tradeDate;
    private LocalDateTime tradeTime;
    private String remark;
    private LocalDateTime createTime;
}
