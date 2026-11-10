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

    /**
     * 交易日期，整型 yyyyMMdd，如 20231103。
     *
     * <p>对应列类型为 {@code INT}（见 sql/migrations/V6__slim_daily_columns.sql）。
     * 用整型而非字符串，是为了让区间条件走索引：INT 列与字符串参数比较会触发
     * 隐式类型转换，导致 {@code uk_code_trade_date} 失效。
     * 对外 API 仍在 {@code StockDataController#toMap} 里转回 "yyyyMMdd" 字符串，
     * 保持前端与 Feign 调用方的契约不变。
     */
    private Integer tradeDate;

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
