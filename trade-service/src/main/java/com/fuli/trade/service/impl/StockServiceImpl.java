package com.fuli.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fuli.trade.entity.PositionSummary;
import com.fuli.trade.entity.StockDailyData;
import com.fuli.trade.entity.StockInfo;
import com.fuli.trade.entity.TradeRecord;
import com.fuli.trade.mapper.PositionSummaryMapper;
import com.fuli.trade.mapper.StockDailyDataMapper;
import com.fuli.trade.mapper.StockInfoMapper;
import com.fuli.trade.mapper.TradeRecordMapper;
import com.fuli.trade.service.StockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class StockServiceImpl implements StockService {

    private final StockInfoMapper stockInfoMapper;
    private final StockDailyDataMapper stockDailyDataMapper;
    private final PositionSummaryMapper positionSummaryMapper;
    private final TradeRecordMapper tradeRecordMapper;

    public StockServiceImpl(StockInfoMapper stockInfoMapper,
                            StockDailyDataMapper stockDailyDataMapper,
                            PositionSummaryMapper positionSummaryMapper,
                            TradeRecordMapper tradeRecordMapper) {
        this.stockInfoMapper = stockInfoMapper;
        this.stockDailyDataMapper = stockDailyDataMapper;
        this.positionSummaryMapper = positionSummaryMapper;
        this.tradeRecordMapper = tradeRecordMapper;
    }

    @Override
    public List<StockInfo> searchStocks(String keyword) {
        QueryWrapper<StockInfo> wrapper = new QueryWrapper<>();
        wrapper.like("stock_code", keyword).or().like("stock_name", keyword);
        wrapper.orderByAsc("stock_code");
        wrapper.last("LIMIT 20");
        return stockInfoMapper.selectList(wrapper);
    }

    @Override
    public List<StockDailyData> getDailyData(String stockCode, String startDate, String endDate) {
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

    @Override
    public StockInfo getStockInfo(String stockCode) {
        return stockInfoMapper.selectById(stockCode);
    }

    @Override
    public StockDailyData getLatestPrice(String stockCode) {
        LambdaQueryWrapper<StockDailyData> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StockDailyData::getStockCode, stockCode)
                .orderByDesc(StockDailyData::getTradeDate)
                .last("LIMIT 1");
        return stockDailyDataMapper.selectOne(wrapper);
    }

    @Override
    public int getHoldingQuantity(Long userId, String stockCode) {
        PositionSummary position = positionSummaryMapper.selectOne(
                new LambdaQueryWrapper<PositionSummary>()
                        .eq(PositionSummary::getUserId, userId)
                        .eq(PositionSummary::getStockCode, stockCode)
                        .eq(PositionSummary::getDeleted, 0)
        );
        return position != null ? position.getTotalQuantity() : 0;
    }

    /**
     * 查询用户可卖数量
     * A股为T+1，当天买入的股票不能当天卖出
     * 美股为T+0，当天买入的股票可以当天卖出（当前暂未支持美股）
     *
     * @param userId    用户ID
     * @param stockCode 股票代码
     * @return Map包含：
     *         - totalQuantity: 总持仓数量
     *         - availableQuantity: 可卖数量
     *         - frozenQuantity: 冻结数量（T+1限制，当天买入的部分）
     *         - market: 市场类型（SH/SZ/BJ为A股，其他为美股等）
     *         - tradeRule: 交易规则（T+1/T+0）
     */
    @Override
    public Map<String, Object> getAvailableQuantity(Long userId, String stockCode) {
        Map<String, Object> result = new HashMap<>();

        // 1. 获取股票信息，判断市场类型
        StockInfo stockInfo = stockInfoMapper.selectById(stockCode);
        String market = stockInfo != null ? stockInfo.getMarket() : "";
        boolean isAStock = isAStock(market, stockCode);

        // 2. 获取总持仓数量
        int totalQuantity = getHoldingQuantity(userId, stockCode);

        // 3. 如果是A股（T+1），需要减去当天买入的数量
        int frozenQuantity = 0;
        if (isAStock) {
            frozenQuantity = getTodayBuyQuantity(userId, stockCode);
        }

        int availableQuantity = Math.max(0, totalQuantity - frozenQuantity);

        result.put("userId", userId);
        result.put("stockCode", stockCode);
        result.put("totalQuantity", totalQuantity);
        result.put("availableQuantity", availableQuantity);
        result.put("frozenQuantity", frozenQuantity);
        result.put("market", market);
        result.put("tradeRule", isAStock ? "T+1" : "T+0");
        result.put("isAStock", isAStock);

        log.info("查询可卖数量: userId={}, stockCode={}, total={}, available={}, frozen={}, market={}, rule={}",
                userId, stockCode, totalQuantity, availableQuantity, frozenQuantity, market, isAStock ? "T+1" : "T+0");

        return result;
    }

    /**
     * 判断是否为A股
     * A股代码特征：
     * - 上海：60xxxx.SH、68xxxx.SH（科创板）
     * - 深圳：00xxxx.SZ、30xxxx.SZ（创业板）
     * - 北京：8xxxxx.BJ、43xxxx.BJ
     */
    private boolean isAStock(String market, String stockCode) {
        if (market == null) {
            // 兜底：通过代码后缀判断
            return stockCode != null && (stockCode.endsWith(".SH") || stockCode.endsWith(".SZ") || stockCode.endsWith(".BJ"));
        }
        return "SH".equalsIgnoreCase(market) || "SZ".equalsIgnoreCase(market) || "BJ".equalsIgnoreCase(market);
    }

    /**
     * 获取用户当天买入某只股票的数量
     */
    private int getTodayBuyQuantity(Long userId, String stockCode) {
        try {
            LocalDate today = LocalDate.now();
            List<TradeRecord> todayBuys = tradeRecordMapper.selectList(
                    new LambdaQueryWrapper<TradeRecord>()
                            .eq(TradeRecord::getUserId, userId)
                            .eq(TradeRecord::getStockCode, stockCode)
                            .eq(TradeRecord::getTradeType, 1)  // 买入
                            .eq(TradeRecord::getTradeDate, today)
            );
            return todayBuys.stream().mapToInt(TradeRecord::getTradeQuantity).sum();
        } catch (Exception e) {
            log.warn("查询当天买入数量失败: userId={}, stockCode={}", userId, stockCode, e);
            return 0;
        }
    }

    /**
     * 根据交易记录重新计算持仓数据
     * 用于修复历史数据不一致的问题
     */
    @Override
    public Map<String, Object> fixHoldingData(Long userId) {
        Map<String, Object> result = new HashMap<>();

        // 1. 查询用户所有交易记录
        List<TradeRecord> trades = tradeRecordMapper.selectList(
                new LambdaQueryWrapper<TradeRecord>()
                        .eq(TradeRecord::getUserId, userId)
                        .orderByAsc(TradeRecord::getTradeDate)
        );

        // 2. 按股票分组计算持仓
        Map<String, Integer> holdingMap = new HashMap<>();
        Map<String, BigDecimal> costMap = new HashMap<>();
        Map<String, String> nameMap = new HashMap<>();

        for (TradeRecord trade : trades) {
            String stockCode = trade.getStockCode();
            nameMap.put(stockCode, trade.getStockName());

            if (trade.getTradeType() == 1) {
                // 买入
                int oldQty = holdingMap.getOrDefault(stockCode, 0);
                BigDecimal oldCost = costMap.getOrDefault(stockCode, BigDecimal.ZERO);
                int newQty = oldQty + trade.getTradeQuantity();
                BigDecimal newCost = oldCost.multiply(BigDecimal.valueOf(oldQty))
                        .add(trade.getTradePrice().multiply(BigDecimal.valueOf(trade.getTradeQuantity())))
                        .divide(BigDecimal.valueOf(Math.max(newQty, 1)), 4, RoundingMode.HALF_UP);
                holdingMap.put(stockCode, newQty);
                costMap.put(stockCode, newCost);
            } else {
                // 卖出
                int oldQty = holdingMap.getOrDefault(stockCode, 0);
                holdingMap.put(stockCode, Math.max(0, oldQty - trade.getTradeQuantity()));
            }
        }

        // 3. 更新 position_summary 表
        int fixedCount = 0;
        for (Map.Entry<String, Integer> entry : holdingMap.entrySet()) {
            String stockCode = entry.getKey();
            int quantity = entry.getValue();
            BigDecimal avgCost = costMap.getOrDefault(stockCode, BigDecimal.ZERO);

            PositionSummary position = positionSummaryMapper.selectOne(
                    new LambdaQueryWrapper<PositionSummary>()
                            .eq(PositionSummary::getUserId, userId)
                            .eq(PositionSummary::getStockCode, stockCode)
            );

            if (position == null) {
                position = new PositionSummary();
                position.setUserId(userId);
                position.setStockCode(stockCode);
                position.setStockName(nameMap.get(stockCode));
                position.setTotalQuantity(quantity);
                position.setAvgCost(avgCost);
                positionSummaryMapper.insert(position);
            } else {
                position.setTotalQuantity(quantity);
                position.setAvgCost(avgCost);
                positionSummaryMapper.updateById(position);
            }
            fixedCount++;
        }

        result.put("userId", userId);
        result.put("fixedCount", fixedCount);
        result.put("holdings", holdingMap);
        return result;
    }
}
