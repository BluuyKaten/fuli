package com.fuli.data.crypto.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * OKX 成交明细，对应 {@code data_db.crypto_fill}（V10）。
 *
 * <p>主键是 {@code (inst_id, trade_id)} 复合键：tradeId 只在单个 instId 维度唯一。
 * 这里把 instId 标为手动输入主键仅为满足 MyBatis-Plus 单主键模型，
 * <b>不要用 selectById / updateById</b>，用 QueryWrapper 带齐两个条件。
 * 强平/ADL 场景下 tradeId 为负值。
 */
@Data
@TableName("crypto_fill")
public class CryptoFill implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.INPUT)
    private String instId;

    private String tradeId;

    private String ordId;

    private String clOrdId;

    private String side;

    private BigDecimal fillPx;

    private BigDecimal fillSz;

    private BigDecimal fee;

    private String feeCcy;

    private String posId;

    private String billId;

    /** 毫秒 epoch。 */
    private Long fillTime;

    private LocalDateTime createTime;
}
