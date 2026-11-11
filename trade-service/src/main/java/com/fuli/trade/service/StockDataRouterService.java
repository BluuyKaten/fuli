package com.fuli.trade.service;

import com.fuli.trade.datasource.StockDataSource;
import com.fuli.trade.dto.KlineBarDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多数据源路由：主源失败自动切换备用
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockDataRouterService {

    private final List<StockDataSource> dataSources;

    // 数据源优先级按市场区分：
    // - 美股：腾讯优先。东财 K 线主机在部分网络直接断连（ADR-0019），先试它等于每次看图都先等一次超时。
    // - A 股：仍只走东财。腾讯 A 股的复权口径没在这里验证过，接管会让老用户的图静默变样。
    // 原设计里的 Tushare 源尚未实现，所以不再保留 PRIMARY/SECONDARY 两个常量。
    private static final String TENCENT_SOURCE = "Tencent";
    private static final String EASTMONEY_SOURCE = "EastMoney";

    private List<String> sourceOrder(String symbol) {
        return "US".equals(com.fuli.common.api.util.StockCodeUtil.marketOf(symbol))
                ? List.of(TENCENT_SOURCE, EASTMONEY_SOURCE)
                : List.of(EASTMONEY_SOURCE);
    }

    /**
     * 分钟线缓存 TTL。
     *
     * <p>1 分钟线每 60 秒生成一根，10 秒是最小生成周期的 1/6，最坏情况下用户看到的是
     * 「上一根刚走完的分钟」而不是几分钟前的数据。**绝不能超过 30 秒（半根 K 线）**——
     * 超过就会看到当前分钟还没走完就定格的错误形态，这是正确性约束不是性能参数。
     *
     * <p>用进程内 ConcurrentHashMap 而非 Redis：缓存内容只有 240 根、22 KB，
     * 且 TTL 只有 10 秒，跨实例共享的收益抵不上一次网络往返。Redis 留给日线（ADR-0004）。
     */
    private static final long MINUTE_TTL_MS = 10_000;

    /** 缓存条目上限，防止股票代码不断变化导致 map 无限增长 */
    private static final int MINUTE_CACHE_MAX = 500;

    private record MinuteCacheEntry(List<KlineBarDTO> bars, long expireAt, long createdAt) {}

    private final Map<String, MinuteCacheEntry> minuteCache = new ConcurrentHashMap<>();

    public List<KlineBarDTO> getMinuteData(String symbol, int period) {
        String key = symbol + ':' + period;
        long now = System.currentTimeMillis();
        MinuteCacheEntry cached = minuteCache.get(key);

        if (cached != null && cached.expireAt() > now) {
            return cached.bars();
        }

        List<KlineBarDTO> data = routeWithFallback(symbol,
                ds -> ds.getMinuteData(symbol, period),
                String.format("分钟线 %s period=%d", symbol, period)
        );

        if (data.isEmpty()) {
            // 上游失败：有旧数据就返回旧的，比让用户面对空白图强。
            // 刻意不刷新 expireAt —— 否则上游持续失败时，过期数据会被不断"续命"成永久缓存。
            if (cached != null) {
                log.warn("[分钟线] 上游返回空，回退到 {} ms 前的缓存: {}",
                        now - cached.createdAt(), key);
                return cached.bars();
            }
            return data;
        }

        if (minuteCache.size() >= MINUTE_CACHE_MAX) {
            minuteCache.entrySet().removeIf(e -> e.getValue().expireAt() <= now);
        }
        List<KlineBarDTO> immutable = List.copyOf(data);
        minuteCache.put(key, new MinuteCacheEntry(immutable, now + MINUTE_TTL_MS, now));
        return immutable;
    }

    public List<KlineBarDTO> getWeeklyData(String symbol) {
        return routeWithFallback(symbol,
                ds -> ds.getWeeklyData(symbol),
                String.format("周线 %s", symbol)
        );
    }

    public List<KlineBarDTO> getMonthlyData(String symbol) {
        return routeWithFallback(symbol,
                ds -> ds.getMonthlyData(symbol),
                String.format("月线 %s", symbol)
        );
    }

    private List<KlineBarDTO> routeWithFallback(String symbol,
                                                java.util.function.Function<StockDataSource, List<KlineBarDTO>> fetcher,
                                                String operation) {

        List<String> order = sourceOrder(symbol);
        for (int i = 0; i < order.size(); i++) {
            StockDataSource source = findSource(order.get(i));
            if (source == null || !source.isAvailable()) {
                continue;
            }
            try {
                List<KlineBarDTO> data = fetcher.apply(source);
                if (!data.isEmpty()) {
                    if (i > 0) {
                        // 备用源命中要留痕：主源已经不可用一段时间了，日志里得能看出来。
                        log.info("[备用源-{}] {} 取到 {} 条", source.getName(), operation, data.size());
                    }
                    return data;
                }
            } catch (Exception e) {
                log.warn("[{}-{}] {} 失败: {}", i == 0 ? "主源" : "备用源",
                        source.getName(), operation, e.getMessage());
            }
        }

        log.error("[路由] {} 所有数据源均失败", operation);
        return new java.util.ArrayList<>();
    }

    private StockDataSource findSource(String name) {
        return dataSources.stream()
                .filter(ds -> ds.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }
}
