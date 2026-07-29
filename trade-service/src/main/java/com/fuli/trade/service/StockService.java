package com.fuli.trade.service;

import com.fuli.trade.entity.StockDailyData;
import com.fuli.trade.entity.StockInfo;

import java.util.List;

public interface StockService {

    List<StockInfo> searchStocks(String keyword);

    List<StockDailyData> getDailyData(String stockCode, String startDate, String endDate);

    StockInfo getStockInfo(String stockCode);
}
