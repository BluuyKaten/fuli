package com.fuli.trade.dto;

import com.fuli.common.api.enums.TradeTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 本地消息表 payload 的强类型表示。
 *
 * <p>替代原来 {@code String.format("{\"userId\":%d,...}")} 手写 JSON 与
 * {@code indexOf} 手动解析，统一使用 Jackson 序列化 / 反序列化，
 * 避免特殊字符导致的解析错误，并具备 schema 约束。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CashChangeMessage {

    /** 用户 ID */
    private Long userId;

    /** 变动金额（始终为正数，方向由 {@link #direction} 决定） */
    private BigDecimal amount;

    /** 消息唯一 ID，用于幂等防重 */
    private String msgId;

    /**
     * 资金变动方向（与 {@link TradeTypeEnum#getCode()} 对齐）。
     * <ul>
     *   <li>1 = {@link TradeTypeEnum#BUY} → 扣款（deduct）</li>
     *   <li>2 = {@link TradeTypeEnum#SELL} → 入账（add）</li>
     * </ul>
     * 消息自包含方向，解析时无需依赖 topic。
     */
    private Integer direction;
}
