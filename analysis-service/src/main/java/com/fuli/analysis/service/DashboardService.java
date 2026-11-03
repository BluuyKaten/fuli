package com.fuli.analysis.service;

import com.fuli.analysis.vo.DashboardVO;
import com.fuli.common.api.feign.AuthFeignClient;
import com.fuli.common.api.feign.DataFeignClient;
import com.fuli.common.api.feign.TradeFeignClient;
import com.fuli.common.api.vo.PositionVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    @Cacheable(cacheNames = "dashboard", key = "#userId", unless = "#result == null")
    public DashboardVO getDashboardData(Long userId) {
        DashboardVO result = new DashboardVO();

        // 复用 trade-service 的持仓汇总（position_summary 的加权平均成本 + T+1 可卖数量），
        // 不再在 analysis 侧用「历史总买入」重算成本，避免买-卖-再买时成本口径错误。
        List<PositionVO> positions = getPositions(userId);

        BigDecimal totalMarketValue = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;

        for (PositionVO position : positions) {
            String stockCode = position.getStockCode();
            if (position.getStockName() == null || position.getStockName().isEmpty()) {
                position.setStockName(stockCode);
            }

            // 通过 Feign 获取最新行情
            StockLatestPrice latestPrice = getLatestPrice(stockCode);
            BigDecimal costPrice = position.getCostPrice() != null ? position.getCostPrice() : BigDecimal.ZERO;
            BigDecimal currentPrice = latestPrice != null ? latestPrice.price : costPrice;
            LocalDate priceDate = latestPrice != null ? latestPrice.tradeDate : null;

            int holdingQuantity = position.getHoldingQuantity() != null ? position.getHoldingQuantity() : 0;
            BigDecimal marketValue = currentPrice.multiply(BigDecimal.valueOf(holdingQuantity));
            BigDecimal positionCost = costPrice.multiply(BigDecimal.valueOf(holdingQuantity));
            BigDecimal floatingProfitLoss = marketValue.subtract(positionCost);

            BigDecimal dailyProfitLoss = BigDecimal.ZERO;
            if (latestPrice != null && latestPrice.preClose != null) {
                dailyProfitLoss = currentPrice.subtract(latestPrice.preClose).multiply(BigDecimal.valueOf(holdingQuantity));
            }

            position.setCostPrice(costPrice.setScale(2, RoundingMode.HALF_UP));
            position.setCurrentPrice(currentPrice.setScale(2, RoundingMode.HALF_UP));
            position.setMarketValue(marketValue.setScale(2, RoundingMode.HALF_UP));
            position.setFloatingProfitLoss(floatingProfitLoss.setScale(2, RoundingMode.HALF_UP));
            position.setDailyProfitLoss(dailyProfitLoss.setScale(2, RoundingMode.HALF_UP));
            position.setPriceDate(priceDate);

            totalMarketValue = totalMarketValue.add(marketValue);
            totalCost = totalCost.add(positionCost);
        }

        BigDecimal cashBalance = getUserCashBalance(userId);

        // 资金查询失败时返回明确错误，而非静默兜底到默认资金
        if (cashBalance == null) {
            log.error("DashboardService: 查询用户现金失败 userId={}", userId);
            return null;
        }

        // 总资产 = 资金余额 + 总市值
        BigDecimal totalAssets = cashBalance.add(totalMarketValue);

        // 浮动盈亏 = 总市值 - 持仓总成本
        BigDecimal floatingProfitLoss = totalMarketValue.subtract(totalCost);

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

    private List<PositionVO> getPositions(Long userId) {
        try {
            var result = tradeFeignClient.getPositions(userId);
            if (result != null && result.getCode() == 200 && result.getData() != null) {
                return result.getData();
            }
        } catch (Exception e) {
            log.warn("查询持仓失败，降级返回空: userId={}, error={}", userId, e.getMessage());
        }
        return new ArrayList<>();
    }

    private StockLatestPrice getLatestPrice(String stockCode) {
        try {
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

    private BigDecimal getUserCashBalance(Long userId) {
        try {
            var result = authFeignClient.getUserCash(userId);
            if (result != null && result.getCode() == 200 && result.getData() != null) {
                return result.getData();
            }
        } catch (Exception e) {
            log.warn("查询用户现金失败: userId={}, error={}", userId, e.getMessage());
        }
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
