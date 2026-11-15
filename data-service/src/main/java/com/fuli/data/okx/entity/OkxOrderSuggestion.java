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

    /**
     * new / executing / executed / rejected / expired，仅表示人工处置结果。
     *
     * <p>人工确认先将 {@code new} 原子抢占为 {@code executing}，下单成功后再推进到
     * {@code executed}；明确业务失败回退到 {@code new}，未知网络结果保持 {@code executing}。
     * {@code rejected} 只能由人工拒绝入口从 {@code new} 推进。状态机对应表约束
     * {@code chk_okx_suggestion_status}，
     * 见 {@code sql/migrations/V16__okx_suggestion_executing.sql}。
     */
    private String status;

    private String source;

    private String traceId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
