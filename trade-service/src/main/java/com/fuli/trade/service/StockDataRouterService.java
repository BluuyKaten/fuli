package com.fuli.trade.service;

import com.fuli.trade.datasource.StockDataSource;
import com.fuli.trade.dto.KlineBarDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 多数据源路由：主源失败自动切换备用
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockDataRouterService {

    private final List<StockDataSource> dataSources;

    // 主数据源名称（可配置）
    private static final String PRIMARY_SOURCE = "Tushare";
    private static final String SECONDARY_SOURCE = "EastMoney";

    public List<KlineBarDTO> getMinuteData(String stockCode, int period) {
        return routeWithFallback(
                ds -> ds.getMinuteData(stockCode, period),
                String.format("分钟线 %s period=%d", stockCode, period)
        );
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
