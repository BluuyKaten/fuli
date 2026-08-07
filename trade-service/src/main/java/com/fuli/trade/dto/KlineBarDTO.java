package com.fuli.trade.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * K 线数据 DTO（统一返回给前端）
 */
@Data
public class KlineBarDTO {
    /** 时间戳（Unix 秒） */
    private Long time;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private Long volume;
}
