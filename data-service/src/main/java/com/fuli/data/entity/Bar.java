package com.fuli.data.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 行情 K 线（原始价）。
 *
 * <p>归属 {@code data_db.bar}，一张表覆盖全市场全周期，见
 * docs/adr/0007-unified-bar-store-and-time-axis.md。
 *
 * <h3>单位口径（务必与迁移脚本、同步出口一致）</h3>
 * <ul>
 *   <li>{@code volume} 是股/币，不是手</li>
 *   <li>{@code amount} 是计价货币元，不是千元</li>
 *   <li>{@code preClose} 是除权口径的前收盘，原样搬运，不可由 OHLC 推算</li>
 * </ul>
 * 换算只发生在两个地方：历史迁移 SQL 与本服务同步出口（TushareSyncServiceImpl）。
 *
 * <h3>为什么没有 @TableId</h3>
 * 主键是 {@code (instrument_code, period, ts)} 复合键，MyBatis-Plus 的单主键模型表达不了。
 * 因此本实体不继承 BaseMapper 的按 ID 操作，所有 SQL 在 {@code BarMapper} 里显式声明。
 */
@Data
public class Bar implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 标的代码，如 600519.SH / AAPL.US */
    private String instrumentCode;

    /** 周期：1m/5m/15m/30m/1h/4h/1d/1w/1mo */
    private String period;

    /** bar 开盘时刻，UNIX 秒，UTC */
    private Long ts;

    private BigDecimal openPrice;

    private BigDecimal highPrice;

    private BigDecimal lowPrice;

    private BigDecimal closePrice;

    /** 前收盘（除权口径，原样搬运） */
    private BigDecimal preClose;

    /** 成交量，股/币 */
    private BigDecimal volume;

    /** 成交额，计价货币元 */
    private BigDecimal amount;
}
