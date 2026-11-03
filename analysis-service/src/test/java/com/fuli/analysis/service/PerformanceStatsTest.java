package com.fuli.analysis.service;

import com.fuli.analysis.vo.PerformanceStatsVO;
import com.fuli.common.api.Result;
import com.fuli.common.api.dto.TradeQueryDTO;
import com.fuli.common.api.feign.DataFeignClient;
import com.fuli.common.api.feign.TradeFeignClient;
import com.fuli.common.api.vo.TradeVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PerformanceStatsTest {

    @Mock
    private TradeFeignClient tradeFeignClient;

    @Mock
    private DataFeignClient dataFeignClient;

    @InjectMocks
    private AnalysisService analysisService;

    @Test
    void testPerformanceStatsCalculation() {
        Long userId = 100L;

        List<TradeVO> trades = new ArrayList<>();

        // Buy 1: 600519, 100 shares @ 1000 on 2026-01-01
        TradeVO buy1 = new TradeVO();
        buy1.setId(1L);
        buy1.setStockCode("600519.SH");
        buy1.setStockName("贵州茅台");
        buy1.setTradeType(1);
        buy1.setTradeQuantity(100);
        buy1.setTradePrice(new BigDecimal("1000"));
        buy1.setTradeAmount(new BigDecimal("100000"));
        buy1.setTradeDate(LocalDate.of(2026, 1, 1));
        trades.add(buy1);

        // Sell 1: 600519, 100 shares @ 1100 on 2026-01-05 (Profit +10000, +10%, held 4 days)
        TradeVO sell1 = new TradeVO();
        sell1.setId(2L);
        sell1.setStockCode("600519.SH");
        sell1.setStockName("贵州茅台");
        sell1.setTradeType(2);
        sell1.setTradeQuantity(100);
        sell1.setTradePrice(new BigDecimal("1100"));
        sell1.setTradeAmount(new BigDecimal("110000"));
        sell1.setProfitLoss(new BigDecimal("10000"));
        sell1.setProfitLossRatio(new BigDecimal("0.10"));
        sell1.setTradeDate(LocalDate.of(2026, 1, 5));
        trades.add(sell1);

        // Buy 2: 000001, 500 shares @ 10 on 2026-01-10
        TradeVO buy2 = new TradeVO();
        buy2.setId(3L);
        buy2.setStockCode("000001.SZ");
        buy2.setStockName("平安银行");
        buy2.setTradeType(1);
        buy2.setTradeQuantity(500);
        buy2.setTradePrice(new BigDecimal("10"));
        buy2.setTradeAmount(new BigDecimal("5000"));
        buy2.setTradeDate(LocalDate.of(2026, 1, 10));
        trades.add(buy2);

        // Sell 2: 000001, 500 shares @ 9 on 2026-01-12 (Loss -500, -10%, held 2 days)
        TradeVO sell2 = new TradeVO();
        sell2.setId(4L);
        sell2.setStockCode("000001.SZ");
        sell2.setStockName("平安银行");
        sell2.setTradeType(2);
        sell2.setTradeQuantity(500);
        sell2.setTradePrice(new BigDecimal("9"));
        sell2.setTradeAmount(new BigDecimal("4500"));
        sell2.setProfitLoss(new BigDecimal("-500"));
        sell2.setProfitLossRatio(new BigDecimal("-0.10"));
        sell2.setTradeDate(LocalDate.of(2026, 1, 12));
        trades.add(sell2);

        when(tradeFeignClient.queryByCondition(any(TradeQueryDTO.class))).thenReturn(Result.success(trades));

        PerformanceStatsVO stats = analysisService.getPerformanceStats(userId, null, null);

        assertNotNull(stats);
        assertEquals(4, stats.getTotalTrades());
        assertEquals(2, stats.getBuyCount());
        assertEquals(2, stats.getSellCount());
        assertEquals(1, stats.getWinCount());
        assertEquals(1, stats.getLossCount());
        assertEquals(new BigDecimal("50.00"), stats.getWinRate());
        assertEquals(new BigDecimal("9500.00"), stats.getTotalProfitLoss().setScale(2));
        assertEquals(1, stats.getMaxConsecutiveWins());
        assertEquals(1, stats.getMaxConsecutiveLosses());
        assertEquals(3.0, stats.getAvgHoldingDays()); // (4 days + 2 days) / 2 = 3.0 days

        assertNotNull(stats.getBestTrade());
        assertEquals("600519.SH", stats.getBestTrade().getStockCode());
        assertEquals(new BigDecimal("10000"), stats.getBestTrade().getProfitLoss());

        assertNotNull(stats.getWorstTrade());
        assertEquals("000001.SZ", stats.getWorstTrade().getStockCode());
        assertEquals(new BigDecimal("-500"), stats.getWorstTrade().getProfitLoss());

        assertFalse(stats.getProfitDistribution().isEmpty());
        assertFalse(stats.getHoldingDistribution().isEmpty());
    }
}
