package com.fuli.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fuli.common.api.exception.BusinessException;
import com.fuli.trade.entity.PositionSummary;
import com.fuli.trade.mapper.PositionSummaryMapper;
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

    public PositionSummaryServiceImpl(PositionSummaryMapper positionSummaryMapper) {
        this.positionSummaryMapper = positionSummaryMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BigDecimal increasePosition(Long userId, String stockCode, String stockName,
                                       int quantity, BigDecimal price) {
        PositionSummary position = getOrCreatePosition(userId, stockCode, stockName);
        int oldQuantity = position.getTotalQuantity();
        BigDecimal oldAvgCost = position.getAvgCost();

        // 加权平均：新均价 = (原持仓×原均价 + 新买入×成交价) / (原持仓 + 新买入)
        BigDecimal totalCost = oldAvgCost.multiply(BigDecimal.valueOf(oldQuantity))
                .add(price.multiply(BigDecimal.valueOf(quantity)));
        int newQuantity = oldQuantity + quantity;
        BigDecimal newAvgCost = newQuantity > 0
                ? totalCost.divide(BigDecimal.valueOf(newQuantity), 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        position.setTotalQuantity(newQuantity);
        position.setAvgCost(newAvgCost);
        positionSummaryMapper.updateById(position);

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
    @Transactional(rollbackFor = Exception.class)
    public BigDecimal decreasePosition(Long userId, String stockCode, int quantity) {
        PositionSummary position = getPosition(userId, stockCode);
        if (position == null) {
            throw new BusinessException(400, "持仓不存在");
        }
        BigDecimal avgCost = position.getAvgCost();
        int newQuantity = position.getTotalQuantity() - quantity;
        if (newQuantity < 0) {
            throw new BusinessException(400, "持仓不足");
        }
        position.setTotalQuantity(newQuantity);
        positionSummaryMapper.updateById(position);

        log.info("持仓减少: userId={}, stockCode={}, {}股, 剩余={}", userId, stockCode, quantity, newQuantity);
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

    private PositionSummary getOrCreatePosition(Long userId, String stockCode, String stockName) {
        PositionSummary position = getPosition(userId, stockCode);
        if (position == null) {
            position = new PositionSummary();
            position.setUserId(userId);
            position.setStockCode(stockCode);
            position.setStockName(stockName);
            position.setTotalQuantity(0);
            position.setAvgCost(BigDecimal.ZERO);
            positionSummaryMapper.insert(position);
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
