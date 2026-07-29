package com.fuli.common.api.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

@Data
public class MonthlyProfitVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String month;
    private BigDecimal profitLoss;
    private Integer tradeCount;
    private BigDecimal winRate;
}
