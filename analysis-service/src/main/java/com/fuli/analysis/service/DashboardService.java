package com.fuli.analysis.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fuli.common.api.dto.TradeQueryDTO;
import com.fuli.common.api.feign.AuthFeignClient;
import com.fuli.common.api.feign.TradeFeignClient;
import com.fuli.common.api.vo.PositionVO;
import com.fuli.common.api.vo.TradeVO;
import com.fuli.trade.entity.StockDailyData;
import com.fuli.trade.entity.StockInfo;
import com.fuli.trade.mapper.StockDailyDataMapper;
import com.fuli.trade.mapper.StockInfoMapper;
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
    private final StockDailyDataMapper stockDailyDataMapper;
    private final StockInfoMapper stockInfoMapper;

    public DashboardService(TradeFeignClient tradeFeignClient,
                            AuthFeignClient authFeignClient,
                            StockDailyDataMapper stockDailyDataMapper,
                            StockInfoMapper stockInfoMapper) {
        this.tradeFeignClient = tradeFeignClient;
        this.authFeignClient = authFeignClient;
        this.stockDailyDataMapper = stockDailyDataMapper;
        this.stockInfoMapper = stockInfoMapper;
    }

    public Map<String, Object> getDashboardData(Long userId) {
        Map<String, Object> result = new HashMap<>();

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

            StockInfo stockInfo = stockInfoMapper.selectById(stockCode);
            String stockName = stockInfo != null ? stockInfo.getStockName() : stockCode;

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
        BigDecimal initialCash = getUserInitialCash(userId);

        BigDecimal totalAssets = cashBalance.add(totalMarketValue);
        BigDecimal totalProfitLoss = totalAssets.subtract(initialCash);
        BigDecimal profitPercentage = totalProfitLoss.divide(initialCash, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));

        result.put("totalAssets", totalAssets.setScale(2, RoundingMode.HALF_UP));
        result.put("profitPercentage", profitPercentage.setScale(2, RoundingMode.HALF_UP));
        result.put("floatingProfitLoss", totalProfitLoss.setScale(2, RoundingMode.HALF_UP));
        result.put("totalMarketValue", totalMarketValue.setScale(2, RoundingMode.HALF_UP));
        result.put("cashBalance", cashBalance.setScale(2, RoundingMode.HALF_UP));
        result.put("positions", positions);

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
        LambdaQueryWrapper<StockDailyData> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StockDailyData::getStockCode, stockCode)
                .orderByDesc(StockDailyData::getTradeDate)
                .last("LIMIT 1");
        StockDailyData latest = stockDailyDataMapper.selectOne(wrapper);
        if (latest != null) {
            return new StockLatestPrice(
                    latest.getClosePrice() != null ? latest.getClosePrice() : BigDecimal.ZERO,
                    latest.getTradeDate() != null ? LocalDate.parse(latest.getTradeDate(), DateTimeFormatter.ofPattern("yyyyMMdd")) : null,
                    latest.getPreClose() != null ? latest.getPreClose() : null
            );
        }
        return null;
    }

    private BigDecimal getUserCashBalance(Long userId) {
        var result = authFeignClient.getUserCash(userId);
        if (result != null && result.getCode() == 200 && result.getData() != null) {
            return result.getData();
        }
        return new BigDecimal("200000.00");
    }

    private BigDecimal getUserInitialCash(Long userId) {
        var result = authFeignClient.getUserInitialCash(userId);
        if (result != null && result.getCode() == 200 && result.getData() != null) {
            return result.getData();
        }
        return new BigDecimal("200000.00");
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
