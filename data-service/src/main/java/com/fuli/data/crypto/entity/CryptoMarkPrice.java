package com.fuli.data.crypto.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 衍生品标记价格与指数价格时序，对应 {@code data_db.crypto_mark_price}（V10）。
 * 主键 {@code (inst_id, ts_ms)}，时间一律毫秒 epoch。
 */
@Data
@TableName("crypto_mark_price")
public class CryptoMarkPrice implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.INPUT)
    private String instId;

    private Long tsMs;

    private BigDecimal markPx;

    private BigDecimal idxPx;

    private BigDecimal sodUtc0;

    private BigDecimal sodUtc8;
}
