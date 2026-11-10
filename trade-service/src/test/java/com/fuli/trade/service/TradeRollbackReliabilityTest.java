package com.fuli.trade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuli.common.api.Result;
import com.fuli.common.api.dto.TradeDTO;
import com.fuli.common.api.exception.BusinessException;
import com.fuli.common.api.feign.AuthFeignClient;
import com.fuli.common.api.feign.DataFeignClient;
import com.fuli.trade.dto.CashChangeMessage;
import com.fuli.trade.entity.LocalMessage;
import com.fuli.trade.entity.PositionSummary;
import com.fuli.trade.mapper.LocalMessageMapper;
import com.fuli.trade.mapper.PositionSummaryMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"spring.cloud.nacos.config.enabled=false", "spring.cloud.nacos.discovery.enabled=false"})
@ActiveProfiles("test")
@Transactional
class TradeRollbackReliabilityTest {
    @Autowired private TradeRecordService trades;
    @Autowired private PositionSummaryMapper positions;
    @Autowired private LocalMessageMapper messages;
    @MockitoBean private AuthFeignClient auth;
    @MockitoBean private DataFeignClient data;
    @MockitoBean private ApplicationEventPublisher events;

    @org.junit.jupiter.api.BeforeEach
    void clearOutbox() {
        // 测试事务回滚会触发买入退款补偿（REQUIRES_NEW 独立提交），先清理残留消息
        messages.delete(new LambdaQueryWrapper<>());
    }

    @Test
    void deletingLastBuyRestoresEarlierAverageCost() {
        readyMarket("10");
        trades.createTrade(order(1, "10", 100, 2));
        Long lastBuy = trades.createTrade(order(1, "20", 100, 3));
        assertTrue(trades.deleteTradeWithRollback(lastBuy));
        assertPosition(100, "10");
    }

    @Test
    void deletingSellRestoresCostRatherThanUsingSalePrice() {
        readyMarket("20");
        trades.createTrade(order(1, "10", 100, 2));
        Long sell = trades.createTrade(order(2, "20", 50, 3));
        assertTrue(trades.deleteTradeWithRollback(sell));
        assertPosition(100, "10");
    }

    @Test
    void deletingAllTradesClearsCostAndPersistsReverseMessage() throws Exception {
        readyMarket("10");
        Long buy = trades.createTrade(order(1, "10", 100, 2));
        assertTrue(trades.deleteTradeWithRollback(buy));
        assertPosition(0, "0");
        List<LocalMessage> outbox = messages.selectList(new LambdaQueryWrapper<LocalMessage>()
                .orderByAsc(LocalMessage::getId));
        // 买入已改为同步扣款，不再产生扣款消息；此处只应有删除产生的反向入账消息
        assertEquals(1, outbox.size());
        CashChangeMessage reverse = new ObjectMapper().readValue(outbox.get(0).getPayload(), CashChangeMessage.class);
        assertEquals(2, reverse.getDirection());
        assertEquals(0, new BigDecimal("1005").compareTo(reverse.getAmount()));
        assertEquals(outbox.get(0).getMsgId(), reverse.getMsgId());
        assertTrue(reverse.getMsgId().length() <= 64);
        assertEquals(LocalMessage.STATUS_PENDING, outbox.get(0).getStatus());
    }

    @Test
    void deletingSellPersistsReverseDeductionOfNetProceeds() throws Exception {
        readyMarket("20");
        trades.createTrade(order(1, "10", 100, 2));
        Long sell = trades.createTrade(order(2, "20", 50, 3));
        trades.deleteTradeWithRollback(sell);
        LocalMessage reverseMessage = messages.selectList(new LambdaQueryWrapper<LocalMessage>()
                .orderByDesc(LocalMessage::getId)).get(0);
        CashChangeMessage reverse = new ObjectMapper().readValue(reverseMessage.getPayload(), CashChangeMessage.class);
        assertEquals(1, reverse.getDirection());
        assertEquals(0, new BigDecimal("995").compareTo(reverse.getAmount()));
    }

    @Test
    void historicalSaleUsesThatDaysPreCloseRatherThanLatestPrice() {
        readyMarket("20");
        when(data.getLatestPrice(anyString())).thenReturn(Map.of("preClose", new BigDecimal("100")));
        trades.createTrade(order(1, "10", 100, 2));
        assertNotNull(trades.createTrade(order(2, "20", 50, 3)));
        verify(data).getDailyData("600519", "20250103", "20250103", null);
        verify(data, never()).getLatestPrice(anyString());
    }

    @Test
    void missingTradeDayMustNotFallBackToLatestMarket() {
        readyMarket("20");
        when(data.getDailyData(anyString(), anyString(), anyString(), nullable(Integer.class))).thenReturn(List.of());
        trades.createTrade(order(1, "10", 100, 2));
        assertThrows(BusinessException.class, () -> trades.createTrade(order(2, "20", 50, 3)));
    }

    @Test
    void historicalSaleWithTwentyPercentMoveUsesActualRange() {
        readyMarket("100");
        when(data.getDailyData(anyString(), anyString(), anyString(), nullable(Integer.class))).thenReturn(List.of(
                Map.of("tradeDate", "20250103", "preClose", new BigDecimal("100"),
                        "lowPrice", new BigDecimal("98"), "highPrice", new BigDecimal("120"))));
        trades.createTrade(order(1, "100", 100, 2));
        assertNotNull(trades.createTrade(order(2, "120", 50, 3)));
    }

    @Test
    void historicalSaleOutsideActualRangeIsRejectedEvenWithinTenPercent() {
        readyMarket("100");
        when(data.getDailyData(anyString(), anyString(), anyString(), nullable(Integer.class))).thenReturn(List.of(
                Map.of("tradeDate", "20250103", "preClose", new BigDecimal("100"),
                        "lowPrice", new BigDecimal("99"), "highPrice", new BigDecimal("102"))));
        trades.createTrade(order(1, "100", 100, 2));
        assertThrows(BusinessException.class, () -> trades.createTrade(order(2, "105", 50, 3)));
    }

    @Test
    void incompleteOrMismatchedHistoricalRangeDoesNotPermitSale() {
        readyMarket("20");
        trades.createTrade(order(1, "10", 100, 2));
        for (Map<String, Object> row : List.<Map<String, Object>>of(
                Map.of("tradeDate", "20250103", "preClose", 20),
                Map.of("tradeDate", "20250102", "lowPrice", 18, "highPrice", 22),
                Map.of("tradeDate", "20250103", "lowPrice", 22, "highPrice", 18),
                Map.of("tradeDate", "20250103", "lowPrice", 0, "highPrice", 22))) {
            when(data.getDailyData(anyString(), anyString(), anyString(), nullable(Integer.class))).thenReturn(List.of(row));
            assertThrows(BusinessException.class, () -> trades.createTrade(order(2, "20", 50, 3)));
        }
    }

    @Test
    void bareCodeFallsBackToSuffixedMarketData() {
        // 行情库只认 600519.SH，裸代码必须补后缀后才能校验卖出价
        when(auth.deductCash(any(), any(), any())).thenReturn(Result.success(true));
        when(auth.addCash(any(), any(), any())).thenReturn(Result.success(true));
        when(data.getDailyData(eq("600519"), anyString(), anyString(), nullable(Integer.class))).thenReturn(List.of());
        when(data.getDailyData(eq("600519.SH"), anyString(), anyString(), nullable(Integer.class))).thenAnswer(inv ->
                List.of(Map.<String, Object>of("tradeDate", inv.getArgument(1, String.class),
                        "lowPrice", new BigDecimal("98"), "highPrice", new BigDecimal("102"))));

        trades.createTrade(order(1, "100", 100, 2));

        assertNotNull(trades.createTrade(order(2, "100", 50, 3)));
    }

    private void readyMarket(String preClose) {
        when(auth.getUserCash(any())).thenReturn(Result.success(new BigDecimal("1000000")));
        // 买入改为同步原子扣款：默认视为扣款成功
        when(auth.deductCash(any(), any(), any())).thenReturn(Result.success(true));
        when(auth.addCash(any(), any(), any())).thenReturn(Result.success(true));
        when(data.getLatestPrice(anyString())).thenReturn(Map.of("preClose", new BigDecimal(preClose)));
        when(data.getDailyData(anyString(), anyString(), anyString(), nullable(Integer.class))).thenAnswer(inv ->
                List.of(Map.<String, Object>of("tradeDate", inv.getArgument(1, String.class),
                        "preClose", new BigDecimal(preClose),
                        "lowPrice", new BigDecimal(preClose).multiply(new BigDecimal("0.9")),
                        "highPrice", new BigDecimal(preClose).multiply(new BigDecimal("1.1")))));
    }

    private TradeDTO order(int type, String price, int quantity, int day) {
        TradeDTO dto = new TradeDTO();
        dto.setUserId(91L);
        dto.setStockCode("600519");
        dto.setStockName("贵州茅台");
        dto.setTradeType(type);
        dto.setTradePrice(new BigDecimal(price));
        dto.setTradeQuantity(quantity);
        dto.setCommission(new BigDecimal("5"));
        dto.setTax(BigDecimal.ZERO);
        dto.setTradeDate(LocalDate.of(2025, 1, day));
        return dto;
    }

    private void assertPosition(int quantity, String cost) {
        PositionSummary position = positions.selectOne(new LambdaQueryWrapper<PositionSummary>()
                .eq(PositionSummary::getUserId, 91L).eq(PositionSummary::getStockCode, "600519"));
        assertNotNull(position);
        assertEquals(quantity, position.getTotalQuantity());
        assertEquals(0, new BigDecimal(cost).compareTo(position.getAvgCost()));
    }
}
