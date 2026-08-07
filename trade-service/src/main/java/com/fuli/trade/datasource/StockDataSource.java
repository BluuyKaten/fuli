package com.fuli.trade.datasource;

import com.fuli.trade.dto.KlineBarDTO;

import java.util.List;

/**
 * 股票数据源接口（支持多数据源切换）
 */
public interface StockDataSource {

    /**
     * 获取分钟 K 线
     * @param stockCode 纯数字代码（如 300750）
     * @param period 周期：1/5/15/60
     */
    List<KlineBarDTO> getMinuteData(String stockCode, int period);

    /**
     * 获取周 K 线
     */
    List<KlineBarDTO> getWeeklyData(String stockCode);

    /**
     * 获取月 K 线
     */
    List<KlineBarDTO> getMonthlyData(String stockCode);

    /**
     * 数据源名称
     */
    String getName();

    /**
     * 是否可用
     */
    boolean isAvailable();
}
