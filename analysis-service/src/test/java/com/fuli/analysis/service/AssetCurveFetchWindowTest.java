package com.fuli.analysis.service;

import com.fuli.common.api.Result;
import com.fuli.common.api.feign.AuthFeignClient;
import com.fuli.common.api.feign.DataFeignClient;
import com.fuli.common.api.feign.TradeFeignClient;
import com.fuli.common.api.vo.TradeVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 资产曲线取数窗口：每只票只按自己的持仓区间拉日线，不再「全账户最早交易日 → 今天」。
 *
 * <p>两条断言缺一不可：①窗口确实收窄了（否则优化没发生）；②曲线与「全区间拉取」逐点一致
 * （否则就是拿用户看到的账换性能）。第②条把改动前的实现照搬进测试当参照物。
 */
@ExtendWith(MockitoExtension.class)
class AssetCurveFetchWindowTest {
    private static final Long USER = 31L;
    private static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;

    @Mock TradeFeignClient tradeFeignClient;
    @Mock DataFeignClient dataFeignClient;
    @Mock AuthFeignClient authFeignClient;
    @InjectMocks AnalysisService analysisService;

    @Test
    void eachStockIsFetchedOnlyForItsOwnHoldingWindow() {
        stub(this::defaultTrades);

        analysisService.getAssetCurve(USER, null, LocalDate.of(2026, 2, 10));

        List<String[]> calls = capturedRanges();
        assertEquals(2, calls.size(), calls.toString());
        // 2026-01-06 已清仓 → 窗口止于末笔交易，不该再拉到今天
        assertArrayEquals(new String[]{"600519.SH", "20260105", "20260106"}, calls.get(0));
        // 仍在持有 → 窗口延伸到 endDate
        assertArrayEquals(new String[]{"000001.SZ", "20260202", "20260210"}, calls.get(1));
    }

    @Test
    void curveStaysBitIdenticalToFetchingTheWholeRange() {
        List<TradeVO> fixture = defaultTrades();
        stub(() -> fixture);

        Map<String, Object> actual = analysisService.getAssetCurve(USER, null, LocalDate.of(2026, 2, 10));
        Map<String, Object> legacy = legacyCurve(fixture, LocalDate.of(2026, 2, 10));

        assertEquals(legacy.get("dates"), actual.get("dates"));
        assertEquals(legacy.get("assets"), actual.get("assets"));
        assertFalse(((List<?>) actual.get("dates")).isEmpty(), "空曲线会让这条对拍失去意义");
    }

    /** 600519.SH 在 1 月初买入后清仓；000001.SZ 在 2 月买入并持有到 endDate。 */
    private List<TradeVO> defaultTrades() {
        List<TradeVO> trades = new ArrayList<>();
        trades.add(trade("600519.SH", 1, "2026-01-05", 100, "10.00"));
        trades.add(trade("600519.SH", 2, "2026-01-06", 100, "11.00"));
        trades.add(trade("000001.SZ", 1, "2026-02-02", 200, "5.00"));
        return trades;
    }

    private TradeVO trade(String code, int type, String date, int quantity, String price) {
        TradeVO vo = new TradeVO();
        vo.setStockCode(code);
        vo.setTradeType(type);
        vo.setTradeDate(LocalDate.parse(date));
        vo.setTradeQuantity(quantity);
        vo.setTradePrice(new BigDecimal(price));
        vo.setTradeAmount(new BigDecimal(price).multiply(BigDecimal.valueOf(quantity)));
        vo.setTotalCost(new BigDecimal(price).multiply(BigDecimal.valueOf(quantity)));
        return vo;
    }

    /** 行情 mock：按请求区间返回「工作日一根」，收盘价 = 基础价 + 日号，便于逐点比较。 */
    private void stub(java.util.function.Supplier<List<TradeVO>> trades) {
        when(tradeFeignClient.queryByCondition(any())).thenAnswer(inv -> Result.success(trades.get()));
        when(authFeignClient.getUserInitialCash(any())).thenReturn(Result.success(new BigDecimal("100000.00")));
        when(dataFeignClient.getDailyData(anyString(), anyString(), anyString(), isNull())).thenAnswer(inv -> {
            String code = inv.getArgument(0);
            LocalDate from = LocalDate.parse(inv.getArgument(1, String.class), BASIC);
            LocalDate to = LocalDate.parse(inv.getArgument(2, String.class), BASIC);
            BigDecimal base = code.startsWith("600519") ? new BigDecimal("10") : new BigDecimal("5");
            List<Map<String, Object>> rows = new ArrayList<>();
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                if (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) continue;
                Map<String, Object> row = new HashMap<>();
                row.put("tradeDate", d.format(BASIC));
                row.put("closePrice", base.add(BigDecimal.valueOf(d.getDayOfMonth())));
                rows.add(row);
            }
            return rows;
        });
    }

    private List<String[]> capturedRanges() {
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> from = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        verify(dataFeignClient, times(2)).getDailyData(code.capture(), from.capture(), to.capture(), isNull());
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < code.getAllValues().size(); i++) {
            out.add(new String[]{code.getAllValues().get(i), from.getAllValues().get(i), to.getAllValues().get(i)});
        }
        return out;
    }

    /** 改动前的实现，一字未改（除把取数抽成 directCall 便于在测试里复用）。 */
    private Map<String, Object> legacyCurve(List<TradeVO> trades, LocalDate endDate) {
        if (trades.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("dates", List.of());
            empty.put("assets", List.of());
            return empty;
        }
        trades.sort(Comparator.comparing(TradeVO::getTradeDate));
        Set<String> stockCodes = trades.stream().map(TradeVO::getStockCode).collect(Collectors.toSet());
        LocalDate firstTradeDate = trades.get(0).getTradeDate();
        LocalDate lastTradeDate = trades.get(trades.size() - 1).getTradeDate();
        LocalDate endDateActual = endDate != null ? endDate : LocalDate.now();
        if (endDateActual.isBefore(lastTradeDate)) endDateActual = lastTradeDate;

        Map<String, Map<LocalDate, BigDecimal>> stockDailyPrices = new HashMap<>();
        for (String stockCode : stockCodes) {
            Map<LocalDate, BigDecimal> dailyPrices = new HashMap<>();
            for (Map<String, Object> item : dataFeignClient.getDailyData(stockCode,
                    firstTradeDate.format(BASIC), endDateActual.format(BASIC), null)) {
                String tradeDateStr = (String) item.get("tradeDate");
                Object closePriceObj = item.get("closePrice");
                if (tradeDateStr != null && closePriceObj != null) {
                    dailyPrices.put(LocalDate.parse(tradeDateStr, BASIC), new BigDecimal(closePriceObj.toString()));
                }
            }
            stockDailyPrices.put(stockCode, dailyPrices);
        }

        Map<String, Integer> holdings = new HashMap<>();
        Map<String, BigDecimal> avgCosts = new HashMap<>();
        BigDecimal cash = new BigDecimal("100000.00");
        Map<LocalDate, List<TradeVO>> tradesByDate = trades.stream()
                .collect(Collectors.groupingBy(TradeVO::getTradeDate, TreeMap::new, Collectors.toList()));

        List<String> dates = new ArrayList<>();
        List<BigDecimal> assets = new ArrayList<>();
        LocalDate currentDate = firstTradeDate;
        while (!currentDate.isAfter(endDateActual)) {
            List<TradeVO> todayTrades = tradesByDate.get(currentDate);
            if (todayTrades != null) {
                for (TradeVO trade : todayTrades) {
                    String stockCode = trade.getStockCode();
                    int quantity = trade.getTradeQuantity();
                    BigDecimal price = trade.getTradePrice();
                    if (trade.getTradeType() == 1) {
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
                        BigDecimal income = trade.getTotalCost() != null
                                ? trade.getTotalCost() : price.multiply(BigDecimal.valueOf(quantity));
                        cash = cash.add(income);
                        holdings.put(stockCode, Math.max(0, holdings.getOrDefault(stockCode, 0) - quantity));
                    }
                }
            }
            BigDecimal dailyAsset = cash;
            boolean hasHolding = false;
            for (Map.Entry<String, Integer> entry : holdings.entrySet()) {
                if (entry.getValue() <= 0) continue;
                hasHolding = true;
                BigDecimal closePrice = stockDailyPrices
                        .getOrDefault(entry.getKey(), Map.of())
                        .getOrDefault(currentDate, avgCosts.getOrDefault(entry.getKey(), BigDecimal.ZERO));
                dailyAsset = dailyAsset.add(closePrice.multiply(BigDecimal.valueOf(entry.getValue())));
            }
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
