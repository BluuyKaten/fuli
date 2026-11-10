package com.fuli.data.market;

import java.math.BigDecimal;

/**
 * 一根加密 K 线（已归一化到 bar 口径）。
 *
 * @param ts     bar 开盘时刻，Unix 秒，UTC
 * @param open   开盘价
 * @param high   最高价
 * @param low    最低价
 * @param close  收盘价
 * @param volume 基础币成交量（币）
 * @param amount 计价币成交额（元/USDT）
 */
public record CryptoCandle(long ts, BigDecimal open, BigDecimal high, BigDecimal low,
                           BigDecimal close, BigDecimal volume, BigDecimal amount) {
}
