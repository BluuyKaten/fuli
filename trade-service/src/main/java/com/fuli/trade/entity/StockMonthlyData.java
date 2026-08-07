package com.fuli.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 月 K 线
 */
@Data
@TableName("stock_monthly_data")
public class StockMonthlyData implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String stockCode;
    private LocalDate tradeDate;
    private BigDecimal openPrice;
    private BigDecimal highPrice;
    private BigDecimal lowPrice;
    private BigDecimal closePrice;
    private Long vol;
    private BigDecimal amount;
}
