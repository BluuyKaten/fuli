package com.fuli.data.service;

import com.fuli.data.cache.DailyKlineCache;
import com.fuli.data.entity.Bar;
import com.fuli.data.repository.KlineQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 行情查询的门面：负责参数规整与缓存装饰，DB 访问全部下沉到 {@link KlineQueryRepository}。
 */
@Service
@RequiredArgsConstructor
public class KlineQueryService {

    /** 首屏窗口：500 根视窗 + 120 根指标前置。见 docs/adr/0002-kline-windowed-loading.md */
    public static final int DEFAULT_LIMIT = 620;

    /** 上限保护，避免调用方传一个巨大 limit 把整表拖出来 */
    public static final int MAX_LIMIT = 5000;

    /** 不复权。旧 Feign 端点只提供原始价，adjust 固定为 none。 */
    public static final String ADJUST_NONE = "none";

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final KlineQueryRepository repository;

    private final DailyKlineCache cache;

    /**
     * 无区间与有区间走不同策略：
     * <ul>
     *   <li>无区间 → 最近 N 根，走缓存（双窗口请求几乎都命中）</li>
     *   <li>有区间 → 直接查库。练习模块要的是任意时间段的完整区间，
     *       与「最近 800 根」的缓存内容对不上，硬塞进缓存只会污染它</li>
     * </ul>
     *
     * @param fromTs 闭区间下界（含），null 表示开放
     * @param toTs   开区间上界（不含），null 表示开放
     */
    public List<Bar> query(String symbol, String period, Long fromTs, Long toTs, Integer limit) {
        if (fromTs == null && toTs == null) {
            int want = clamp(limit == null ? DEFAULT_LIMIT : limit);
            return cache.getRecent(symbol, period, ADJUST_NONE, want,
                    () -> repository.selectRecent(symbol, period, DailyKlineCache.CACHE_SIZE));
        }
        return repository.selectRange(symbol, period, fromTs, toTs, limit == null ? null : clamp(limit));
    }

    private static int clamp(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /**
     * 把 {@code yyyyMMdd} 自然日按 Asia/Shanghai 解释成 UTC 秒闭开区间下界。
     *
     * <p>日线 bar 的 ts 是交易日 01:30 UTC（= 09:30 CST），落在
     * {@code [当日 00:00 CST, 次日 00:00 CST)} 内，因此自然日区间能精确对齐。
     */
    public static Long dayStartTs(String yyyyMMdd) {
        if (yyyyMMdd == null || yyyyMMdd.isBlank()) {
            return null;
        }
        return LocalDate.parse(yyyyMMdd.replace("-", ""), BASIC_DATE)
                .atStartOfDay(SHANGHAI).toEpochSecond();
    }

    /** 同上，用于「结束日期」——返回次日 00:00 CST 的开区间上界。 */
    public static Long dayEndExclusiveTs(String yyyyMMdd) {
        if (yyyyMMdd == null || yyyyMMdd.isBlank()) {
            return null;
        }
        return LocalDate.parse(yyyyMMdd.replace("-", ""), BASIC_DATE)
                .plusDays(1).atStartOfDay(SHANGHAI).toEpochSecond();
    }

    /** bar 的开盘时刻还原成北京时间的交易日 {@code yyyyMMdd} 整数（旧契约 {@code tradeDate}）。 */
    public static Integer tradeDateOf(Long ts) {
        if (ts == null) {
            return null;
        }
        return Integer.valueOf(LocalDate.ofInstant(Instant.ofEpochSecond(ts), SHANGHAI).format(BASIC_DATE));
    }

    /** 日线类周期用日期字符串展示，分钟类用 epoch 秒（ADR-0007）。 */
    public static boolean isDailyOrAbove(String period) {
        return "1d".equals(period) || "1w".equals(period) || "1mo".equals(period);
    }

    /**
     * 全历史收盘价缩略序列，用于 K 线图底部的全貌条。
     *
     * <p>用 LTTB（Largest-Triangle-Three-Buckets）而不是等距采样：等距采样会抹掉尖峰和深坑，
     * 「这只股票三年前腰斩过」这类形态在缩略图上就看不见了。LTTB 按三角形面积选点，保形性好得多。
     *
     * @param points 目标点数，建议 200–300（对应缩略条像素宽度）
     */
    public List<Map<String, Object>> sparkline(String symbol, int points) {
        List<Map<String, Object>> rows =
                repository.selectCloseSeries(symbol, KlineQueryRepository.PERIOD_DAILY);
        if (rows.isEmpty()) {
            return List.of();
        }

        List<Integer> dates = new ArrayList<>(rows.size());
        List<double[]> pts = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Object d = row.get("d");
            Object c = row.get("c");
            if (!(d instanceof Number) || !(c instanceof Number)) {
                continue;
            }
            Integer tradeDate = tradeDateOf(((Number) d).longValue());
            if (tradeDate == null) {
                continue;
            }
            // x 用去空后的顺序索引：交易日天然等距，用真实日期当 x 只会引入周末跳变
            pts.add(new double[]{pts.size(), ((Number) c).doubleValue()});
            dates.add(tradeDate);
        }
        if (pts.isEmpty()) {
            return List.of();
        }

        List<double[]> sampled = lttb(pts, points);
        List<Map<String, Object>> result = new ArrayList<>(sampled.size());
        for (double[] p : sampled) {
            int idx = (int) p[0];
            if (idx < 0 || idx >= dates.size()) {
                continue;
            }
            Map<String, Object> item = new HashMap<>(4);
            item.put("d", dates.get(idx));
            item.put("c", p[1]);
            result.add(item);
        }
        return result;
    }

    private static List<double[]> lttb(List<double[]> data, int threshold) {
        int n = data.size();
        if (threshold >= n || threshold < 3) {
            return data;
        }
        List<double[]> out = new ArrayList<>(threshold);
        double bucketSize = (double) (n - 2) / (threshold - 2);

        out.add(data.get(0));
        int a = 0;
        for (int i = 0; i < threshold - 2; i++) {
            // 下一个桶的平均点，作为三角形的第三个顶点
            int avgStart = (int) Math.floor((i + 1) * bucketSize) + 1;
            int avgEnd = (int) Math.min(Math.floor((i + 2) * bucketSize) + 1, n);
            if (avgStart >= avgEnd) {
                avgStart = Math.min(avgStart, n - 1);
                avgEnd = avgStart + 1;
            }
            double avgX = 0;
            double avgY = 0;
            for (int j = avgStart; j < avgEnd; j++) {
                avgX += data.get(j)[0];
                avgY += data.get(j)[1];
            }
            int len = avgEnd - avgStart;
            avgX /= len;
            avgY /= len;

            int rangeStart = (int) Math.floor(i * bucketSize) + 1;
            int rangeEnd = (int) Math.floor((i + 1) * bucketSize) + 1;
            double ax = data.get(a)[0];
            double ay = data.get(a)[1];
            double maxArea = -1;
            int next = rangeStart;
            for (int j = rangeStart; j < rangeEnd && j < n; j++) {
                double area = Math.abs((ax - avgX) * (data.get(j)[1] - ay)
                        - (ax - data.get(j)[0]) * (avgY - ay));
                if (area > maxArea) {
                    maxArea = area;
                    next = j;
                }
            }
            out.add(data.get(next));
            a = next;
        }
        out.add(data.get(n - 1));
        return out;
    }
}
