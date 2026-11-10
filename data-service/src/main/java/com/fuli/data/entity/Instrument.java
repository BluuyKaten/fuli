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
 * 标的主表 {@code data_db.instrument}。
 *
 * <p>符号只承载身份（&lt;代码&gt;.&lt;市场&gt;），属性全部在这里：交易所、计价币种、
 * 价格精度、最小变动价位、交易单位、时区、交易时段。见
 * docs/adr/0006-instrument-identity-and-symbol-scheme.md。
 */
@Data
@TableName("instrument")
public class Instrument implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 标的代码，如 600519.SH / AAPL.US / BTCUSDT.CRYPTO */
    @TableId(type = IdType.INPUT)
    private String code;

    /** SH/SZ/BJ/US/CRYPTO */
    private String market;

    private String name;

    /** 计价币种：CNY/USD/USDT… */
    private String currency;

    /** 小数位，前端显示精度 */
    private Integer priceScale;

    /** 交易单位：A股 100，美股 1，加密可为 0.0001 */
    private BigDecimal lotSize;

    private BigDecimal tickSize;

    /** IANA 时区，如 Asia/Shanghai */
    private String tz;

    /** DAY / 24H */
    private String session;

    /** 1 正常 0 停牌/摘牌 */
    private Integer status;

    /** NASDAQ/NYSE/AMEX（US 必填） */
    private String exchange;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
