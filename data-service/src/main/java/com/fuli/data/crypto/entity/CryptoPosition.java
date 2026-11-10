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
 * OKX 持仓快照，对应 {@code data_db.crypto_position}（V10）。
 *
 * <p><b>对账注意</b>（okx.txt）：多个持仓变化可能聚合成一条推送、只带最新 tradeId；
 * 强平/ADL 触发的持仓变化<b>不更新 tradeId</b>，必须同时比较 pos 或 uTime 才能识别。
 */
@Data
@TableName("crypto_position")
public class CryptoPosition implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** OKX 持仓 ID：mgnMode + posSide + instId + ccy 派生。 */
    @TableId(type = IdType.INPUT)
    private String posId;

    private String instId;

    private String instType;

    private String mgnMode;

    private String posSide;

    /** 持仓数量，可为负。 */
    private BigDecimal pos;

    private BigDecimal availPos;

    private BigDecimal avgPx;

    private BigDecimal upl;

    private BigDecimal uplRatio;

    private BigDecimal lever;

    private BigDecimal liqPx;

    private BigDecimal imr;

    private BigDecimal mmr;

    private BigDecimal mgnRatio;

    /** 最新成交 ID；对账时还需比较 pos 或 uTime。 */
    private String tradeId;

    private Long cTime;

    private Long uTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
