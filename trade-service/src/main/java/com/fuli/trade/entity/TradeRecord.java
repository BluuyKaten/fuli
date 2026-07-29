package com.fuli.trade.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("trade_record")
public class TradeRecord implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String stockCode;

    private String stockName;

    private Integer tradeType;

    private BigDecimal tradePrice;

    private Integer tradeQuantity;

    private BigDecimal tradeAmount;

    private BigDecimal commission;

    private BigDecimal tax;

    private BigDecimal totalCost;

    private BigDecimal profitLoss;

    private BigDecimal profitLossRatio;

    private LocalDate tradeDate;

    private LocalDateTime tradeTime;

    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
