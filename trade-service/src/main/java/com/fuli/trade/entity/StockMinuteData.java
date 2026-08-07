package com.fuli.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 分钟 K 线（1/5/15/60 分钟）
 */
@Data
@TableName("stock_minute_data")
public class StockMinuteData implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** Tushare 格式：300750.SZ */
    private String stockCode;

    /** K 线时间（精确到分钟） */
    private LocalDateTime tradeTime;

    /** 周期：1/5/15/60 */
    private Integer period;

    private BigDecimal openPrice;
    private BigDecimal highPrice;
    private BigDecimal lowPrice;
    private BigDecimal closePrice;
    private Long vol;
    private BigDecimal amount;
}
