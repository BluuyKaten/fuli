package com.fuli.trade.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class PriceAlertVO {
    private Long id;
    private Long userId;
    private String stockCode;
    private String stockName;
    private Integer alertType;
    private BigDecimal targetValue;
    private String notes;
    private Integer status;
    private LocalDateTime triggeredTime;
    private BigDecimal triggeredValue;
    private BigDecimal currentPrice;
    private BigDecimal changePct;
    private BigDecimal distancePct;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
