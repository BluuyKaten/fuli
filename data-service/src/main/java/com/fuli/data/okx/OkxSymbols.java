package com.fuli.data.okx;

/**
 * OKX {@code instId} ↔ 我们的标的符号。
 *
 * <p>{@code BTC-USDT} → {@code BTC-USDT.CRYPTO}：原样保留 instId，只加市场后缀。
 * ADR-0006 的示例曾写成去横线的 {@code BTCUSDT.CRYPTO}，ADR-0008 取代了该示例——
 * 去横线后映射不可逆（{@code 1INCH-USDT}、{@code USDT-USDC} 都会歧义），而符号是长期主键。
 *
 * <p><b>两端都接受裸 instId 和完整符号</b>：配置里写的是 {@code BTC-USDT}（OKX 原生 ID），
 * 接口调用方通常传 {@code BTC-USDT.CRYPTO}（我们的符号）。早期版本只认后者，
 * 导致定时任务里 3 个标的全部被拒、只留 WARN，数据一根没写。
 */
public final class OkxSymbols {

    public static final String MARKET = "CRYPTO";

    private static final String SUFFIX = "." + MARKET;

    private OkxSymbols() {
    }

    /** {@code BTC-USDT} → {@code BTC-USDT.CRYPTO}；传入已带后缀的原样返回。 */
    public static String toSymbol(String instId) {
        String id = requireNonBlank(instId);
        if (id.endsWith(SUFFIX)) {
            return id;
        }
        if (id.contains(".")) {
            throw new IllegalArgumentException("不是 OKX instId：" + id + "（市场后缀应为 " + SUFFIX + "）");
        }
        return id + SUFFIX;
    }

    /**
     * {@code BTC-USDT.CRYPTO} 或裸 {@code BTC-USDT} → {@code BTC-USDT}。
     *
     * <p>刻意不走 {@code StockCodeUtil.resolve}：它对「裸 6 位数字」会按 A 股推断市场，
     * 对带横线的 instId 则直接判非法，两边都不是 OKX 想要的语义。
     *
     * @throws IllegalArgumentException 带的是别家市场后缀（防止把 A 股符号误发给 OKX）
     */
    public static String toInstId(String symbol) {
        String s = requireNonBlank(symbol);
        if (s.endsWith(SUFFIX)) {
            return s.substring(0, s.length() - SUFFIX.length());
        }
        if (s.contains(".")) {
            throw new IllegalArgumentException("不是加密标的：" + s + "（市场应为 " + MARKET + "）");
        }
        // OKX 现货/合约 instId 一定是 BASE-QUOTE 形式；纯 6 位数字是 A 股代码，别放过去
        if (!s.contains("-")) {
            throw new IllegalArgumentException("不是 OKX instId：" + s + "（应形如 BTC-USDT）");
        }
        return s;
    }

    private static String requireNonBlank(String s) {
        if (s == null || s.trim().isEmpty()) {
            throw new IllegalArgumentException("instId / 符号不能为空");
        }
        return s.trim();
    }
}
