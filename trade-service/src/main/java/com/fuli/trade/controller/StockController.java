package com.fuli.trade.controller;

import com.fuli.common.api.Result;
import com.fuli.trade.entity.StockDailyData;
import com.fuli.trade.entity.StockInfo;
import com.fuli.trade.service.StockService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/stock")
public class StockController {

    private final StockService stockService;

    public StockController(StockService stockService) {
        this.stockService = stockService;
    }

    @GetMapping("/search")
    public Result<List<StockInfo>> search(@RequestParam String keyword) {
        return Result.success(stockService.searchStocks(keyword));
    }

    @GetMapping("/info")
    public Result<Map<String, Object>> info(@RequestParam String stockCode) {
        StockInfo stockInfo = stockService.getStockInfo(stockCode);
        if (stockInfo == null) {
            return Result.error("股票不存在");
        }
        Map<String, Object> map = new HashMap<>();
        map.put("stockCode", stockInfo.getStockCode());
        map.put("stockName", stockInfo.getStockName());
        map.put("area", stockInfo.getArea());
        map.put("industry", stockInfo.getIndustry());
        map.put("market", stockInfo.getMarket());
        return Result.success(map);
    }

    @GetMapping("/daily")
    public Result<List<Map<String, Object>>> daily(@RequestParam String stockCode,
                                                    @RequestParam(required = false) String startDate,
                                                    @RequestParam(required = false) String endDate) {
        List<StockDailyData> dataList = stockService.getDailyData(stockCode, startDate, endDate);
        List<Map<String, Object>> result = dataList.stream().map(data -> {
            Map<String, Object> map = new HashMap<>();
            map.put("stockCode", data.getStockCode());
            map.put("tradeDate", data.getTradeDate());
            map.put("openPrice", data.getOpenPrice());
            map.put("highPrice", data.getHighPrice());
            map.put("lowPrice", data.getLowPrice());
            map.put("closePrice", data.getClosePrice());
            map.put("preClose", data.getPreClose());
            map.put("vol", data.getVol());
            map.put("amount", data.getAmount());
            return map;
        }).collect(java.util.stream.Collectors.toList());
        return Result.success(result);
    }

    /**
     * 获取股票最新行情（供 analysis-service Feign 调用）
     */
    @GetMapping("/latest-price")
    public Result<Map<String, Object>> latestPrice(@RequestParam String stockCode) {
        StockDailyData latest = stockService.getLatestPrice(stockCode);
        if (latest == null) {
            return Result.error("无行情数据");
        }
        Map<String, Object> map = new HashMap<>();
        map.put("stockCode", latest.getStockCode());
        map.put("tradeDate", latest.getTradeDate());
        map.put("closePrice", latest.getClosePrice());
        map.put("preClose", latest.getPreClose());
        return Result.success(map);
    }

    /**
     * 查询当前用户对某只股票的持仓数量（供 K 线图卖出时显示）
     */
    @GetMapping("/holding")
    public Result<Map<String, Object>> getHolding(@RequestParam Long userId, @RequestParam String stockCode) {
        Map<String, Object> map = new HashMap<>();
        int quantity = stockService.getHoldingQuantity(userId, stockCode);
        map.put("userId", userId);
        map.put("stockCode", stockCode);
        map.put("holdingQuantity", quantity);
        return Result.success(map);
    }

    /**
     * 查询当前用户对某只股票的可卖数量（考虑A股T+1规则）
     * 返回：总持仓、可卖数量、冻结数量、市场类型、交易规则
     */
    @GetMapping("/available-quantity")
    public Result<Map<String, Object>> getAvailableQuantity(@RequestParam Long userId, @RequestParam String stockCode) {
        Map<String, Object> map = stockService.getAvailableQuantity(userId, stockCode);
        return Result.success(map);
    }

    /**
     * 修复持仓数据（根据交易记录重新计算）
     * 用于修复历史数据不一致的问题
     */
    @GetMapping("/fix-holding")
    public Result<Map<String, Object>> fixHolding(@RequestParam Long userId) {
        Map<String, Object> result = stockService.fixHoldingData(userId);
        return Result.success(result);
    }
}
