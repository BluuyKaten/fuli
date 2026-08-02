package com.fuli.common.api.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class PositionVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String stockCode;
    private String stockName;
    private Integer holdingQuantity;
    private Integer availableQuantity;
    private BigDecimal costPrice;
    private BigDecimal currentPrice;
    private BigDecimal marketValue;
    private BigDecimal floatingProfitLoss;
    private BigDecimal dailyProfitLoss;
    private LocalDate priceDate;
}
