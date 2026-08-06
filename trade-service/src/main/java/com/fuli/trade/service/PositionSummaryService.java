package com.fuli.trade.service;

import java.math.BigDecimal;

/**
 * 持仓汇总服务接口
 */
public interface PositionSummaryService {

    /**
     * 买入后更新持仓（加权平均成本）
     * @return 更新后的平均成本
     */
    BigDecimal increasePosition(Long userId, String stockCode, String stockName,
                                int quantity, BigDecimal price);

    /**
     * 卖出前校验持仓是否足够
     * @return 当前持仓数量
     */
    int checkHolding(Long userId, String stockCode, int sellQuantity);

    /**
     * 卖出后减少持仓
     * @return 卖出成本价（加权平均）
     */
    BigDecimal decreasePosition(Long userId, String stockCode, int quantity);

    /**
     * 查询当前持仓数量
     */
    int getHoldingQuantity(Long userId, String stockCode);

    /**
     * 查询当前加权平均成本
     */
    BigDecimal getAvgCost(Long userId, String stockCode);
}
