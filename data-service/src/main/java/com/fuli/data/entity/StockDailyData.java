package com.fuli.data.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 股票日线行情实体（data-service 自有）。
 *
 * <p>归属 {@code data_db.stock_daily_data} 表，由 data-service 独立维护，
 * 其他服务通过 {@code DataFeignClient} 访问，不再直接读写此表。
 */
@Data
@TableName("stock_daily_data")
public class StockDailyData implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String stockCode;

    private String tradeDate;

    private BigDecimal openPrice;

    private BigDecimal highPrice;

    private BigDecimal lowPrice;

    private BigDecimal closePrice;

    private BigDecimal preClose;

    private BigDecimal changeAmount;

    private BigDecimal pctChg;

    private BigDecimal vol;

    private BigDecimal amount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
