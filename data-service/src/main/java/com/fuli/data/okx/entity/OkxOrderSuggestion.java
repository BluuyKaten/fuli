package com.fuli.data.okx.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Agent 下单建议，对应 {@code data_db.okx_order_suggestion}（V11）。
 *
 * <p><b>这不是订单</b>：本表只记录「Agent 建议怎么做」，不进入 {@code crypto_order} 状态机，
 * 也不触发任何下单请求（ADR-0010 决策 4）。{@code status} 表示人工处置结果。
 */
@Data
@TableName("okx_order_suggestion")
public class OkxOrderSuggestion implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String instId;

    private String side;

    private String ordType;

    private BigDecimal sz;

    private BigDecimal px;

    private String tdMode;

    private String reason;

    private BigDecimal confidence;

    /** new / accepted / rejected / expired，仅表示人工处置。 */
    private String status;

    private String source;

    private String traceId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
