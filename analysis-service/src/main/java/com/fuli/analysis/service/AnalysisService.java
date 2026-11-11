package com.fuli.analysis.service;

import com.fuli.common.api.dto.TradeQueryDTO;
import com.fuli.common.api.feign.AuthFeignClient;
import com.fuli.common.api.feign.DataFeignClient;
import com.fuli.common.api.feign.TradeFeignClient;
import com.fuli.common.api.vo.MonthlyProfitVO;
import com.fuli.common.api.vo.StatisticsVO;
import com.fuli.common.api.vo.TradeVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
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
    private final DataFeignClient dataFeignClient;
    private final AuthFeignClient authFeignClient;

    public AnalysisService(TradeFeignClient tradeFeignClient, DataFeignClient dataFeignClient,
                           AuthFeignClient authFeignClient) {
        this.tradeFeignClient = tradeFeignClient;
        this.dataFeignClient = dataFeignClient;
        this.authFeignClient = authFeignClient;
    }

    @Cacheable(cacheNames = "statistics", key = "#userId + ':' + #stockCode + ':' + #startDate + ':' + #endDate", unless = "#result == null")
    public StatisticsVO getStatistics(Long userId, String stockCode, LocalDate startDate, LocalDate endDate) {
        TradeQueryDTO queryDTO = new TradeQueryDTO();
        queryDTO.setUserId(userId);
        queryDTO.setStockCode(stockCode);
        queryDTO.setStartDate(startDate);
        queryDTO.setEndDate(endDate);

        try {
            var result = tradeFeignClient.getStatistics(queryDTO);
            if (result != null && result.getCode() == 200) {
                return result.getData();
            }
        } catch (Exception e) {
            log.warn("获取统计失败，降级返回空: userId={}, error={}", userId, e.getMessage());
        }
        return null;
    }

    public List<TradeVO> getTradeList(Long userId, String stockCode, LocalDate startDate, LocalDate endDate) {
        TradeQueryDTO queryDTO = new TradeQueryDTO();
        queryDTO.setUserId(userId);
        queryDTO.setStockCode(stockCode);
        queryDTO.setStartDate(startDate);
        queryDTO.setEndDate(endDate);

        try {
            var result = tradeFeignClient.queryByCondition(queryDTO);
            if (result != null && result.getCode() == 200) {
                return result.getData();
            }
        } catch (Exception e) {
            log.warn("查询交易列表失败，降级返回空: userId={}, error={}", userId, e.getMessage());
        }
        return new ArrayList<>();
    }

    /**
     * 月度盈亏：由 trade-service 的 {@code GROUP BY} 聚合端点直接给出，不再拉全量交易在内存里分组。
     *
     * <p>下沉的收益是量级差：一次区间查询返回「N 笔交易」（可能几千行 Feign 载荷），
     * 而结果只有「每月一行」（最多几十行）。胜率仍在 trade-service 用 BigDecimal 复算，
     * 避免各数据库除法精度/舍入规则不同导致同一账户改前改后差一位。
     *
     * <p>失败时与其它分析接口一样降级为空列表（前端显示「暂无月度盈亏数据」），不抛给用户。
     * <b>部署顺序</b>：trade-service 必须先上（新增 {@code /trade/internal/monthly-profit}），
     * 否则本服务在灰度期间会拿到 404 并降级为空。
     */
    @Cacheable(cacheNames = "monthlyProfit", key = "#userId + ':' + #startDate + ':' + #endDate")
    public List<MonthlyProfitVO> getMonthlyProfit(Long userId, LocalDate startDate, LocalDate endDate) {
        TradeQueryDTO queryDTO = new TradeQueryDTO();
        queryDTO.setUserId(userId);
        queryDTO.setStartDate(startDate);
        queryDTO.setEndDate(endDate);

        try {
            var result = tradeFeignClient.getMonthlyProfit(queryDTO);
            if (result != null && result.getCode() == 200 && result.getData() != null) {
                return result.getData();
            }
        } catch (Exception e) {
            log.warn("获取月度盈亏失败，降级返回空: userId={}, error={}", userId, e.getMessage());
        }
        return new ArrayList<>();
    }

    /** 纯计算：由已拉取的交易列表推导月度盈亏，供 getPerformanceStats 复用，避免二次全量拉取。 */
    private List<MonthlyProfitVO> computeMonthlyProfit(List<TradeVO> trades) {
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
    @Cacheable(cacheNames = "assetCurve", key = "#userId + ':' + #startDate + ':' + #endDate")
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

        // 3. 确定日期范围
        LocalDate firstTradeDate = trades.get(0).getTradeDate();
        LocalDate lastTradeDate = trades.get(trades.size() - 1).getTradeDate();
        // 如果有传入 endDate 且晚于最后交易日期，使用传入的；否则延伸到今天
        LocalDate endDateActual = endDate != null ? endDate : LocalDate.now();
        if (endDateActual.isBefore(lastTradeDate)) {
            endDateActual = lastTradeDate;
        }

        // 4. 获取每只股票的每日收盘价：区间按该股自己的持仓窗口收窄（见 priceWindows）
        Map<String, Map<LocalDate, BigDecimal>> stockDailyPrices = new HashMap<>();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyyMMdd");

        for (Map.Entry<String, PriceWindow> entry : priceWindows(trades, endDateActual).entrySet()) {
            String stockCode = entry.getKey();
            PriceWindow window = entry.getValue();
            Map<LocalDate, BigDecimal> dailyPrices = new HashMap<>();
            try {
                // 通过 data-service 获取日线数据。
                // 第 4 个参数 limit 传 null：这里要的是窗口内整段区间，截断会让后续的持仓估值缺日。
                // 与练习模块同理，见 KlineQueryService.query 的注释。
                List<Map<String, Object>> result = dataFeignClient.getDailyData(stockCode,
                        window.from().format(fmt), window.to().format(fmt), null);
                if (result != null) {
                    for (Map<String, Object> item : result) {
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

        // 6. 按日期遍历，计算每日总资产（现金 + 持仓市值）
        Map<String, Integer> holdings = new HashMap<>();  // stockCode -> quantity
        Map<String, BigDecimal> avgCosts = new HashMap<>(); // stockCode -> avgCost
        BigDecimal cash = getInitialCash(userId); // 现金余额（从初始资金起算）

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
                        // 买入：扣现金 + 更新持仓和加权平均成本
                        BigDecimal cost = trade.getTotalCost() != null
                                ? trade.getTotalCost() : price.multiply(BigDecimal.valueOf(quantity));
                        cash = cash.subtract(cost);
                        int oldQty = holdings.getOrDefault(stockCode, 0);
                        BigDecimal oldCost = avgCosts.getOrDefault(stockCode, BigDecimal.ZERO);
                        int newQty = oldQty + quantity;
                        BigDecimal newCost = oldCost.multiply(BigDecimal.valueOf(oldQty))
                                .add(price.multiply(BigDecimal.valueOf(quantity)))
                                .divide(BigDecimal.valueOf(Math.max(newQty, 1)), 4, RoundingMode.HALF_UP);
                        holdings.put(stockCode, newQty);
                        avgCosts.put(stockCode, newCost);
                    } else {
                        // 卖出：加现金 + 减少持仓（成本不变）
                        BigDecimal income = trade.getTotalCost() != null
                                ? trade.getTotalCost() : price.multiply(BigDecimal.valueOf(quantity));
                        cash = cash.add(income);
                        int oldQty = holdings.getOrDefault(stockCode, 0);
                        holdings.put(stockCode, Math.max(0, oldQty - quantity));
                    }
                }
            }

            // 计算当日总资产 = 现金 + 持仓市值
            BigDecimal dailyAsset = cash;
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

    /**
     * 每只股票真正需要行情的日期窗口。
     *
     * <p>原先所有股票都按「全账户最早交易日 ~ 今天」各拉一次日线：一只 2020 年就清仓的票，
     * 到 2026 年仍在每次打开仪表盘时把 6 年日线重新拉一遍，而它的持仓量早就是 0——
     * 估值只在 {@code quantity > 0} 的日期才读价格，其余全是白拉的载荷。
     *
     * <p>窗口取「该股首笔交易 ~ 该股末笔交易」；末笔之后仍持有（净买入为正）的延伸到
     * {@code endDateActual}。这个窗口覆盖了所有 {@code quantity > 0} 的日期，所以曲线逐点不变
     * （有对拍测试钉住）。注意入参 trades 必须已按交易日升序，否则「首笔/末笔」不成立。
     */
    private static Map<String, PriceWindow> priceWindows(List<TradeVO> trades, LocalDate endDateActual) {
        Map<String, LocalDate[]> span = new LinkedHashMap<>();
        Map<String, Long> netQuantity = new HashMap<>();
        for (TradeVO trade : trades) {
            String code = trade.getStockCode();
            LocalDate date = trade.getTradeDate();
            if (code == null || date == null) continue;
            LocalDate[] range = span.get(code);
            if (range == null) {
                span.put(code, new LocalDate[]{date, date});
            } else {
                range[1] = date;
            }
            long quantity = trade.getTradeQuantity() != null ? trade.getTradeQuantity() : 0;
            boolean buy = trade.getTradeType() != null && trade.getTradeType() == 1;
            netQuantity.merge(code, buy ? quantity : -quantity, Long::sum);
        }
        // 按首次出现顺序返回，取数顺序与交易顺序一致（日志与测试都好读）
        Map<String, PriceWindow> windows = new LinkedHashMap<>();
        span.forEach((code, range) -> windows.put(code,
                new PriceWindow(range[0], netQuantity.getOrDefault(code, 0L) > 0 ? endDateActual : range[1])));
        return windows;
    }

    /** 某只股票需要行情的日期闭区间（含两端）。 */
    private record PriceWindow(LocalDate from, LocalDate to) {}

    /** 获取初始资金（资产曲线起算基准），失败时回退到注册默认值。 */
    private BigDecimal getInitialCash(Long userId) {
        try {
            var result = authFeignClient.getUserInitialCash(userId);
            if (result != null && result.getCode() == 200 && result.getData() != null) {
                return result.getData();
            }
        } catch (Exception e) {
            log.warn("获取初始资金失败，回退到默认 200000: userId={}, error={}", userId, e.getMessage());
        }
        return new BigDecimal("200000.00");
    }

    /**
     * 高级绩效统计分析
     */
    @Cacheable(cacheNames = "performance", key = "#userId + ':' + #startDate + ':' + #endDate")
    public com.fuli.analysis.vo.PerformanceStatsVO getPerformanceStats(Long userId, LocalDate startDate, LocalDate endDate) {
        List<TradeVO> allTrades = getTradeList(userId, null, startDate, endDate);
        if (allTrades == null || allTrades.isEmpty()) {
            return com.fuli.analysis.vo.PerformanceStatsVO.builder()
                    .totalTrades(0)
                    .buyCount(0)
                    .sellCount(0)
                    .winCount(0)
                    .lossCount(0)
                    .breakEvenCount(0)
                    .winRate(BigDecimal.ZERO)
                    .totalProfitLoss(BigDecimal.ZERO)
                    .totalProfitAmount(BigDecimal.ZERO)
                    .totalLossAmount(BigDecimal.ZERO)
                    .profitLossRatio(BigDecimal.ZERO)
                    .profitFactor(BigDecimal.ZERO)
                    .avgWinAmount(BigDecimal.ZERO)
                    .avgLossAmount(BigDecimal.ZERO)
                    .avgTradeProfit(BigDecimal.ZERO)
                    .maxConsecutiveWins(0)
                    .maxConsecutiveLosses(0)
                    .avgHoldingDays(0.0)
                    .profitDistribution(Collections.emptyList())
                    .holdingDistribution(Collections.emptyList())
                    .topProfitStocks(Collections.emptyList())
                    .topLossStocks(Collections.emptyList())
                    .monthlyStats(Collections.emptyList())
                    .build();
        }

        // 按日期与ID升序排列
        allTrades.sort(Comparator.comparing(TradeVO::getTradeDate).thenComparing(TradeVO::getId));

        int buyCount = 0;
        int sellCount = 0;
        int winCount = 0;
        int lossCount = 0;
        int breakEvenCount = 0;

        BigDecimal totalProfitLoss = BigDecimal.ZERO;
        BigDecimal totalProfitAmount = BigDecimal.ZERO;
        BigDecimal totalLossAmount = BigDecimal.ZERO;

        int currentWins = 0;
        int maxWins = 0;
        int currentLosses = 0;
        int maxLosses = 0;

        TradeVO bestTrade = null;
        TradeVO worstTrade = null;

        Map<String, List<BuyLot>> buyLotsMap = new HashMap<>();
        List<Long> holdingDaysList = new ArrayList<>();

        int countBigWin = 0;
        int countMidWin = 0;
        int countSmallWin = 0;
        int countSmallLoss = 0;
        int countMidLoss = 0;
        int countBigLoss = 0;

        Map<String, StockAgg> stockAggMap = new HashMap<>();

        for (TradeVO t : allTrades) {
            String code = t.getStockCode();
            String name = t.getStockName();
            LocalDate date = t.getTradeDate();
            Integer type = t.getTradeType();

            StockAgg agg = stockAggMap.computeIfAbsent(code, k -> new StockAgg(code, name));

            if (type != null && type == 1) { // 买入
                buyCount++;
                agg.totalTrades++;
                buyLotsMap.computeIfAbsent(code, k -> new ArrayList<>())
                        .add(new BuyLot(date, t.getTradeQuantity() != null ? t.getTradeQuantity() : 0));
            } else if (type != null && type == 2) { // 卖出
                sellCount++;
                agg.totalTrades++;
                agg.sellTrades++;
                BigDecimal pnl = t.getProfitLoss() != null ? t.getProfitLoss() : BigDecimal.ZERO;
                totalProfitLoss = totalProfitLoss.add(pnl);
                agg.totalPnl = agg.totalPnl.add(pnl);

                if (pnl.compareTo(BigDecimal.ZERO) > 0) {
                    winCount++;
                    agg.winTrades++;
                    totalProfitAmount = totalProfitAmount.add(pnl);
                    currentWins++;
                    currentLosses = 0;
                    if (currentWins > maxWins) maxWins = currentWins;
                } else if (pnl.compareTo(BigDecimal.ZERO) < 0) {
                    lossCount++;
                    totalLossAmount = totalLossAmount.add(pnl.abs());
                    currentLosses++;
                    currentWins = 0;
                    if (currentLosses > maxLosses) maxLosses = currentLosses;
                } else {
                    breakEvenCount++;
                    currentWins = 0;
                    currentLosses = 0;
                }

                if (bestTrade == null || (t.getProfitLoss() != null && (bestTrade.getProfitLoss() == null || t.getProfitLoss().compareTo(bestTrade.getProfitLoss()) > 0))) {
                    bestTrade = t;
                }
                if (worstTrade == null || (t.getProfitLoss() != null && (worstTrade.getProfitLoss() == null || t.getProfitLoss().compareTo(worstTrade.getProfitLoss()) < 0))) {
                    worstTrade = t;
                }

                // 收益率分布计算
                double ratioPct = 0.0;
                if (t.getProfitLossRatio() != null) {
                    double rVal = t.getProfitLossRatio().doubleValue();
                    ratioPct = Math.abs(rVal) <= 1.0 ? rVal * 100 : rVal;
                } else if (t.getTradeAmount() != null && t.getTradeAmount().compareTo(BigDecimal.ZERO) > 0) {
                    ratioPct = pnl.divide(t.getTradeAmount(), 4, RoundingMode.HALF_UP).doubleValue() * 100;
                }

                if (ratioPct > 10.0) countBigWin++;
                else if (ratioPct >= 3.0) countMidWin++;
                else if (ratioPct >= 0.0) countSmallWin++;
                else if (ratioPct >= -3.0) countSmallLoss++;
                else if (ratioPct >= -7.0) countMidLoss++;
                else countBigLoss++;

                // 先进先出持仓天数计算
                int sellQty = t.getTradeQuantity() != null ? t.getTradeQuantity() : 0;
                List<BuyLot> lots = buyLotsMap.get(code);
                if (lots != null) {
                    while (sellQty > 0 && !lots.isEmpty()) {
                        BuyLot first = lots.get(0);
                        long days = java.time.temporal.ChronoUnit.DAYS.between(first.date, date);
                        if (days < 0) days = 0;
                        holdingDaysList.add(days);

                        if (first.quantity <= sellQty) {
                            sellQty -= first.quantity;
                            lots.remove(0);
                        } else {
                            first.quantity -= sellQty;
                            sellQty = 0;
                        }
                    }
                }
            }
        }

        BigDecimal winRate = sellCount > 0
                ? BigDecimal.valueOf(winCount).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(sellCount), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        BigDecimal avgWinAmount = winCount > 0
                ? totalProfitAmount.divide(BigDecimal.valueOf(winCount), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        BigDecimal avgLossAmount = lossCount > 0
                ? totalLossAmount.divide(BigDecimal.valueOf(lossCount), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        BigDecimal profitLossRatio = avgLossAmount.compareTo(BigDecimal.ZERO) > 0
                ? avgWinAmount.divide(avgLossAmount, 2, RoundingMode.HALF_UP)
                : avgWinAmount;

        BigDecimal profitFactor = totalLossAmount.compareTo(BigDecimal.ZERO) > 0
                ? totalProfitAmount.divide(totalLossAmount, 2, RoundingMode.HALF_UP)
                : totalProfitAmount;

        BigDecimal avgTradeProfit = sellCount > 0
                ? totalProfitLoss.divide(BigDecimal.valueOf(sellCount), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        double avgHoldingDays = holdingDaysList.isEmpty() ? 0.0
                : holdingDaysList.stream().mapToLong(Long::longValue).average().orElse(0.0);
        avgHoldingDays = Math.round(avgHoldingDays * 10.0) / 10.0;

        // 收益率区间列表
        List<com.fuli.analysis.vo.PerformanceStatsVO.DistributionItemVO> profitDistList = new ArrayList<>();
        int validSellCount = Math.max(sellCount, 1);
        profitDistList.add(new com.fuli.analysis.vo.PerformanceStatsVO.DistributionItemVO("大赚 (>10%)", countBigWin, Math.round(countBigWin * 1000.0 / validSellCount) / 10.0));
        profitDistList.add(new com.fuli.analysis.vo.PerformanceStatsVO.DistributionItemVO("中赚 (3%~10%)", countMidWin, Math.round(countMidWin * 1000.0 / validSellCount) / 10.0));
        profitDistList.add(new com.fuli.analysis.vo.PerformanceStatsVO.DistributionItemVO("小赚 (0%~3%)", countSmallWin, Math.round(countSmallWin * 1000.0 / validSellCount) / 10.0));
        profitDistList.add(new com.fuli.analysis.vo.PerformanceStatsVO.DistributionItemVO("小亏 (-3%~0%)", countSmallLoss, Math.round(countSmallLoss * 1000.0 / validSellCount) / 10.0));
        profitDistList.add(new com.fuli.analysis.vo.PerformanceStatsVO.DistributionItemVO("中亏 (-7%~-3%)", countMidLoss, Math.round(countMidLoss * 1000.0 / validSellCount) / 10.0));
        profitDistList.add(new com.fuli.analysis.vo.PerformanceStatsVO.DistributionItemVO("大亏 (<-7%)", countBigLoss, Math.round(countBigLoss * 1000.0 / validSellCount) / 10.0));

        // 持仓周期分布
        long superShort = holdingDaysList.stream().filter(d -> d <= 2).count();
        long shortTerm = holdingDaysList.stream().filter(d -> d >= 3 && d <= 5).count();
        long midTerm = holdingDaysList.stream().filter(d -> d >= 6 && d <= 20).count();
        long swingTerm = holdingDaysList.stream().filter(d -> d > 20).count();
        int totalHoldingLots = Math.max(holdingDaysList.size(), 1);

        List<com.fuli.analysis.vo.PerformanceStatsVO.DistributionItemVO> holdingDistList = new ArrayList<>();
        holdingDistList.add(new com.fuli.analysis.vo.PerformanceStatsVO.DistributionItemVO("超短线 (1~2天)", (int) superShort, Math.round(superShort * 1000.0 / totalHoldingLots) / 10.0));
        holdingDistList.add(new com.fuli.analysis.vo.PerformanceStatsVO.DistributionItemVO("短线 (3~5天)", (int) shortTerm, Math.round(shortTerm * 1000.0 / totalHoldingLots) / 10.0));
        holdingDistList.add(new com.fuli.analysis.vo.PerformanceStatsVO.DistributionItemVO("中线 (6~20天)", (int) midTerm, Math.round(midTerm * 1000.0 / totalHoldingLots) / 10.0));
        holdingDistList.add(new com.fuli.analysis.vo.PerformanceStatsVO.DistributionItemVO("波段 (20天以上)", (int) swingTerm, Math.round(swingTerm * 1000.0 / totalHoldingLots) / 10.0));

        // 个股盈亏排行
        List<com.fuli.analysis.vo.PerformanceStatsVO.StockPerformanceVO> topProfit = stockAggMap.values().stream()
                .filter(a -> a.totalPnl.compareTo(BigDecimal.ZERO) > 0)
                .sorted((a, b) -> b.totalPnl.compareTo(a.totalPnl))
                .limit(5)
                .map(StockAgg::toVO)
                .collect(Collectors.toList());

        List<com.fuli.analysis.vo.PerformanceStatsVO.StockPerformanceVO> topLoss = stockAggMap.values().stream()
                .filter(a -> a.totalPnl.compareTo(BigDecimal.ZERO) < 0)
                .sorted(Comparator.comparing(a -> a.totalPnl))
                .limit(5)
                .map(StockAgg::toVO)
                .collect(Collectors.toList());

        // 月度绩效（复用已拉取的 allTrades，避免二次全量 Feign 拉取）
        List<MonthlyProfitVO> monthlyProfits = computeMonthlyProfit(allTrades);
        List<com.fuli.analysis.vo.PerformanceStatsVO.MonthlyPerformanceVO> monthlyStats = monthlyProfits.stream()
                .map(m -> com.fuli.analysis.vo.PerformanceStatsVO.MonthlyPerformanceVO.builder()
                        .month(m.getMonth())
                        .profitLoss(m.getProfitLoss())
                        .tradeCount(m.getTradeCount())
                        .winRate(m.getWinRate() != null ? m.getWinRate().multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO)
                        .build())
                .collect(Collectors.toList());

        return com.fuli.analysis.vo.PerformanceStatsVO.builder()
                .totalTrades(allTrades.size())
                .buyCount(buyCount)
                .sellCount(sellCount)
                .winCount(winCount)
                .lossCount(lossCount)
                .breakEvenCount(breakEvenCount)
                .winRate(winRate)
                .totalProfitLoss(totalProfitLoss)
                .totalProfitAmount(totalProfitAmount)
                .totalLossAmount(totalLossAmount)
                .profitLossRatio(profitLossRatio)
                .profitFactor(profitFactor)
                .avgWinAmount(avgWinAmount)
                .avgLossAmount(avgLossAmount)
                .avgTradeProfit(avgTradeProfit)
                .maxConsecutiveWins(maxWins)
                .maxConsecutiveLosses(maxLosses)
                .avgHoldingDays(avgHoldingDays)
                .bestTrade(bestTrade != null ? toExtremeVO(bestTrade) : null)
                .worstTrade(worstTrade != null ? toExtremeVO(worstTrade) : null)
                .profitDistribution(profitDistList)
                .holdingDistribution(holdingDistList)
                .topProfitStocks(topProfit)
                .topLossStocks(topLoss)
                .monthlyStats(monthlyStats)
                .build();
    }

    private com.fuli.analysis.vo.PerformanceStatsVO.ExtremeTradeVO toExtremeVO(TradeVO t) {
        return com.fuli.analysis.vo.PerformanceStatsVO.ExtremeTradeVO.builder()
                .stockCode(t.getStockCode())
                .stockName(t.getStockName())
                .profitLoss(t.getProfitLoss())
                .profitLossRatio(t.getProfitLossRatio() != null
                        ? (Math.abs(t.getProfitLossRatio().doubleValue()) <= 1.0 ? t.getProfitLossRatio().multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP) : t.getProfitLossRatio())
                        : null)
                .tradeDate(t.getTradeDate() != null ? t.getTradeDate().toString() : null)
                .build();
    }

    private static class BuyLot {
        LocalDate date;
        int quantity;
        BuyLot(LocalDate date, int quantity) {
            this.date = date;
            this.quantity = quantity;
        }
    }

    private static class StockAgg {
        String code;
        String name;
        int totalTrades = 0;
        int sellTrades = 0;
        int winTrades = 0;
        BigDecimal totalPnl = BigDecimal.ZERO;

        StockAgg(String code, String name) {
            this.code = code;
            this.name = name;
        }

        com.fuli.analysis.vo.PerformanceStatsVO.StockPerformanceVO toVO() {
            BigDecimal wr = sellTrades > 0
                    ? BigDecimal.valueOf(winTrades).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(sellTrades), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            return com.fuli.analysis.vo.PerformanceStatsVO.StockPerformanceVO.builder()
                    .stockCode(code)
                    .stockName(name)
                    .profitLoss(totalPnl)
                    .tradeCount(totalTrades)
                    .winRate(wr)
                    .build();
        }
    }
}
