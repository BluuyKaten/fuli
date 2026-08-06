package com.fuli.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("position_summary")
public class PositionSummary implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String stockCode;

    private String stockName;

    private Integer totalQuantity;

    private BigDecimal avgCost;

    private BigDecimal currentPrice;

    private BigDecimal marketValue;

    private BigDecimal unrealizedPnl;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
