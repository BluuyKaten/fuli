package com.fuli.trade.controller;

import com.fuli.common.api.Result;
import com.fuli.trade.entity.StockDailyData;
import com.fuli.trade.entity.StockInfo;
import com.fuli.trade.service.StockService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
    public Result<StockInfo> info(@RequestParam String stockCode) {
        return Result.success(stockService.getStockInfo(stockCode));
    }

    @GetMapping("/daily")
    public Result<List<StockDailyData>> daily(@RequestParam String stockCode,
                                              @RequestParam(required = false) String startDate,
                                              @RequestParam(required = false) String endDate) {
        return Result.success(stockService.getDailyData(stockCode, startDate, endDate));
    }
}
