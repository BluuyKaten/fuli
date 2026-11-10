package com.fuli.common.api.util;

import java.util.Locale;
import java.util.Set;

/**
 * 标的符号的唯一解析器。
 *
 * <p>标的身份是「&lt;代码&gt;.&lt;市场&gt;」两段式，market ∈ {SH, SZ, BJ, US, CRYPTO}，
 * 见 docs/adr/0006-instrument-identity-and-symbol-scheme.md。后缀的语义是「命名空间」而不是
 * 「交易所」：{@code SH} 是交易所，{@code US} 是国家市场，{@code CRYPTO} 是资产类别。
 *
 * <h3>为什么只留这一份实现</h3>
 * 旧代码有四份各自为政的后缀推导（本类 {@code withSuffix}、前端 {@code src/utils/stockCode.ts}、
 * {@code KlineController#getSuffix}、{@code EastMoneyDataSource} 的首位判断），覆盖范围互不相同，
 * 920xxx、900xxx 在不同路径上会推到不同的市场。只要留两份就会出现两套真相，因此全部收敛到这里。
 *
 * <h3>裸代码只兼容 A 股</h3>
 * {@code AAPL}、{@code BTCUSDT} 无法从字面判断市场，一律拒绝并要求带后缀。
 * 服务端不做代码猜测——猜错的表现是「查不到数据」而不是报错，最难排查。
 */
public final class StockCodeUtil {

    /** 合法市场后缀。 */
    private static final Set<String> MARKETS = Set.of("SH", "SZ", "BJ", "US", "CRYPTO");

    /** 代码段最大长度：{@code BTCUSDT} 为 7 位，留余量到 15。 */
    private static final int MAX_CODE_LENGTH = 15;

    private StockCodeUtil() {}

    /**
     * 解析为规范符号：完整符号原样返回（大写规范化），裸 6 位按 A 股规则补后缀。
     *
     * @throws IllegalArgumentException 输入为空、市场后缀非法、或裸代码不是 6 位数字
     */
    public static String resolve(String input) {
        String symbol = resolveOrNull(input);
        if (symbol == null) {
            throw new IllegalArgumentException(
                    "非法的标的符号：" + input + "；美股/加密必须带市场后缀（如 AAPL.US、BTCUSDT.CRYPTO）");
        }
        return symbol;
    }

    /**
     * 宽松版 {@link #resolve}：无法解析时返回 {@code null} 而不抛异常。
     *
     * <p>批量端点（{@code /latest-prices}、{@code /infos}）用这一版：调用方一次传几十个代码，
     * 单个脏数据不应该让整批请求 400。
     */
    public static String resolveOrNull(String input) {
        if (input == null) {
            return null;
        }
        String s = input.trim().toUpperCase(Locale.ROOT);
        if (s.isEmpty()) {
            return null;
        }
        if (s.indexOf('.') >= 0) {
            return isQualified(s) ? s : null;
        }
        String market = inferAShareMarket(s);
        return market == null ? null : s + "." + market;
    }

    /**
     * 去掉后缀，仅做字符串截取，不做任何推断。
     *
     * <p>{@code 600519.SH → 600519}、{@code AAPL.US → AAPL}、{@code 600519 → 600519}。
     * 东方财富的 {@code secid}、前端展示用这个；它不负责判断市场。
     */
    public static String pureCode(String symbol) {
        if (symbol == null) {
            return "";
        }
        String s = symbol.trim();
        int dot = s.indexOf('.');
        return dot < 0 ? s : s.substring(0, dot);
    }

    /**
     * 取市场后缀（SH/SZ/BJ/US/CRYPTO）。裸 6 位按 A 股规则推断后再取。
     *
     * @throws IllegalArgumentException 符号非法
     */
    public static String marketOf(String symbol) {
        String resolved = resolve(symbol);
        return resolved.substring(resolved.indexOf('.') + 1);
    }

    /** 是否为 A 股市场（含北交所）。符号侧的等价判断；以 {@code instrument.market} 为准则。 */
    public static boolean isAShareMarket(String market) {
        return "SH".equals(market) || "SZ".equals(market) || "BJ".equals(market);
    }

    /** 完整符号校验：正好两段、市场合法、代码段非空且不太长。 */
    private static boolean isQualified(String s) {
        int dot = s.indexOf('.');
        if (dot <= 0 || dot != s.lastIndexOf('.')) {
            return false;
        }
        String code = s.substring(0, dot);
        String market = s.substring(dot + 1);
        return !code.isEmpty()
                && code.length() <= MAX_CODE_LENGTH
                && MARKETS.contains(market);
    }

    /**
     * 裸 6 位代码推断 A 股市场，规则与旧实现保持一致（不做「改进」，避免存量符号漂移）。
     *
     * <ul>
     *   <li>北交所：920 / 43 / 83 / 87 开头，以及 4 / 8 开头 → BJ</li>
     *   <li>沪市：60 / 68 / 900（沪 B）开头 → SH</li>
     *   <li>深市：00 / 30 / 20（深 B）开头 → SZ</li>
     * </ul>
     *
     * <p>北交所的判断必须在沪市之前：920xxx 与 900xxx 都以 9 开头。
     *
     * @return 市场后缀；不是 6 位数字或无法归类时返回 {@code null}
     */
    private static String inferAShareMarket(String s) {
        if (s.length() != 6 || !s.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return null;
        }
        if (s.startsWith("920") || s.startsWith("43") || s.startsWith("83") || s.startsWith("87")
                || s.charAt(0) == '4' || s.charAt(0) == '8') {
            return "BJ";
        }
        if (s.startsWith("60") || s.startsWith("68") || s.startsWith("900")) {
            return "SH";
        }
        if (s.startsWith("00") || s.startsWith("30") || s.startsWith("20")) {
            return "SZ";
        }
        return null;
    }
}
