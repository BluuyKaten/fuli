package com.fuli.common.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class TradeVO {
    private Long id;
    private Long userId;
    private String symbol;
    private String side;
    private BigDecimal quantity;
    private BigDecimal price;
    private BigDecimal profitLoss;
    private LocalDateTime tradeTime;
}
