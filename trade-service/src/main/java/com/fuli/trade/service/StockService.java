package com.fuli.trade.service;

import java.util.List;
import java.util.Map;

public interface StockService {

    List<Map<String, Object>> searchStocks(String keyword);

    /**
     * 获取股票日线行情。
     *
     * <p>{@code limit} 仅在未指定起止日期时生效（取最近 N 根）；指定区间时按区间返回，
     * 因为练习模块要对整段区间做连续性校验，截断会让校验结论失真。
     */
    List<Map<String, Object>> getDailyData(String stockCode, String startDate, String endDate, Integer limit);

    /** 全历史收盘价缩略序列（服务端已降采样），供 K 线底部全貌条使用 */
    List<Map<String, Object>> getDailySparkline(String stockCode, int points);

    Map<String, Object> getStockInfo(String stockCode);

    /**
     * 获取股票最新行情
     */
    Map<String, Object> getLatestPrice(String stockCode);

    /**
     * 批量获取股票最新行情（一次 Feign 批量调用，消除 N+1）
     * @param stockCodes 逗号分隔的股票代码
     * @return key=股票代码, value=行情 Map
     */
    Map<String, Map<String, Object>> getLatestPrices(String stockCodes);

    /**
     * 批量获取股票基础信息（一次 Feign 批量调用，消除 N+1）
     */
    Map<String, Map<String, Object>> getStockInfos(String stockCodes);

    /**
     * 查询用户持仓数量
     */
    int getHoldingQuantity(Long userId, String stockCode);

    /**
     * 查询用户可卖数量（考虑A股T+1规则）
     * @return Map包含：totalQuantity（总持仓）、availableQuantity（可卖数量）、frozenQuantity（T+1冻结数量）、market（市场类型）
     */
    Map<String, Object> getAvailableQuantity(Long userId, String stockCode);

    /**
     * 修复持仓数据（根据交易记录重新计算）
     */
    Map<String, Object> fixHoldingData(Long userId);
}

