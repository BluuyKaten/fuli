package com.fuli.trade.service;

import com.fuli.trade.entity.PositionSummary;

import java.math.BigDecimal;
import java.util.List;

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
     * 卖出后减少持仓(原子操作,并发安全)
     * @return 卖出成本价(加权平均)
     * @throws com.fuli.common.api.exception.BusinessException 持仓不足
     */
    BigDecimal decreasePositionAtomic(Long userId, String stockCode, int quantity);

    /**
     * 查询当前持仓数量
     */
    int getHoldingQuantity(Long userId, String stockCode);

    /**
     * 查询当前加权平均成本
     */
    BigDecimal getAvgCost(Long userId, String stockCode);

    /** 根据尚未删除的交易重建持仓数量和加权成本。与交易删除在同一事务中执行。 */
    void rebuildPosition(Long userId, String stockCode, String stockName);

    /** 查询用户所有持仓（数量 > 0），供 analysis-service 复用成本口径。 */
    List<PositionSummary> listByUser(Long userId);
}
