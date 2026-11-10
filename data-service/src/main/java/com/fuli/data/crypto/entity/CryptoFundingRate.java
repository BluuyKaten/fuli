package com.fuli.data.crypto.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 永续合约资金费率时序，对应 {@code data_db.crypto_funding_rate}（V10）。
 * 主键 {@code (inst_id, ts_ms)}，时间一律毫秒 epoch。
 */
@Data
@TableName("crypto_funding_rate")
public class CryptoFundingRate implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.INPUT)
    private String instId;

    private Long tsMs;

    private BigDecimal fundingRate;

    private BigDecimal nextFundingRate;

    private BigDecimal realizedRate;

    private Long fundingTime;

    private Long nextFundingTime;

    private String method;
}
