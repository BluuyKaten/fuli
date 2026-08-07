package com.fuli.trade.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 实时行情 DTO（5 档）
 */
@Data
public class QuoteDTO {
    private String stockCode;
    private BigDecimal price;
    private BigDecimal preClose;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private Long volume;
    private BigDecimal amount;

    // 买盘
    private BigDecimal[] bidPrices = new BigDecimal[5];
    private Integer[] bidVols = new Integer[5];
    // 卖盘
    private BigDecimal[] askPrices = new BigDecimal[5];
    private Integer[] askVols = new Integer[5];

    private Long timestamp;
}
