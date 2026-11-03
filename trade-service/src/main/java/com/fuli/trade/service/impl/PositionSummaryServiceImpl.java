package com.fuli.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fuli.common.api.exception.BusinessException;
import com.fuli.trade.entity.PositionSummary;
import com.fuli.trade.entity.TradeRecord;
import com.fuli.common.api.enums.TradeTypeEnum;
import com.fuli.trade.mapper.PositionSummaryMapper;
import com.fuli.trade.mapper.TradeRecordMapper;
import com.fuli.trade.service.PositionSummaryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Slf4j
@Service
public class PositionSummaryServiceImpl implements PositionSummaryService {

    private final PositionSummaryMapper positionSummaryMapper;
    private final TradeRecordMapper tradeRecordMapper;

    public PositionSummaryServiceImpl(PositionSummaryMapper positionSummaryMapper, TradeRecordMapper tradeRecordMapper) {
        this.positionSummaryMapper = positionSummaryMapper;
        this.tradeRecordMapper = tradeRecordMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BigDecimal increasePosition(Long userId, String stockCode, String stockName,
                                       int quantity, BigDecimal price) {
        // 1. 幂等建仓（INSERT ... ON DUPLICATE KEY UPDATE），并发首次建仓不撞唯一键。
        getOrCreatePosition(userId, stockCode, stockName);

        // 2. 行锁读当前值，串行化「同股并发加仓」的读改写，避免两个事务都基于
        //    同一旧持仓各自计算并覆盖，导致持仓只加了一份、现金却扣了两份。
        PositionSummary position = positionSummaryMapper.selectForUpdate(userId, stockCode);
        if (position == null) {
            throw new BusinessException(400, "持仓不存在");
        }
        int oldQuantity = position.getTotalQuantity();
        BigDecimal oldAvgCost = position.getAvgCost() == null ? BigDecimal.ZERO : position.getAvgCost();

        // 加权平均：新均价 = (原持仓×原均价 + 新买入×成交价) / (原持仓 + 新买入)
        BigDecimal totalCost = oldAvgCost.multiply(BigDecimal.valueOf(oldQuantity))
                .add(price.multiply(BigDecimal.valueOf(quantity)));
        int newQuantity = oldQuantity + quantity;
        BigDecimal newAvgCost = newQuantity > 0
                ? totalCost.divide(BigDecimal.valueOf(newQuantity), 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // 3. 复用原子 SQL 累加数量并写均价（行锁保证两处更新都基于锁内旧值，串行一致）。
        positionSummaryMapper.increaseQuantity(userId, stockCode, quantity);
        positionSummaryMapper.updateAvgCost(userId, stockCode, newAvgCost);

        log.info("持仓增加: userId={}, stockCode={}, {}股 @{}, 新持仓={}, 新均价={}",
                userId, stockCode, quantity, price, newQuantity, newAvgCost);
        return newAvgCost;
    }

    @Override
    public int checkHolding(Long userId, String stockCode, int sellQuantity) {
        PositionSummary position = getPosition(userId, stockCode);
        if (position == null || position.getTotalQuantity() < sellQuantity) {
            int holding = position == null ? 0 : position.getTotalQuantity();
            throw new BusinessException(400, "持仓不足，当前持有 " + holding + " 股，尝试卖出 " + sellQuantity + " 股");
        }
        return position.getTotalQuantity();
    }

    @Override
    public BigDecimal decreasePositionAtomic(Long userId, String stockCode, int quantity) {
        // 1. 先取当前均价(用于返回给调用方计算盈亏)
        PositionSummary position = getPosition(userId, stockCode);
        if (position == null) {
            throw new BusinessException(400, "持仓不存在");
        }
        BigDecimal avgCost = position.getAvgCost();
        // 2. 原子扣减,受影响行=0 表示持仓不足
        int affected = positionSummaryMapper.decreaseQuantity(userId, stockCode, quantity);
        if (affected <= 0) {
            throw new BusinessException(400, "持仓不足,扣减失败");
        }
        log.info("持仓原子减少: userId={}, stockCode={}, {}股", userId, stockCode, quantity);
        return avgCost;
    }

    @Override
    public int getHoldingQuantity(Long userId, String stockCode) {
        PositionSummary position = getPosition(userId, stockCode);
        return position == null ? 0 : position.getTotalQuantity();
    }

    @Override
    public BigDecimal getAvgCost(Long userId, String stockCode) {
        PositionSummary position = getPosition(userId, stockCode);
        return position == null ? null : position.getAvgCost();
    }

    @Override
    public java.util.List<PositionSummary> listByUser(Long userId) {
        return positionSummaryMapper.selectList(
                new LambdaQueryWrapper<PositionSummary>()
                        .eq(PositionSummary::getUserId, userId)
                        .gt(PositionSummary::getTotalQuantity, 0));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rebuildPosition(Long userId, String stockCode, String stockName) {
        var records = tradeRecordMapper.selectList(new LambdaQueryWrapper<TradeRecord>()
                .eq(TradeRecord::getUserId, userId)
                .eq(TradeRecord::getStockCode, stockCode)
                .orderByAsc(TradeRecord::getTradeDate)
                .orderByAsc(TradeRecord::getId));
        int quantity = 0;
        BigDecimal avgCost = BigDecimal.ZERO;
        for (TradeRecord record : records) {
            if (TradeTypeEnum.BUY.getCode().equals(record.getTradeType())) {
                int nextQuantity = quantity + record.getTradeQuantity();
                avgCost = avgCost.multiply(BigDecimal.valueOf(quantity))
                        .add(record.getTradePrice().multiply(BigDecimal.valueOf(record.getTradeQuantity())))
                        .divide(BigDecimal.valueOf(nextQuantity), 4, RoundingMode.HALF_UP);
                quantity = nextQuantity;
            } else if (TradeTypeEnum.SELL.getCode().equals(record.getTradeType())) {
                quantity -= record.getTradeQuantity();
                if (quantity < 0) {
                    throw new BusinessException("交易历史持仓不足，无法重建成本");
                }
                if (quantity == 0) {
                    avgCost = BigDecimal.ZERO;
                }
            } else {
                throw new BusinessException("交易历史包含未知交易类型");
            }
        }
        PositionSummary position = getOrCreatePosition(userId, stockCode, stockName);
        position.setTotalQuantity(quantity);
        position.setAvgCost(avgCost);
        if (positionSummaryMapper.updateById(position) != 1) {
            throw new BusinessException("持仓重建失败");
        }
    }

    private PositionSummary getOrCreatePosition(Long userId, String stockCode, String stockName) {
        PositionSummary position = getPosition(userId, stockCode);
        if (position == null) {
            // 幂等建仓：并发首次建仓时唯一键冲突走 ON DUPLICATE KEY UPDATE，而非抛异常。
            positionSummaryMapper.insertIfAbsent(userId, stockCode, stockName);
            position = getPosition(userId, stockCode);
            if (position == null) {
                throw new BusinessException(400, "创建持仓失败");
            }
        }
        return position;
    }

    private PositionSummary getPosition(Long userId, String stockCode) {
        return positionSummaryMapper.selectOne(
                new LambdaQueryWrapper<PositionSummary>()
                        .eq(PositionSummary::getUserId, userId)
                        .eq(PositionSummary::getStockCode, stockCode)
        );
    }
}
