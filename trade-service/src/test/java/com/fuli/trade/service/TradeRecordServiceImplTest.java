package com.fuli.trade.service;

import com.fuli.common.api.dto.TradeDTO;
import com.fuli.common.api.enums.TradeTypeEnum;
import com.fuli.common.api.exception.BizCode;
import com.fuli.common.api.exception.BusinessException;
import com.fuli.common.api.feign.AuthFeignClient;
import com.fuli.common.api.Result;
import com.fuli.trade.config.FuliProperties;
import com.fuli.trade.entity.PositionSummary;
import com.fuli.trade.entity.TradeRecord;
import com.fuli.trade.mapper.PositionSummaryMapper;
import com.fuli.trade.mapper.StockDailyDataMapper;
import com.fuli.trade.mapper.TradeRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * TradeRecordServiceImpl 集成测试(H2 内存数据库)
 * 覆盖 createTrade 主路径:持仓更新、盈亏计算、卖出不足、删除回滚
 */
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TradeRecordServiceImplTest {

    @Autowired
    private TradeRecordService tradeRecordService;

    @Autowired
    private TradeRecordMapper tradeRecordMapper;

    @Autowired
    private PositionSummaryMapper positionSummaryMapper;

    @Autowired
    private StockDailyDataMapper stockDailyDataMapper;

    @MockitoBean
    private AuthFeignClient authFeignClient;

    @MockitoBean
    private org.springframework.context.ApplicationEventPublisher applicationEventPublisher;

    @MockitoBean
    private com.fuli.trade.service.LocalMessageService localMessageService;
    @MockitoBean
    private com.fuli.trade.service.LocalMessageRetryService localMessageRetryService;

    private static final Long USER_ID = 1L;

    @BeforeEach
    void setUp() {
        // 清理数据
        tradeRecordMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>());
        positionSummaryMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>());
        stockDailyDataMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>());

        // 插入行情数据,避免卖出涨跌停校验阻塞
        insertDailyData("600519", "20250101", "100.00");

        // Mock Feign:默认资金充足
        when(authFeignClient.getUserCash(eq(USER_ID)))
                .thenReturn(Result.success(new BigDecimal("1000000.00")));
        when(authFeignClient.getUserCash(any()))
                .thenReturn(Result.success(new BigDecimal("1000000.00")));
        doReturn(Result.success(true)).when(authFeignClient).deductCash(any(), any(), any());
        doReturn(Result.success(true)).when(authFeignClient).addCash(any(), any(), any());

        // Mock localMessageService:返回 pending 消息
        com.fuli.trade.entity.LocalMessage mockMsg = new com.fuli.trade.entity.LocalMessage();
        mockMsg.setMsgId("test-msg-id");
        when(localMessageService.createPendingMessage(any(), any(), any())).thenReturn(mockMsg);

        // 禁用真实事件发布(避免异步线程干扰)
        doNothing().when(applicationEventPublisher).publishEvent(any());
    }

    @Test
    void createTrade_buy_shouldIncreasePosition() {
        TradeDTO buy = newBuyDTO("600519", "贵州茅台", new BigDecimal("100.00"), 100);

        Long id = tradeRecordService.createTrade(buy);

        assertNotNull(id);
        // 持仓应增加
        PositionSummary pos = getPosition("600519");
        assertNotNull(pos);
        assertEquals(100, pos.getTotalQuantity());
        // 均价 = (0 + 100*100) / 100 = 100
        assertEquals(0, new BigDecimal("100.00").compareTo(pos.getAvgCost()));
    }

    @Test
    void createTrade_buyTwice_shouldWeightedAvgCost() {
        tradeRecordService.createTrade(newBuyDTO("600519", "贵州茅台", new BigDecimal("100.00"), 100));
        tradeRecordService.createTrade(newBuyDTO("600519", "贵州茅台", new BigDecimal("120.00"), 100));

        PositionSummary pos = getPosition("600519");
        assertEquals(200, pos.getTotalQuantity());
        // 加权均价 = (100*100 + 100*120) / 200 = 110
        assertEquals(0, new BigDecimal("110.00").compareTo(pos.getAvgCost()));
    }

    @Test
    void createTrade_sell_shouldCalculateProfitAndDecreasePosition() {
        // 先买后卖
        tradeRecordService.createTrade(newBuyDTO("600519", "贵州茅台", new BigDecimal("100.00"), 100));
        // 清理发布事件计数
        clearInvocations(applicationEventPublisher);

        TradeDTO sell = newSellDTO("600519", "贵州茅台", new BigDecimal("105.00"), 50);
        Long sellId = tradeRecordService.createTrade(sell);

        // 持仓减为 50
        PositionSummary pos = getPosition("600519");
        assertEquals(50, pos.getTotalQuantity());

        // 卖出盈亏 = (105 - 100) * 50 - 手续费 5 - 印花税 0.6 = 244.4
        TradeRecord sellRecord = tradeRecordMapper.selectById(sellId);
        assertNotNull(sellRecord.getProfitLoss());
        assertTrue(sellRecord.getProfitLoss().compareTo(BigDecimal.ZERO) > 0);
        assertEquals(0, new BigDecimal("244.40").compareTo(sellRecord.getProfitLoss()));
    }

    @Test
    void createTrade_sellWithoutPosition_shouldThrow() {
        TradeDTO sell = newSellDTO("600519", "贵州茅台", new BigDecimal("120.00"), 50);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> tradeRecordService.createTrade(sell));

        assertEquals(BizCode.POSITION_INSUFFICIENT, ex.getCode());
    }

    @Test
    void createTrade_sellMoreThanHolding_shouldThrow() {
        tradeRecordService.createTrade(newBuyDTO("600519", "贵州茅台", new BigDecimal("100.00"), 100));

        clearInvocations(applicationEventPublisher);
        TradeDTO sell = newSellDTO("600519", "贵州茅台", new BigDecimal("120.00"), 150);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> tradeRecordService.createTrade(sell));

        assertEquals(BizCode.POSITION_INSUFFICIENT, ex.getCode());
    }

    @Test
    void createTrade_invalidPrice_shouldThrow() {
        TradeDTO buy = newBuyDTO("600519", "贵州茅台", BigDecimal.ZERO, 100);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> tradeRecordService.createTrade(buy));

        assertEquals(400, ex.getCode());
    }

    @Test
    void deleteTradeWithRollback_buy_shouldReversePosition() {
        Long buyId = tradeRecordService.createTrade(newBuyDTO("600519", "贵州茅台", new BigDecimal("100.00"), 100));
        assertNotNull(getPosition("600519"));

        clearInvocations(applicationEventPublisher);
        boolean deleted = tradeRecordService.deleteTradeWithRollback(buyId);

        assertTrue(deleted);
        // 持仓应被回滚(数量减为 0,但 position_summary 行可能仍存在)
        PositionSummary pos = getPosition("600519");
        assertNotNull(pos);
        assertEquals(0, pos.getTotalQuantity());
    }

    @Test
    void isLastTrade_lastTrade_shouldReturnTrue() {
        Long id1 = tradeRecordService.createTrade(newBuyDTO("600519", "贵州茅台", new BigDecimal("100.00"), 100));
        Long id2 = tradeRecordService.createTrade(newBuyDTO("600519", "贵州茅台", new BigDecimal("110.00"), 100));

        assertTrue(tradeRecordService.isLastTrade(id2));
        assertFalse(tradeRecordService.isLastTrade(id1));
    }

    private TradeDTO newBuyDTO(String code, String name, BigDecimal price, int qty) {
        TradeDTO dto = new TradeDTO();
        dto.setUserId(USER_ID);
        dto.setStockCode(code);
        dto.setStockName(name);
        dto.setTradeType(TradeTypeEnum.BUY.getCode());
        dto.setTradePrice(price);
        dto.setTradeQuantity(qty);
        dto.setCommission(new BigDecimal("5.00"));
        dto.setTax(BigDecimal.ZERO);
        dto.setTradeDate(java.time.LocalDate.now());
        return dto;
    }

    private TradeDTO newSellDTO(String code, String name, BigDecimal price, int qty) {
        TradeDTO dto = new TradeDTO();
        dto.setUserId(USER_ID);
        dto.setStockCode(code);
        dto.setStockName(name);
        dto.setTradeType(TradeTypeEnum.SELL.getCode());
        // 卖出价设在涨跌停范围内(前收盘 100,涨停 110,跌停 90)
        dto.setTradePrice(price);
        dto.setTradeQuantity(qty);
        dto.setCommission(new BigDecimal("5.00"));
        dto.setTax(new BigDecimal("0.60"));
        dto.setTradeDate(java.time.LocalDate.of(2025, 1, 2));
        return dto;
    }

    private PositionSummary getPosition(String stockCode) {
        List<PositionSummary> list = positionSummaryMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PositionSummary>()
                        .eq(PositionSummary::getUserId, USER_ID)
                        .eq(PositionSummary::getStockCode, stockCode));
        return list.isEmpty() ? null : list.get(0);
    }

    private void insertDailyData(String stockCode, String tradeDate, String preClose) {
        com.fuli.trade.entity.StockDailyData data = new com.fuli.trade.entity.StockDailyData();
        data.setStockCode(stockCode);
        data.setTradeDate(tradeDate);
        data.setPreClose(new BigDecimal(preClose));
        stockDailyDataMapper.insert(data);
    }
}
