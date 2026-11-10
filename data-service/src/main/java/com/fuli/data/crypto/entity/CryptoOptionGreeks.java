package com.fuli.data.crypto.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 期权 Greeks 时序，对应 {@code data_db.crypto_option_greeks}（V10）。
 * PA / BS 两套口径由 {@code account/set-greeks} 切换，两套字段都保留。
 * 主键 {@code (inst_id, ts_ms)}，时间一律毫秒 epoch。
 */
@Data
@TableName("crypto_option_greeks")
public class CryptoOptionGreeks implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.INPUT)
    private String instId;

    private Long tsMs;

    private BigDecimal iv;

    private BigDecimal delta;

    private BigDecimal gamma;

    private BigDecimal theta;

    private BigDecimal vega;

    private BigDecimal deltaBs;

    private BigDecimal gammaBs;

    private BigDecimal thetaBs;

    private BigDecimal vegaBs;

    private BigDecimal markVol;
}
