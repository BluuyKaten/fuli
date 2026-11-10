package com.fuli.common.api.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * data-service 行情数据 Feign 客户端。
 *
 * <p>trade-service / analysis-service 通过此接口访问行情数据，
 * 不再直接访问 data-service 的数据库表，消除服务边界耦合（C3）。
 *
 * <p>注意：data-service 直接返回数据本身（非 Result 包装），避免 data.data 双重嵌套。
 */
@FeignClient(name = "data-service", contextId = "dataFeignClient")
public interface DataFeignClient {

    /**
     * 搜索股票（按代码或名称模糊匹配）。
     */
    @GetMapping("/data/stock/search")
    List<Map<String, Object>> searchStocks(@RequestParam("keyword") String keyword);

    /**
     * 获取股票日线行情。
     *
     * <p>区间与 limit 的语义：
     * <ul>
     *   <li>传了 startDate 或 endDate：按区间返回，{@code limit} 不生效（练习模块依赖完整区间做连续性校验）</li>
     *   <li>都没传：返回最近 {@code limit} 根，{@code limit} 为空时退回 620 根（500 视窗 + 120 指标前置）</li>
     * </ul>
     * 见 docs/adr/0002-kline-windowed-loading.md。
     */
    @GetMapping("/data/stock/daily")
    List<Map<String, Object>> getDailyData(@RequestParam("stockCode") String stockCode,
                                           @RequestParam(value = "startDate", required = false) String startDate,
                                           @RequestParam(value = "endDate", required = false) String endDate,
                                           @RequestParam(value = "limit", required = false) Integer limit);

    /**
     * 全历史收盘价缩略序列（服务端已 LTTB 降采样）。供 K 线图底部全貌条使用。
     */
    @GetMapping("/data/stock/daily/sparkline")
    List<Map<String, Object>> getDailySparkline(@RequestParam("stockCode") String stockCode,
                                                @RequestParam(value = "points", defaultValue = "240") int points);

    /**
     * 获取股票基础信息。
     */
    @GetMapping("/data/stock/info")
    Map<String, Object> getStockInfo(@RequestParam("stockCode") String stockCode);

    /**
     * 获取股票最新行情（含收盘价、前收盘价）。
     */
    @GetMapping("/data/stock/latest-price")
    Map<String, Object> getLatestPrice(@RequestParam("stockCode") String stockCode);

    /**
     * 批量获取股票最新行情，消除 N+1 查询。
     */
    @GetMapping("/data/stock/latest-prices")
    Map<String, Map<String, Object>> getLatestPrices(@RequestParam("stockCodes") String stockCodes);

    /**
     * 批量获取股票基础信息，消除 N+1 查询。
     */
    @GetMapping("/data/stock/infos")
    Map<String, Map<String, Object>> getStockInfos(@RequestParam("stockCodes") String stockCodes);
}
