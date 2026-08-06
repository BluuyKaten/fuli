package com.fuli.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 本地消息表实体（用于跨服务事务最终一致性）
 */
@Data
@TableName("local_message")
public class LocalMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 消息状态：待处理 */
    public static final int STATUS_PENDING = 0;
    /** 消息状态：成功 */
    public static final int STATUS_SUCCESS = 1;
    /** 消息状态：失败（可重试） */
    public static final int STATUS_FAILED = 2;
    /** 消息状态：死信（超过最大重试次数） */
    public static final int STATUS_DEAD_LETTER = 3;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 消息唯一 ID */
    private String msgId;

    /** 消息主题 */
    private String topic;

    /** 消息体（JSON） */
    private String payload;

    /** 状态 */
    private Integer status;

    /** 已重试次数 */
    private Integer retryCount;

    /** 最大重试次数 */
    private Integer maxRetry;

    /** 下次重试时间 */
    private LocalDateTime nextRetryTime;

    /** 最后一次错误信息 */
    private String lastError;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
