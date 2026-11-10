package com.fuli.trade.service.impl;

import com.fuli.common.api.feign.DataFeignClient;
import com.fuli.common.api.util.StockCodeUtil;
import com.fuli.trade.entity.PositionSummary;
import com.fuli.trade.entity.TradeRecord;
import com.fuli.trade.mapper.PositionSummaryMapper;
import com.fuli.trade.mapper.TradeRecordMapper;
import com.fuli.trade.service.StockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
public class StockServiceImpl implements StockService {

    private final DataFeignClient dataFeignClient;
    private final PositionSummaryMapper positionSummaryMapper;
    private final TradeRecordMapper tradeRecordMapper;

    public StockServiceImpl(DataFeignClient dataFeignClient,
                            PositionSummaryMapper positionSummaryMapper,
                            TradeRecordMapper tradeRecordMapper) {
        this.dataFeignClient = dataFeignClient;
        this.positionSummaryMapper = positionSummaryMapper;
        this.tradeRecordMapper = tradeRecordMapper;
    }

    @Override
    public List<Map<String, Object>> searchStocks(String keyword) {
        try {
            List<Map<String, Object>> result = dataFeignClient.searchStocks(keyword);
            return result != null ? result : new java.util.ArrayList<>();
        } catch (Exception e) {
            log.warn("搜索股票失败，降级返回空: keyword={}, error={}", keyword, e.getMessage());
            return new java.util.ArrayList<>();
        }
    }

    @Override
    public List<Map<String, Object>> getDailyData(String stockCode, String startDate, String endDate, Integer limit) {
        try {
            List<Map<String, Object>> result = dataFeignClient.getDailyData(stockCode, startDate, endDate, limit);
            return result != null ? result : new java.util.ArrayList<>();
        } catch (Exception e) {
            log.warn("获取日线失败，降级返回空: stockCode={}, error={}", stockCode, e.getMessage());
            return new java.util.ArrayList<>();
        }
    }

    @Override
    public List<Map<String, Object>> getDailySparkline(String stockCode, int points) {
        try {
            List<Map<String, Object>> result = dataFeignClient.getDailySparkline(stockCode, points);
            return result != null ? result : new java.util.ArrayList<>();
        } catch (Exception e) {
            // 缩略条只是辅助信息，拿不到就不显示，不应影响主图
            log.warn("获取缩略序列失败，降级返回空: stockCode={}, error={}", stockCode, e.getMessage());
            return new java.util.ArrayList<>();
        }
    }

    @Override
    public Map<String, Object> getStockInfo(String stockCode) {
        try {
            Map<String, Object> result = dataFeignClient.getStockInfo(stockCode);
            return result != null ? result : new HashMap<>();
        } catch (Exception e) {
            log.warn("获取股票信息失败，降级返回空: stockCode={}, error={}", stockCode, e.getMessage());
            return new HashMap<>();
        }
    }

    @Override
    public Map<String, Object> getLatestPrice(String stockCode) {
        try {
            Map<String, Object> result = dataFeignClient.getLatestPrice(stockCode);
            return result != null ? result : new HashMap<>();
        } catch (Exception e) {
            log.warn("获取最新价失败，降级返回空: stockCode={}, error={}", stockCode, e.getMessage());
            return new HashMap<>();
        }
    }

    @Override
    public Map<String, Map<String, Object>> getLatestPrices(String stockCodes) {
        try {
            Map<String, Map<String, Object>> result = dataFeignClient.getLatestPrices(stockCodes);
            return result != null ? result : new HashMap<>();
        } catch (Exception e) {
            log.warn("批量获取最新价失败，降级返回空: stockCodes={}, error={}", stockCodes, e.getMessage());
            return new HashMap<>();
        }
    }

    @Override
    public Map<String, Map<String, Object>> getStockInfos(String stockCodes) {
        try {
            Map<String, Map<String, Object>> result = dataFeignClient.getStockInfos(stockCodes);
            return result != null ? result : new HashMap<>();
        } catch (Exception e) {
            log.warn("批量获取股票信息失败，降级返回空: stockCodes={}, error={}", stockCodes, e.getMessage());
            return new HashMap<>();
        }
    }

    @Override
    public int getHoldingQuantity(Long userId, String stockCode) {
        PositionSummary position = positionSummaryMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PositionSummary>()
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
        // marketCode 来自 instrument.market（data-service 新增字段）；market 仍是 Tushare 板块，
        // 只用于展示，不能用来判断交易规则——「主板/创业板」不是交易所。
        Map<String, Object> stockInfo = getStockInfo(stockCode);
        String market = stockInfo.get("market") != null ? stockInfo.get("market").toString() : "";
        String marketCode = stockInfo.get("marketCode") != null ? stockInfo.get("marketCode").toString() : "";
        boolean isAStock = isAStock(marketCode, stockCode);

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
     * 判断是否为 A 股（决定 T+1 冻结）。
     *
     * <p>先读 {@code instrument.market}（data-service 的 {@code marketCode}）；
     * 缺失时按符号后缀兜底。**不再用六位数字正则**——那套规则会把 {@code AAPL} 当成 A 股，
     * 接入美股后直接导致 T+0 标的被错误冻结。
     */
    private boolean isAStock(String marketCode, String stockCode) {
        if (marketCode != null && !marketCode.isBlank()) {
            return StockCodeUtil.isAShareMarket(marketCode.trim().toUpperCase(Locale.ROOT));
        }
        try {
            return StockCodeUtil.isAShareMarket(StockCodeUtil.marketOf(stockCode));
        } catch (IllegalArgumentException e) {
            // 无法判定就当非 A 股：宁可走 T+0 的通用路径，也不能凭空冻结可卖数量
            return false;
        }
    }

    /**
     * 获取用户当天买入某只股票的数量
     */
    private int getTodayBuyQuantity(Long userId, String stockCode) {
        try {
            LocalDate today = LocalDate.now();
            List<TradeRecord> todayBuys = tradeRecordMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TradeRecord>()
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
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TradeRecord>()
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
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PositionSummary>()
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

