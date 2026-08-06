package com.fuli.trade.service;

import com.fuli.trade.entity.StockDailyData;
import com.fuli.trade.entity.StockInfo;

import java.util.List;
import java.util.Map;

public interface StockService {

    List<StockInfo> searchStocks(String keyword);

    List<StockDailyData> getDailyData(String stockCode, String startDate, String endDate);

    StockInfo getStockInfo(String stockCode);

    /**
     * 获取股票最新行情
     */
    StockDailyData getLatestPrice(String stockCode);

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
