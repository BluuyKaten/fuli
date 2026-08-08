package com.fuli.data.controller;

import com.fuli.data.entity.StockDailyData;
import com.fuli.data.entity.StockInfo;
import com.fuli.data.mapper.StockDailyDataMapper;
import com.fuli.data.mapper.StockInfoMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 行情数据控制器（data-service 对外 Feign 接口）。
 *
 * <p>trade-service / analysis-service 通过 {@code DataFeignClient} 调用此控制器获取行情数据，
 * 不再直接访问 data-service 的数据库表，消除服务边界耦合。
 */
@Slf4j
@RestController
@RequestMapping("/data/stock")
public class StockDataController {

    private final StockInfoMapper stockInfoMapper;
    private final StockDailyDataMapper stockDailyDataMapper;

    public StockDataController(StockInfoMapper stockInfoMapper, StockDailyDataMapper stockDailyDataMapper) {
        this.stockInfoMapper = stockInfoMapper;
        this.stockDailyDataMapper = stockDailyDataMapper;
    }

    /**
     * 搜索股票（按代码或名称模糊匹配）。
     */
    @GetMapping("/search")
    public List<StockInfo> searchStocks(@RequestParam("keyword") String keyword) {
        QueryWrapper<StockInfo> wrapper = new QueryWrapper<>();
        wrapper.like("stock_code", keyword).or().like("stock_name", keyword);
        wrapper.orderByAsc("stock_code");
        wrapper.last("LIMIT 20");
        return stockInfoMapper.selectList(wrapper);
    }

    /**
     * 获取股票日线行情。
     */
    @GetMapping("/daily")
    public List<StockDailyData> getDailyData(@RequestParam("stockCode") String stockCode,
                                             @RequestParam(value = "startDate", required = false) String startDate,
                                             @RequestParam(value = "endDate", required = false) String endDate) {
        LambdaQueryWrapper<StockDailyData> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StockDailyData::getStockCode, stockCode);
        if (startDate != null && !startDate.isEmpty()) {
            wrapper.ge(StockDailyData::getTradeDate, startDate);
        }
        if (endDate != null && !endDate.isEmpty()) {
            wrapper.le(StockDailyData::getTradeDate, endDate);
        }
        wrapper.orderByAsc(StockDailyData::getTradeDate);
        return stockDailyDataMapper.selectList(wrapper);
    }

    /**
     * 获取股票基础信息。
     */
    @GetMapping("/info")
    public StockInfo getStockInfo(@RequestParam("stockCode") String stockCode) {
        return stockInfoMapper.selectById(stockCode);
    }

    /**
     * 获取股票最新行情（含收盘价、前收盘价）。
     */
    @GetMapping("/latest-price")
    public Map<String, Object> getLatestPrice(@RequestParam("stockCode") String stockCode) {
        LambdaQueryWrapper<StockDailyData> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StockDailyData::getStockCode, stockCode)
                .orderByDesc(StockDailyData::getTradeDate)
                .last("LIMIT 1");
        StockDailyData latest = stockDailyDataMapper.selectOne(wrapper);

        Map<String, Object> result = new HashMap<>();
        if (latest != null) {
            result.put("stockCode", latest.getStockCode());
            result.put("closePrice", latest.getClosePrice());
            result.put("preClose", latest.getPreClose());
            result.put("tradeDate", latest.getTradeDate());
        }
        return result;
    }

    /**
     * 批量获取股票最新行情，消除 N+1 查询。
     */
    @GetMapping("/latest-prices")
    public Map<String, Map<String, Object>> getLatestPrices(@RequestParam("stockCodes") String stockCodes) {
        String[] codes = stockCodes.split(",");
        Map<String, Map<String, Object>> result = new HashMap<>();
        for (String code : codes) {
            result.put(code, getLatestPrice(code.trim()));
        }
        return result;
    }

    /**
     * 批量获取股票基础信息，消除 N+1 查询。
     */
    @GetMapping("/infos")
    public Map<String, StockInfo> getStockInfos(@RequestParam("stockCodes") String stockCodes) {
        String[] codes = stockCodes.split(",");
        Map<String, StockInfo> result = new HashMap<>();
        for (String code : codes) {
            StockInfo info = stockInfoMapper.selectById(code.trim());
            if (info != null) {
                result.put(code.trim(), info);
            }
        }
        return result;
    }
}
