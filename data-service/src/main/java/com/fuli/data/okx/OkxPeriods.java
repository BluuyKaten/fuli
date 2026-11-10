package com.fuli.data.okx;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 我们的 {@code bar.period} 与 OKX {@code bar} 参数的双向映射。
 *
 * <p>见 docs/adr/0008：只映射 {@code bar.chk_bar_period} 允许的 9 个取值。
 * OKX 还有 3m / 2H / 6H / 12H / 2D / 3D / 3M，不接——加一个周期要改表 CHECK + 前端枚举 + 缓存 key，
 * 属跨层改动，必须走 ADR，不能在这里悄悄放宽。
 *
 * <p>大小写是 OKX 的硬约定：分钟用 {@code m}（{@code 15m}），小时/天/周/月用大写
 * （{@code 1H} / {@code 1Dutc}）；写成 {@code 1h} 会被 OKX 判为非法 bar。
 */
public final class OkxPeriods {

    /** 我们的 period → OKX bar。日/周/月一律用 utc 口径，理由见 ADR-0008 决策 3。 */
    private static final Map<String, String> TO_OKX = new LinkedHashMap<>();

    private static final Map<String, String> FROM_OKX = new LinkedHashMap<>();

    static {
        map("1m", "1m");
        map("5m", "5m");
        map("15m", "15m");
        map("30m", "30m");
        map("1h", "1H");
        map("4h", "4H");
        map("1d", "1Dutc");
        map("1w", "1Wutc");
        map("1mo", "1Mutc");
    }

    private OkxPeriods() {
    }

    private static void map(String ours, String okx) {
        TO_OKX.put(ours, okx);
        FROM_OKX.put(okx, ours);
    }

    /**
     * 精确匹配，<b>不做大小写归一</b>。
     *
     * <p>这不是苛刻，是防一类静默事故：OKX 的 {@code 1M} 是月线、{@code 1m} 是分钟。
     * 若先把入参转小写，把 OKX 词汇 {@code 1M} 传进来会拿到「1 分钟」的数据——
     * 不报错、数据看着也像，但周期完全错了。所以宁可让它报「不支持的周期」。
     *
     * @throws IllegalArgumentException 周期不在受控集合内（由 controller 转 400）
     */
    public static String toOkxBar(String period) {
        String okx = period == null ? null : TO_OKX.get(period.trim());
        if (okx == null) {
            throw new IllegalArgumentException("不支持的周期：" + period
                    + "；OKX 接入仅支持 " + String.join("/", TO_OKX.keySet())
                    + "（注意大小写：分钟小写 m，小时/天/周/月按我们的词汇写 1h/1d/1w/1mo）");
        }
        return okx;
    }

    /** OKX bar → 我们的 period；不是受控集合内返回 null（用于解析回包时过滤）。 */
    public static String toPeriod(String okxBar) {
        return okxBar == null ? null : FROM_OKX.get(okxBar.trim());
    }

    /** 受支持周期（有序，便于打印与前端下拉）。 */
    public static List<String> supported() {
        return List.copyOf(TO_OKX.keySet());
    }
}
