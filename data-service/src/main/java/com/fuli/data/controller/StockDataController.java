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
 *
 * <p>注意：为避 Result 双重嵌套（data.data），直接返回数据本身，由调用方 Feign 客户端的原生返回类型接收。
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
    public List<Map<String, Object>> searchStocks(@RequestParam("keyword") String keyword) {
        QueryWrapper<StockInfo> wrapper = new QueryWrapper<>();
        wrapper.like("stock_code", keyword).or().like("stock_name", keyword);
        wrapper.orderByAsc("stock_code");
        wrapper.last("LIMIT 20");
        List<StockInfo> stocks = stockInfoMapper.selectList(wrapper);
        return stocks.stream().map(this::toMap).collect(java.util.stream.Collectors.toList());
    }

    /**
     * 获取股票日线行情。
     */
    @GetMapping("/daily")
    public List<Map<String, Object>> getDailyData(@RequestParam("stockCode") String stockCode,
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
        List<StockDailyData> dataList = stockDailyDataMapper.selectList(wrapper);
        return dataList.stream().map(this::toMap).collect(java.util.stream.Collectors.toList());
    }

    /**
     * 获取股票基础信息。
     */
    @GetMapping("/info")
    public Map<String, Object> getStockInfo(@RequestParam("stockCode") String stockCode) {
        StockInfo info = stockInfoMapper.selectById(stockCode);
        return info != null ? toMap(info) : new HashMap<>();
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
            result.put(code.trim(), getLatestPrice(code.trim()));
        }
        return result;
    }

    /**
     * 批量获取股票基础信息，消除 N+1 查询。
     */
    @GetMapping("/infos")
    public Map<String, Map<String, Object>> getStockInfos(@RequestParam("stockCodes") String stockCodes) {
        String[] codes = stockCodes.split(",");
        Map<String, Map<String, Object>> result = new HashMap<>();
        for (String code : codes) {
            StockInfo info = stockInfoMapper.selectById(code.trim());
            if (info != null) {
                result.put(code.trim(), toMap(info));
            }
        }
        return result;
    }

    private Map<String, Object> toMap(StockInfo info) {
        Map<String, Object> map = new HashMap<>();
        map.put("stockCode", info.getStockCode());
        map.put("stockName", info.getStockName());
        map.put("area", info.getArea());
        map.put("industry", info.getIndustry());
        map.put("market", info.getMarket());
        map.put("listDate", info.getListDate());
        map.put("status", info.getStatus());
        return map;
    }

    private Map<String, Object> toMap(StockDailyData data) {
        Map<String, Object> map = new HashMap<>();
        map.put("stockCode", data.getStockCode());
        map.put("tradeDate", data.getTradeDate());
        map.put("openPrice", data.getOpenPrice());
        map.put("highPrice", data.getHighPrice());
        map.put("lowPrice", data.getLowPrice());
        map.put("closePrice", data.getClosePrice());
        map.put("preClose", data.getPreClose());
        map.put("changeAmount", data.getChangeAmount());
        map.put("pctChg", data.getPctChg());
        map.put("vol", data.getVol());
        map.put("amount", data.getAmount());
        return map;
    }
}
