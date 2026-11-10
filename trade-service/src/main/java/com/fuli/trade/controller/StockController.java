package com.fuli.trade.controller;

import com.fuli.common.api.Result;
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
    private final jakarta.servlet.http.HttpServletRequest request;

    public StockController(StockService stockService, jakarta.servlet.http.HttpServletRequest request) {
        this.stockService = stockService;
        this.request = request;
    }

    @GetMapping("/search")
    public Result<List<Map<String, Object>>> search(@RequestParam String keyword) {
        return Result.success(stockService.searchStocks(keyword));
    }

    @GetMapping("/info")
    public Result<Map<String, Object>> info(@RequestParam String stockCode) {
        Map<String, Object> stockInfo = stockService.getStockInfo(stockCode);
        if (stockInfo == null || stockInfo.isEmpty()) {
            return Result.error("股票不存在");
        }
        return Result.success(stockInfo);
    }

    @GetMapping("/daily")
    public Result<List<Map<String, Object>>> daily(@RequestParam String stockCode,
                                                    @RequestParam(required = false) String startDate,
                                                    @RequestParam(required = false) String endDate,
                                                    @RequestParam(required = false) Integer limit) {
        List<Map<String, Object>> dataList = stockService.getDailyData(stockCode, startDate, endDate, limit);
        return Result.success(dataList);
    }

    /** 全历史收盘价缩略序列，供 K 线底部全貌条使用 */
    @GetMapping("/daily/sparkline")
    public Result<List<Map<String, Object>>> dailySparkline(@RequestParam String stockCode,
                                                            @RequestParam(defaultValue = "240") int points) {
        return Result.success(stockService.getDailySparkline(stockCode, points));
    }

    /**
     * 获取股票最新行情（供 analysis-service Feign 调用）
     */
    @GetMapping("/latest-price")
    public Result<Map<String, Object>> latestPrice(@RequestParam String stockCode) {
        Map<String, Object> latest = stockService.getLatestPrice(stockCode);
        if (latest == null || latest.isEmpty()) {
            return Result.error("无行情数据");
        }
        return Result.success(latest);
    }

    /**
     * 批量获取股票最新行情（一次 Feign 批量调用，消除 N+1 查询）
     */
    @GetMapping("/latest-prices")
    public Result<Map<String, Map<String, Object>>> latestPrices(@RequestParam String stockCodes) {
        return Result.success(stockService.getLatestPrices(stockCodes));
    }

    /**
     * 批量获取股票基础信息（一次 Feign 批量调用，消除 N+1 查询）
     */
    @GetMapping("/infos")
    public Result<Map<String, Map<String, Object>>> infos(@RequestParam String stockCodes) {
        return Result.success(stockService.getStockInfos(stockCodes));
    }

    /**
     * 查询当前用户对某只股票的持仓数量（供 K 线图卖出时显示）
     */
    @GetMapping("/holding")
    public Result<Map<String, Object>> getHolding(@RequestParam(required = false) Long userId, @RequestParam String stockCode) {
        userId = com.fuli.common.api.security.AuthenticatedUser.requireId(request);
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
    public Result<Map<String, Object>> getAvailableQuantity(@RequestParam(required = false) Long userId, @RequestParam String stockCode) {
        userId = com.fuli.common.api.security.AuthenticatedUser.requireId(request);
        Map<String, Object> map = stockService.getAvailableQuantity(userId, stockCode);
        return Result.success(map);
    }

    /**
     * 修复持仓数据（根据交易记录重新计算）
     * 用于修复历史数据不一致的问题
     */
    @GetMapping("/fix-holding")
    public Result<Map<String, Object>> fixHolding(@RequestParam(required = false) Long userId) {
        userId = com.fuli.common.api.security.AuthenticatedUser.requireId(request);
        Map<String, Object> result = stockService.fixHoldingData(userId);
        return Result.success(result);
    }
}

