package com.fuli.data.crypto.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 加密标的与衍生品元数据，对应 {@code data_db.crypto_instrument}（V10）。
 *
 * <p>与 {@code instrument} 平行但独立：后者仍是 K 线展示用的标的主表且列宽只有 20，
 * 这里用 VARCHAR(64) 存 OKX 原始 instId 并承载合约/期权专属字段（ADR-0009 决策 5）。
 */
@Data
@TableName("crypto_instrument")
public class CryptoInstrument implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** OKX 原始 instId，不带 .CRYPTO 后缀。 */
    @TableId(type = IdType.INPUT)
    private String instId;

    /** SPOT / SWAP / FUTURES / OPTION；受控集合在应用层校验，不设 CHECK。 */
    private String instType;

    private String instFamily;

    /** 期权标的，如 BTC-USD。 */
    private String uly;

    private String baseCcy;

    private String quoteCcy;

    private String settleCcy;

    private BigDecimal ctVal;

    private BigDecimal ctMult;

    /** linear / inverse。 */
    private String ctType;

    /** C / P。 */
    private String optType;

    private BigDecimal strike;

    /** 毫秒 epoch，不是秒。 */
    private Long expiryTime;

    private BigDecimal lever;

    private BigDecimal tickSz;

    private BigDecimal lotSz;

    private BigDecimal minSz;

    private BigDecimal maxLmtSz;

    private String state;

    private Long listTime;

    /** OKX 更新时间，毫秒 epoch。 */
    private Long uTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
