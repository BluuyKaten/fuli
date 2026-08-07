package com.fuli.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 实时行情快照（5 档盘口）
 */
@Data
@TableName("realtime_quote")
public class RealtimeQuote implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.NONE)
    private String stockCode;

    private LocalDate tradeDate;
    private BigDecimal closePrice;
    private BigDecimal preClose;
    private BigDecimal openPrice;
    private BigDecimal highPrice;
    private BigDecimal lowPrice;
    private Long vol;
    private BigDecimal amount;

    // 买盘
    private BigDecimal bid1Price;
    private Integer bid1Vol;
    private BigDecimal bid2Price;
    private Integer bid2Vol;
    private BigDecimal bid3Price;
    private Integer bid3Vol;
    private BigDecimal bid4Price;
    private Integer bid4Vol;
    private BigDecimal bid5Price;
    private Integer bid5Vol;

    // 卖盘
    private BigDecimal ask1Price;
    private Integer ask1Vol;
    private BigDecimal ask2Price;
    private Integer ask2Vol;
    private BigDecimal ask3Price;
    private Integer ask3Vol;
    private BigDecimal ask4Price;
    private Integer ask4Vol;
    private BigDecimal ask5Price;
    private Integer ask5Vol;

    private LocalDateTime updateTime;

    /** 是否为新插入（非数据库字段） */
    @TableField(exist = false)
    private boolean isNew = false;
}
