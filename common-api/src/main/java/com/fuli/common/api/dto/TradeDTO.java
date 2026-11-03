package com.fuli.common.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class TradeDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    /**
     * 客户端幂等键：同一笔提交（双击/网络重试）复用同一个值，服务端据此去重，
     * 避免重复扣款 + 重复落单。可为空（为空则不启用幂等去重）。
     */
    private String requestId;

    @NotNull(message = "用户ID不能为空")
    private Long userId;

    @NotNull(message = "股票代码不能为空")
    private String stockCode;

    @NotNull(message = "股票名称不能为空")
    private String stockName;

    @NotNull(message = "交易类型不能为空")
    private Integer tradeType;

    @NotNull(message = "成交价格不能为空")
    private BigDecimal tradePrice;

    @NotNull(message = "成交数量不能为空")
    private Integer tradeQuantity;

    private BigDecimal commission;

    private BigDecimal tax;

    private String remark;

    @NotNull(message = "交易日期不能为空")
    private LocalDate tradeDate;

    private LocalDateTime tradeTime;
}
