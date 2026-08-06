package com.fuli.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 幂等消息实体（防止 Feign 重试导致重复扣款/入账）
 */
@Data
@TableName("idempotent_message")
public class IdempotentMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 状态：处理中 */
    public static final int STATUS_PROCESSING = 0;
    /** 状态：成功 */
    public static final int STATUS_SUCCESS = 1;
    /** 状态：失败 */
    public static final int STATUS_FAILED = 2;

    /** 资金方向：扣款（买入） */
    public static final int DIRECTION_DEDUCT = 1;
    /** 资金方向：入账（卖出） */
    public static final int DIRECTION_ADD = 2;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 消息唯一 ID */
    private String msgId;

    /** 用户 ID */
    private Long userId;

    /** 变动金额 */
    private BigDecimal amount;

    /** 资金方向：1-扣款 2-入账 */
    private Integer cashDirection;

    /** 状态：0-PROCESSING 1-SUCCESS 2-FAILED */
    private Integer status;

    /** 失败原因 */
    private String errorMsg;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
