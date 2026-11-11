package com.fuli.analysis.service;

import com.fuli.common.api.Result;
import com.fuli.common.api.dto.TradeQueryDTO;
import com.fuli.common.api.feign.AuthFeignClient;
import com.fuli.common.api.feign.DataFeignClient;
import com.fuli.common.api.feign.TradeFeignClient;
import com.fuli.common.api.vo.MonthlyProfitVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 月度盈亏改为「下沉到 trade-service 聚合」后的边界：只该转一次聚合请求，
 * 不再走「拉全量交易列表」的旧路径；失败时与其它分析接口一致降级为空列表。
 */
@ExtendWith(MockitoExtension.class)
class MonthlyProfitAggregationTest {

    @Mock TradeFeignClient tradeFeignClient;
    @Mock DataFeignClient dataFeignClient;
    @Mock AuthFeignClient authFeignClient;
    @InjectMocks AnalysisService analysisService;

    @Test
    void delegatesToTheAggregateEndpointInsteadOfPullingEveryTrade() {
        MonthlyProfitVO january = new MonthlyProfitVO();
        january.setMonth("2026-01");
        january.setTradeCount(2);
        january.setProfitLoss(new BigDecimal("30.0000"));
        january.setWinRate(new BigDecimal("0.5000"));
        when(tradeFeignClient.getMonthlyProfit(any())).thenReturn(Result.success(List.of(january)));

        var result = analysisService.getMonthlyProfit(1L, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));

        assertEquals(List.of(january), result);
        ArgumentCaptor<TradeQueryDTO> sent = ArgumentCaptor.forClass(TradeQueryDTO.class);
        verify(tradeFeignClient).getMonthlyProfit(sent.capture());
        assertEquals(1L, sent.getValue().getUserId());
        assertEquals(LocalDate.of(2026, 1, 1), sent.getValue().getStartDate());
        assertEquals(LocalDate.of(2026, 1, 31), sent.getValue().getEndDate());
        // 旧的「拉全量交易再分组」路径不应再被走到：区间一长就是几千行 Feign 载荷。
        verify(tradeFeignClient, never()).queryByCondition(any());
    }

    @Test
    void upstreamFailureDegradesToAnEmptyListInstead() {
        when(tradeFeignClient.getMonthlyProfit(any())).thenThrow(new IllegalStateException("trade-service down"));
        assertTrue(analysisService.getMonthlyProfit(1L, null, null).isEmpty());
    }

    @Test
    void nonOkEnvelopeAlsoDegradesToAnEmptyList() {
        when(tradeFeignClient.getMonthlyProfit(any())).thenReturn(Result.error("查询失败"));
        assertTrue(analysisService.getMonthlyProfit(1L, null, null).isEmpty());
    }
}
