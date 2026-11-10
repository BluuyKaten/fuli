package com.fuli.data.crypto.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 账户余额快照，对应 {@code data_db.crypto_account}（V12）。
 *
 * <p>一次 WS {@code account} 推送会产生多行：每个币种一行 + 一行 {@code ccy='TOTAL'}（全账户折合权益）。
 * 唯一键 {@code (user_id, ccy, u_time)} 负责去重——交易所会定时重推同一份快照。
 */
@Data
@TableName("crypto_account")
public class CryptoAccount implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 币种；{@code TOTAL} 为合成行。 */
    private String ccy;

    /**
     * OKX 的 {@code eq}（该币种权益）。
     *
     * <p><b>Java 字段不能叫 eq</b>：MyBatis-Plus 生成的 SQL 脚本里有
     * {@code <if test="eq != null">}，而 {@code eq} 是 OGNL 的运算符关键字，
     * 会直接抛 "Malformed OGNL expression"。列名保持 {@code eq}（交易所口径），
     * 用 {@code @TableField} 显式映射。
     */
    @TableField("eq")
    private BigDecimal equity;

    private BigDecimal cashBal;

    private BigDecimal availBal;

    private BigDecimal availEq;

    private BigDecimal frozenBal;

    private BigDecimal upl;

    private BigDecimal isoEq;

    private BigDecimal disEq;

    /** OKX 快照时间（毫秒）。 */
    private Long uTime;

    private LocalDateTime snapshotAt;
}
