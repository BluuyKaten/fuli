package com.fuli.analysis.service;

import com.fuli.common.api.dto.TradeQueryDTO;
import com.fuli.common.api.feign.TradeFeignClient;
import com.fuli.common.api.vo.MonthlyProfitVO;
import com.fuli.common.api.vo.StatisticsVO;
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
public class AnalysisService {

    private final TradeFeignClient tradeFeignClient;

    public AnalysisService(TradeFeignClient tradeFeignClient) {
        this.tradeFeignClient = tradeFeignClient;
    }

    public StatisticsVO getStatistics(Long userId, String stockCode, LocalDate startDate, LocalDate endDate) {
        TradeQueryDTO queryDTO = new TradeQueryDTO();
        queryDTO.setUserId(userId);
        queryDTO.setStockCode(stockCode);
        queryDTO.setStartDate(startDate);
        queryDTO.setEndDate(endDate);

        var result = tradeFeignClient.getStatistics(queryDTO);
        if (result != null && result.getCode() == 200) {
            return result.getData();
        }
        return null;
    }

    public List<TradeVO> getTradeList(Long userId, String stockCode, LocalDate startDate, LocalDate endDate) {
        TradeQueryDTO queryDTO = new TradeQueryDTO();
        queryDTO.setUserId(userId);
        queryDTO.setStockCode(stockCode);
        queryDTO.setStartDate(startDate);
        queryDTO.setEndDate(endDate);

        var result = tradeFeignClient.queryByCondition(queryDTO);
        if (result != null && result.getCode() == 200) {
            return result.getData();
        }
        return new ArrayList<>();
    }

    public List<MonthlyProfitVO> getMonthlyProfit(Long userId, LocalDate startDate, LocalDate endDate) {
        List<TradeVO> trades = getTradeList(userId, null, startDate, endDate);

        Map<String, List<TradeVO>> monthlyMap = trades.stream()
                .filter(t -> t.getProfitLoss() != null)
                .collect(Collectors.groupingBy(
                        t -> t.getTradeDate().format(DateTimeFormatter.ofPattern("yyyy-MM")),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        return monthlyMap.entrySet().stream().map(entry -> {
            String month = entry.getKey();
            List<TradeVO> monthTrades = entry.getValue();

            MonthlyProfitVO vo = new MonthlyProfitVO();
            vo.setMonth(month);
            vo.setTradeCount(monthTrades.size());

            BigDecimal totalProfitLoss = monthTrades.stream()
                    .map(TradeVO::getProfitLoss)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            vo.setProfitLoss(totalProfitLoss);

            long winCount = monthTrades.stream()
                    .filter(t -> t.getProfitLoss().compareTo(BigDecimal.ZERO) > 0)
                    .count();
            BigDecimal winRate = BigDecimal.valueOf(winCount)
                    .divide(BigDecimal.valueOf(monthTrades.size()), 4, RoundingMode.HALF_UP);
            vo.setWinRate(winRate);

            return vo;
        }).collect(Collectors.toList());
    }

    /**
     * 资产曲线 - 包含持仓浮动盈亏
     * 逻辑：根据每日持仓数量和当日收盘价计算持仓市值
     */
    public Map<String, Object> getAssetCurve(Long userId, LocalDate startDate, LocalDate endDate) {
        // 1. 获取用户所有交易记录
        List<TradeVO> trades = getTradeList(userId, null, startDate, endDate);
        if (trades.isEmpty()) {
            Map<String, Object> emptyResult = new LinkedHashMap<>();
            emptyResult.put("dates", Collections.emptyList());
            emptyResult.put("assets", Collections.emptyList());
            return emptyResult;
        }

        // 2. 按日期排序
        trades.sort(Comparator.comparing(TradeVO::getTradeDate));

        // 3. 获取所有涉及的股票
        Set<String> stockCodes = trades.stream()
                .map(TradeVO::getStockCode)
                .collect(Collectors.toSet());

        // 4. 确定日期范围
        LocalDate firstTradeDate = trades.get(0).getTradeDate();
        LocalDate lastTradeDate = trades.get(trades.size() - 1).getTradeDate();
        // 如果有传入 endDate 且晚于最后交易日期，使用传入的；否则延伸到今天
        LocalDate endDateActual = endDate != null ? endDate : LocalDate.now();
        if (endDateActual.isBefore(lastTradeDate)) {
            endDateActual = lastTradeDate;
        }

        // 5. 获取每只股票的每日收盘价
        Map<String, Map<LocalDate, BigDecimal>> stockDailyPrices = new HashMap<>();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyyMMdd");

        for (String stockCode : stockCodes) {
            Map<LocalDate, BigDecimal> dailyPrices = new HashMap<>();
            try {
                var result = tradeFeignClient.getStockDaily(stockCode,
                        firstTradeDate.format(fmt), endDateActual.format(fmt));
                if (result != null && result.getCode() == 200 && result.getData() != null) {
                    for (Map<String, Object> item : result.getData()) {
                        String tradeDateStr = (String) item.get("tradeDate");
                        Object closePriceObj = item.get("closePrice");
                        if (tradeDateStr != null && closePriceObj != null) {
                            LocalDate date = LocalDate.parse(tradeDateStr, fmt);
                            BigDecimal closePrice = new BigDecimal(closePriceObj.toString());
                            dailyPrices.put(date, closePrice);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("获取股票 {} 日线数据失败: {}", stockCode, e.getMessage());
            }
            stockDailyPrices.put(stockCode, dailyPrices);
        }

        // 6. 按日期遍历，计算每日持仓市值
        Map<String, Integer> holdings = new HashMap<>();  // stockCode -> quantity
        Map<String, BigDecimal> avgCosts = new HashMap<>(); // stockCode -> avgCost

        // 按交易日期分组
        Map<LocalDate, List<TradeVO>> tradesByDate = trades.stream()
                .collect(Collectors.groupingBy(TradeVO::getTradeDate, TreeMap::new, Collectors.toList()));

        List<String> dates = new ArrayList<>();
        List<BigDecimal> assets = new ArrayList<>();

        LocalDate currentDate = firstTradeDate;
        while (!currentDate.isAfter(endDateActual)) {
            // 处理当天的交易
            List<TradeVO> todayTrades = tradesByDate.get(currentDate);
            if (todayTrades != null) {
                for (TradeVO trade : todayTrades) {
                    String stockCode = trade.getStockCode();
                    int quantity = trade.getTradeQuantity();
                    BigDecimal price = trade.getTradePrice();

                    if (trade.getTradeType() == 1) {
                        // 买入：更新持仓和加权平均成本
                        int oldQty = holdings.getOrDefault(stockCode, 0);
                        BigDecimal oldCost = avgCosts.getOrDefault(stockCode, BigDecimal.ZERO);
                        int newQty = oldQty + quantity;
                        BigDecimal newCost = oldCost.multiply(BigDecimal.valueOf(oldQty))
                                .add(price.multiply(BigDecimal.valueOf(quantity)))
                                .divide(BigDecimal.valueOf(Math.max(newQty, 1)), 4, RoundingMode.HALF_UP);
                        holdings.put(stockCode, newQty);
                        avgCosts.put(stockCode, newCost);
                    } else {
                        // 卖出：减少持仓（成本不变）
                        int oldQty = holdings.getOrDefault(stockCode, 0);
                        holdings.put(stockCode, Math.max(0, oldQty - quantity));
                    }
                }
            }

            // 计算当日持仓市值
            BigDecimal dailyAsset = BigDecimal.ZERO;
            boolean hasHolding = false;
            for (Map.Entry<String, Integer> entry : holdings.entrySet()) {
                String stockCode = entry.getKey();
                int quantity = entry.getValue();
                if (quantity <= 0) continue;

                hasHolding = true;
                // 获取当日收盘价，如果没有行情数据则使用平均成本
                BigDecimal closePrice = stockDailyPrices
                        .getOrDefault(stockCode, Collections.emptyMap())
                        .getOrDefault(currentDate, avgCosts.getOrDefault(stockCode, BigDecimal.ZERO));
                dailyAsset = dailyAsset.add(closePrice.multiply(BigDecimal.valueOf(quantity)));
            }

            // 只在有交易或持仓时记录
            if (todayTrades != null || hasHolding) {
                dates.add(currentDate.toString());
                assets.add(dailyAsset.setScale(2, RoundingMode.HALF_UP));
            }

            currentDate = currentDate.plusDays(1);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dates", dates);
        result.put("assets", assets);
        return result;
    }
}
