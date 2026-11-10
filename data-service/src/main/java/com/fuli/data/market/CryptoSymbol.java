package com.fuli.data.market;

import com.fuli.common.api.util.StockCodeUtil;

/**
 * 加密标的符号换算（内部两段式 ↔ 交易所格式）。
 *
 * <p>内部符号：{@code BTC-USDT.CRYPTO}（ADR-0006/0008 决策 1）；连字符左侧是基础币、右侧是计价币。
 */
public final class CryptoSymbol {

    private CryptoSymbol() {
    }

    /** 基础币，如 {@code BTC-USDT.CRYPTO → BTC}。 */
    public static String base(String symbol) {
        return parts(symbol)[0];
    }

    /** 计价币，如 {@code BTC-USDT.CRYPTO → USDT}。 */
    public static String quote(String symbol) {
        return parts(symbol)[1];
    }

    /** 交易所代码（去掉市场后缀的 {@code BASE-QUOTE}），如 {@code BTC-USDT}。 */
    public static String pair(String symbol) {
        return StockCodeUtil.pureCode(symbol);
    }

    /** Binance 符号：去连字符，如 {@code BTC-USDT.CRYPTO → BTCUSDT}。 */
    public static String toBinance(String symbol) {
        return pair(symbol).replace("-", "").toUpperCase();
    }

    /** Gate 符号：连字符转下划线，如 {@code BTC-USDT.CRYPTO → BTC_USDT}。 */
    public static String toGate(String symbol) {
        return pair(symbol).replace("-", "_").toUpperCase();
    }

    /** 校验是否为 CRYPTO 市场并返回规范符号。 */
    public static String resolveCrypto(String symbol) {
        String resolved = StockCodeUtil.resolve(symbol);
        if (!"CRYPTO".equals(StockCodeUtil.marketOf(resolved))) {
            throw new IllegalArgumentException("不是加密标的：" + symbol + "（需带 .CRYPTO 后缀）");
        }
        return resolved;
    }

    private static String[] parts(String symbol) {
        String pair = pair(symbol);
        int dash = pair.indexOf('-');
        if (dash <= 0 || dash != pair.lastIndexOf('-')) {
            throw new IllegalArgumentException("非法加密符号：" + symbol + "（应为 BASE-QUOTE.CRYPTO）");
        }
        return new String[]{pair.substring(0, dash), pair.substring(dash + 1)};
    }
}
