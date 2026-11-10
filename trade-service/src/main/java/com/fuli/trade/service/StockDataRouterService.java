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

    // 主数据源：当前仅实现 EastMoney 一个数据源（分钟/周/月线）。
    // 原设计「主源 Tushare 失败切 EastMoney」中的 Tushare 数据源尚未实现，
    // findSource("Tushare") 恒为 null 是死分支，因此主源直接指向 EastMoney。
    // 待后续接入 Tushare 分钟/周/月线源时，可把 PRIMARY_SOURCE 改回 "Tushare"。
    private static final String PRIMARY_SOURCE = "EastMoney";
    private static final String SECONDARY_SOURCE = "EastMoney";

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

    public List<KlineBarDTO> getMinuteData(String stockCode, int period) {
        String key = stockCode + ':' + period;
        long now = System.currentTimeMillis();
        MinuteCacheEntry cached = minuteCache.get(key);

        if (cached != null && cached.expireAt() > now) {
            return cached.bars();
        }

        List<KlineBarDTO> data = routeWithFallback(
                ds -> ds.getMinuteData(stockCode, period),
                String.format("分钟线 %s period=%d", stockCode, period)
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

    public List<KlineBarDTO> getWeeklyData(String stockCode) {
        return routeWithFallback(
                ds -> ds.getWeeklyData(stockCode),
                String.format("周线 %s", stockCode)
        );
    }

    public List<KlineBarDTO> getMonthlyData(String stockCode) {
        return routeWithFallback(
                ds -> ds.getMonthlyData(stockCode),
                String.format("月线 %s", stockCode)
        );
    }

    private List<KlineBarDTO> routeWithFallback(
            java.util.function.Function<StockDataSource, List<KlineBarDTO>> fetcher,
            String operation) {

        // 1. 尝试主源
        StockDataSource primary = findSource(PRIMARY_SOURCE);
        if (primary != null && primary.isAvailable()) {
            try {
                List<KlineBarDTO> data = fetcher.apply(primary);
                if (!data.isEmpty()) {
                    log.debug("[主源-{}] 获取 {} 条数据", primary.getName(), data.size());
                    return data;
                }
            } catch (Exception e) {
                log.warn("[主源-{}] {} 失败: {}", primary.getName(), operation, e.getMessage());
            }
        }

        // 2. 尝试备用源
        StockDataSource secondary = findSource(SECONDARY_SOURCE);
        if (secondary != null && secondary.isAvailable()) {
            try {
                List<KlineBarDTO> data = fetcher.apply(secondary);
                if (!data.isEmpty()) {
                    log.info("[备用源-{}] 获取 {} 条数据", secondary.getName(), data.size());
                    return data;
                }
            } catch (Exception e) {
                log.warn("[备用源-{}] {} 失败: {}", secondary.getName(), operation, e.getMessage());
            }
        }

        // 3. 所有源都失败
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
