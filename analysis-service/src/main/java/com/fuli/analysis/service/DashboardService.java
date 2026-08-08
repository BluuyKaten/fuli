package com.fuli.analysis.service;

import com.fuli.analysis.vo.DashboardVO;
import com.fuli.common.api.dto.TradeQueryDTO;
import com.fuli.common.api.feign.AuthFeignClient;
import com.fuli.common.api.feign.DataFeignClient;
import com.fuli.common.api.feign.TradeFeignClient;
import com.fuli.common.api.vo.PositionVO;
import com.fuli.common.api.vo.TradeVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DashboardService {

    private final TradeFeignClient tradeFeignClient;
    private final AuthFeignClient authFeignClient;
    private final DataFeignClient dataFeignClient;

    public DashboardService(TradeFeignClient tradeFeignClient,
                            AuthFeignClient authFeignClient,
                            DataFeignClient dataFeignClient) {
        this.tradeFeignClient = tradeFeignClient;
        this.authFeignClient = authFeignClient;
        this.dataFeignClient = dataFeignClient;
    }

    public DashboardVO getDashboardData(Long userId) {
        DashboardVO result = new DashboardVO();

        List<TradeVO> trades = getTradeList(userId);
        Map<String, List<TradeVO>> tradesByStock = trades.stream()
                .collect(Collectors.groupingBy(TradeVO::getStockCode));

        List<PositionVO> positions = new ArrayList<>();
        BigDecimal totalMarketValue = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;

        for (Map.Entry<String, List<TradeVO>> entry : tradesByStock.entrySet()) {
            String stockCode = entry.getKey();
            List<TradeVO> stockTrades = entry.getValue();

            int totalBuyQuantity = 0;
            BigDecimal totalBuyAmount = BigDecimal.ZERO;
            int totalSellQuantity = 0;
            BigDecimal totalSellAmount = BigDecimal.ZERO;

            for (TradeVO trade : stockTrades) {
                if (trade.getTradeType() == 1) {
                    totalBuyQuantity += trade.getTradeQuantity();
                    totalBuyAmount = totalBuyAmount.add(trade.getTotalCost());
                } else {
                    totalSellQuantity += trade.getTradeQuantity();
                    totalSellAmount = totalSellAmount.add(trade.getTotalCost());
                }
            }

            int holdingQuantity = totalBuyQuantity - totalSellQuantity;
            if (holdingQuantity <= 0) continue;

            BigDecimal costPrice = totalBuyAmount.divide(BigDecimal.valueOf(totalBuyQuantity), 4, RoundingMode.HALF_UP);

            // 通过 Feign 获取最新行情
            StockLatestPrice latestPrice = getLatestPrice(stockCode);
            BigDecimal currentPrice = latestPrice != null ? latestPrice.price : costPrice;
            LocalDate priceDate = latestPrice != null ? latestPrice.tradeDate : null;

            BigDecimal marketValue = currentPrice.multiply(BigDecimal.valueOf(holdingQuantity));
            BigDecimal positionCost = costPrice.multiply(BigDecimal.valueOf(holdingQuantity));
            BigDecimal floatingProfitLoss = marketValue.subtract(positionCost);

            BigDecimal dailyProfitLoss = BigDecimal.ZERO;
            if (latestPrice != null && latestPrice.preClose != null) {
                dailyProfitLoss = currentPrice.subtract(latestPrice.preClose).multiply(BigDecimal.valueOf(holdingQuantity));
            }

            // 通过 Feign 获取股票名称
            String stockName = getStockName(stockCode, stockTrades);

            PositionVO position = new PositionVO();
            position.setStockCode(stockCode);
            position.setStockName(stockName);
            position.setHoldingQuantity(holdingQuantity);
            position.setAvailableQuantity(holdingQuantity);
            position.setCostPrice(costPrice.setScale(2, RoundingMode.HALF_UP));
            position.setCurrentPrice(currentPrice.setScale(2, RoundingMode.HALF_UP));
            position.setMarketValue(marketValue.setScale(2, RoundingMode.HALF_UP));
            position.setFloatingProfitLoss(floatingProfitLoss.setScale(2, RoundingMode.HALF_UP));
            position.setDailyProfitLoss(dailyProfitLoss.setScale(2, RoundingMode.HALF_UP));
            position.setPriceDate(priceDate);

            positions.add(position);
            totalMarketValue = totalMarketValue.add(marketValue);
            totalCost = totalCost.add(positionCost);
        }

        BigDecimal cashBalance = getUserCashBalance(userId);

        // 资金查询失败时返回明确错误，而非静默兜底到默认资金（避免用户看到错误资产数字）
        if (cashBalance == null) {
            log.error("DashboardService: 查询用户现金失败 userId={}", userId);
            return null;
        }

        // 总资产 = 资金余额 + 总市值
        BigDecimal totalAssets = cashBalance.add(totalMarketValue);

        // 浮动盈亏 = 总市值 - 持仓总成本（仅持仓部分的账面盈亏）
        BigDecimal floatingProfitLoss = totalMarketValue.subtract(totalCost);

        // 盈利百分比 = 浮动盈亏 / 持仓总成本 × 100%
        BigDecimal profitPercentage = BigDecimal.ZERO;
        if (totalCost.compareTo(BigDecimal.ZERO) > 0) {
            profitPercentage = floatingProfitLoss.divide(totalCost, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
        }

        result.setTotalAssets(totalAssets.setScale(2, RoundingMode.HALF_UP));
        result.setProfitPercentage(profitPercentage.setScale(2, RoundingMode.HALF_UP));
        result.setFloatingProfitLoss(floatingProfitLoss.setScale(2, RoundingMode.HALF_UP));
        result.setTotalMarketValue(totalMarketValue.setScale(2, RoundingMode.HALF_UP));
        result.setCashBalance(cashBalance.setScale(2, RoundingMode.HALF_UP));
        result.setPositions(positions);

        return result;
    }

    private List<TradeVO> getTradeList(Long userId) {
        TradeQueryDTO queryDTO = new TradeQueryDTO();
        queryDTO.setUserId(userId);
        var result = tradeFeignClient.queryByCondition(queryDTO);
        if (result != null && result.getCode() == 200) {
            return result.getData();
        }
        return new ArrayList<>();
    }

    private StockLatestPrice getLatestPrice(String stockCode) {
        try {
            // 通过 data-service 获取行情（不再经过 trade-service 中转）
            Map<String, Object> data = dataFeignClient.getLatestPrice(stockCode);
            if (data != null && !data.isEmpty()) {
                BigDecimal closePrice = data.get("closePrice") != null
                        ? new BigDecimal(data.get("closePrice").toString()) : BigDecimal.ZERO;
                String tradeDateStr = data.get("tradeDate") != null ? data.get("tradeDate").toString() : null;
                LocalDate tradeDate = tradeDateStr != null
                        ? LocalDate.parse(tradeDateStr, DateTimeFormatter.ofPattern("yyyyMMdd")) : null;
                BigDecimal preClose = data.get("preClose") != null
                        ? new BigDecimal(data.get("preClose").toString()) : null;
                return new StockLatestPrice(closePrice, tradeDate, preClose);
            }
        } catch (Exception e) {
            log.warn("获取最新行情失败: stockCode={}, error={}", stockCode, e.getMessage());
        }
        return null;
    }

    private String getStockName(String stockCode, List<TradeVO> stockTrades) {
        // 优先从交易记录中获取名称
        for (TradeVO trade : stockTrades) {
            if (trade.getStockName() != null && !trade.getStockName().isEmpty()) {
                return trade.getStockName();
            }
        }
        // 通过 data-service 获取
        try {
            Map<String, Object> data = dataFeignClient.getStockInfo(stockCode);
            if (data != null && !data.isEmpty()) {
                Object name = data.get("stockName");
                if (name != null) return name.toString();
            }
        } catch (Exception e) {
            log.warn("获取股票名称失败: stockCode={}", stockCode);
        }
        return stockCode;
    }

    private BigDecimal getUserCashBalance(Long userId) {
        var result = authFeignClient.getUserCash(userId);
        if (result != null && result.getCode() == 200 && result.getData() != null) {
            return result.getData();
        }
        // 不再静默兜底到默认资金 200000，返回 null 由上层明确报错
        return null;
    }

    private static class StockLatestPrice {
        BigDecimal price;
        LocalDate tradeDate;
        BigDecimal preClose;

        StockLatestPrice(BigDecimal price, LocalDate tradeDate, BigDecimal preClose) {
            this.price = price;
            this.tradeDate = tradeDate;
            this.preClose = preClose;
        }
    }
}
