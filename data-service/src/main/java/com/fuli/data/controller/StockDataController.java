package com.fuli.data.controller;

import com.fuli.data.entity.StockDailyData;
import com.fuli.data.entity.StockInfo;
import com.fuli.data.mapper.StockDailyDataMapper;
import com.fuli.data.mapper.StockInfoMapper;
import com.fuli.common.api.util.StockCodeUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
     *
     * <p>行情库统一使用带市场后缀的代码（600519.SH），调用方若只传 6 位裸代码，
     * 先用原值查一次以兼容历史数据，未命中再补后缀重查。
     */
    @GetMapping("/daily")
    public List<Map<String, Object>> getDailyData(@RequestParam("stockCode") String stockCode,
                                                  @RequestParam(value = "startDate", required = false) String startDate,
                                                  @RequestParam(value = "endDate", required = false) String endDate) {
        List<StockDailyData> dataList = queryDaily(stockCode, startDate, endDate);
        if (dataList.isEmpty()) {
            String suffixed = withSuffixIfBare(stockCode);
            if (!suffixed.equals(stockCode)) {
                dataList = queryDaily(suffixed, startDate, endDate);
            }
        }
        return dataList.stream().map(this::toMap).collect(java.util.stream.Collectors.toList());
    }

    private List<StockDailyData> queryDaily(String stockCode, String startDate, String endDate) {
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
    public Map<String, Object> getStockInfo(@RequestParam("stockCode") String stockCode) {
        StockInfo info = stockInfoMapper.selectById(stockCode);
        if (info == null) {
            String suffixed = withSuffixIfBare(stockCode);
            if (!suffixed.equals(stockCode)) {
                info = stockInfoMapper.selectById(suffixed);
            }
        }
        return info != null ? toMap(info) : new HashMap<>();
    }

    /**
     * 获取股票最新行情（含收盘价、前收盘价）。
     */
    @GetMapping("/latest-price")
    public Map<String, Object> getLatestPrice(@RequestParam("stockCode") String stockCode) {
        StockDailyData latest = queryLatest(stockCode);
        if (latest == null) {
            String suffixed = withSuffixIfBare(stockCode);
            if (!suffixed.equals(stockCode)) {
                latest = queryLatest(suffixed);
            }
        }

        Map<String, Object> result = new HashMap<>();
        if (latest != null) {
            result.put("stockCode", latest.getStockCode());
            result.put("closePrice", latest.getClosePrice());
            result.put("preClose", latest.getPreClose());
            result.put("tradeDate", latest.getTradeDate());
        }
        return result;
    }

    private StockDailyData queryLatest(String stockCode) {
        LambdaQueryWrapper<StockDailyData> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StockDailyData::getStockCode, stockCode)
                .orderByDesc(StockDailyData::getTradeDate)
                .last("LIMIT 1");
        return stockDailyDataMapper.selectOne(wrapper);
    }

    /**
     * 批量获取股票最新行情，消除 N+1 查询。
     * 一次 IN 查询所有代码的日线（按日期倒序），内存中每只代码取最新一条。
     */
    @GetMapping("/latest-prices")
    public Map<String, Map<String, Object>> getLatestPrices(@RequestParam("stockCodes") String stockCodes) {
        List<String> rawCodes = normalizeCodeList(stockCodes);
        Map<String, Map<String, Object>> result = new HashMap<>();
        if (rawCodes.isEmpty()) {
            return result;
        }

        // 查询集合 = 原值 ∪ 归一化后缀值，一次 IN 查询
        Map<String, String> queryToRaw = new HashMap<>();
        Set<String> queryCodes = new LinkedHashSet<>();
        for (String raw : rawCodes) {
            queryCodes.add(raw);
            String suffixed = StockCodeUtil.withSuffix(raw);
            queryCodes.add(suffixed);
            queryToRaw.putIfAbsent(suffixed, raw);
        }

        List<StockDailyData> rows = stockDailyDataMapper.selectList(
                new LambdaQueryWrapper<StockDailyData>()
                        .in(StockDailyData::getStockCode, queryCodes)
                        .orderByDesc(StockDailyData::getTradeDate));
        Set<String> matched = new HashSet<>();
        for (StockDailyData row : rows) {
            String raw = queryToRaw.getOrDefault(row.getStockCode(), row.getStockCode());
            if (!matched.add(raw)) {
                continue; // 已按日期倒序，每只代码第一条即最新
            }
            result.put(raw, toLatestMap(row));
        }
        return result;
    }

    /**
     * 批量获取股票基础信息，消除 N+1 查询（一次 IN 查询）。
     */
    @GetMapping("/infos")
    public Map<String, Map<String, Object>> getStockInfos(@RequestParam("stockCodes") String stockCodes) {
        List<String> rawCodes = normalizeCodeList(stockCodes);
        Map<String, Map<String, Object>> result = new HashMap<>();
        if (rawCodes.isEmpty()) {
            return result;
        }

        Map<String, String> queryToRaw = new HashMap<>();
        Set<String> queryCodes = new LinkedHashSet<>();
        for (String raw : rawCodes) {
            queryCodes.add(raw);
            String suffixed = StockCodeUtil.withSuffix(raw);
            queryCodes.add(suffixed);
            queryToRaw.putIfAbsent(suffixed, raw);
        }

        List<StockInfo> infos = stockInfoMapper.selectBatchIds(queryCodes);
        Set<String> matched = new HashSet<>();
        for (StockInfo info : infos) {
            String raw = queryToRaw.getOrDefault(info.getStockCode(), info.getStockCode());
            if (!matched.add(raw)) {
                continue;
            }
            result.put(raw, toMap(info));
        }
        return result;
    }

    /** 解析逗号分隔的代码列表并 trim。 */
    private List<String> normalizeCodeList(String stockCodes) {
        List<String> result = new ArrayList<>();
        if (stockCodes == null || stockCodes.isBlank()) {
            return result;
        }
        for (String raw : stockCodes.split(",")) {
            String code = raw.trim();
            if (!code.isEmpty()) {
                result.add(code);
            }
        }
        return result;
    }

    private Map<String, Object> toLatestMap(StockDailyData latest) {
        Map<String, Object> map = new HashMap<>();
        map.put("stockCode", latest.getStockCode());
        map.put("closePrice", latest.getClosePrice());
        map.put("preClose", latest.getPreClose());
        map.put("tradeDate", latest.getTradeDate());
        return map;
    }

    /**
     * 裸 6 位代码补全交易所后缀（统一走 StockCodeUtil，精确到前三位，含北交所 920xxx）。
     */
    private static String withSuffixIfBare(String code) {
        return StockCodeUtil.withSuffix(code);
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
