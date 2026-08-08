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
     */
    @GetMapping("/data/stock/daily")
    List<Map<String, Object>> getDailyData(@RequestParam("stockCode") String stockCode,
                                           @RequestParam(value = "startDate", required = false) String startDate,
                                           @RequestParam(value = "endDate", required = false) String endDate);

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
