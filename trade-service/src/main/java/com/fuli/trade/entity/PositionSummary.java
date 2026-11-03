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

    // 以下三列历史上预留用于缓存行情，但当前从未写入：
    // 市值与浮动盈亏由 analysis-service 实时计算，避免缓存口径漂移。保留列但不映射业务逻辑。
    private BigDecimal currentPrice;

    private BigDecimal marketValue;

    private BigDecimal unrealizedPnl;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
