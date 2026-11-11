package com.fuli.trade.service;

import com.fuli.common.api.dto.TradeQueryDTO;
import com.fuli.common.api.feign.AuthFeignClient;
import com.fuli.common.api.feign.DataFeignClient;
import com.fuli.common.api.vo.MonthlyProfitVO;
import com.fuli.common.api.vo.TradeVO;
import com.fuli.trade.entity.TradeRecord;
import com.fuli.trade.mapper.TradeRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 月度盈亏「SQL 聚合 vs 原内存实现」对拍。
 *
 * <p>下沉的理由是读放大（N 笔交易 → M 个月），但这是给用户看的账，**结果必须逐位一致**：
 * 差一分钱都会被问。所以这里把 analysis-service 原实现（拉全量交易 + stream 分组）原样搬过来
 * 当参照物，用同一份数据跑两遍比较。
 */
@SpringBootTest(properties = {"spring.cloud.nacos.config.enabled=false", "spring.cloud.nacos.discovery.enabled=false"})
@ActiveProfiles("test")
@Transactional
class MonthlyProfitAggregationTest {
    private static final Long USER = 21L;

    @Autowired TradeRecordService tradeRecordService;
    @Autowired TradeRecordMapper tradeRecordMapper;

    @MockitoBean AuthFeignClient authFeignClient;
    @MockitoBean DataFeignClient dataFeignClient;

    @BeforeEach
    void clean() {
        tradeRecordMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>());
    }

    @Test
    void emptyRangeReturnsNoMonths() {
        assertTrue(tradeRecordService.getMonthlyProfit(query(null, null)).isEmpty());
    }

    @Test
    void matchesLegacyInMemoryAggregationAcrossYearsAndFeeBearingAmounts() {
        // 2025-12：两笔卖出（一赚一亏），跨年边界
        trade("600519.SH", 2, "2025-12-30", "244.40");
        trade("600519.SH", 2, "2025-12-31", "-100.05");
        // 2026-01：买入行没有盈亏（不计入分母）、平盘一笔（既不算赢也不算输）、再赚一笔
        trade("000001.SZ", 1, "2026-01-05", null);
        trade("000001.SZ", 2, "2026-01-06", "0.00");
        trade("000001.SZ", 2, "2026-01-07", "1023.45");
        // 2026-02：只有买入，不产生月份
        trade("600519.SH", 1, "2026-02-02", null);

        List<TradeVO> legacyInput = tradeRecordService.queryByCondition(query(null, null));
        assertEquals(tradeRecordService.getMonthlyProfit(query(null, null)), legacyMonthlyProfit(legacyInput),
                "SQL 聚合必须与内存实现逐位一致");
    }

    @Test
    void rangeAndUserFiltersNarrowTheSameWayAsTheTradeList() {
        trade("600519.SH", 2, "2025-12-30", "10.00");
        trade("600519.SH", 2, "2026-01-06", "20.00");
        Long other = 22L;
        TradeRecord foreign = record(other, "600519.SH", 2, "2026-01-06", "999.00");
        tradeRecordMapper.insert(foreign);
        TradeRecord deleted = record(USER, "600519.SH", 2, "2026-01-06", "999.00");
        deleted.setDeleted(1);
        tradeRecordMapper.insert(deleted);

        var actual = tradeRecordService.getMonthlyProfit(
                query(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)));
        assertEquals(legacyMonthlyProfit(tradeRecordService.queryByCondition(
                query(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)))), actual);
        assertEquals(1, actual.size());
        assertEquals("2026-01", actual.get(0).getMonth());
        assertEquals(1, actual.get(0).getTradeCount(), "别的用户和已删除记录都不该进来");
        assertEquals(0, new BigDecimal("20.00").compareTo(actual.get(0).getProfitLoss()));
        assertEquals(new BigDecimal("1.0000"), actual.get(0).getWinRate());
    }

    /** analysis-service 的原实现，一字未改（`computeMonthlyProfit`）。 */
    private List<MonthlyProfitVO> legacyMonthlyProfit(List<TradeVO> trades) {
        Map<String, List<TradeVO>> monthlyMap = trades.stream()
                .filter(t -> t.getProfitLoss() != null)
                .collect(Collectors.groupingBy(
                        t -> t.getTradeDate().format(DateTimeFormatter.ofPattern("yyyy-MM")),
                        LinkedHashMap::new,
                        Collectors.toList()));

        return monthlyMap.entrySet().stream().map(entry -> {
            List<TradeVO> monthTrades = entry.getValue();
            MonthlyProfitVO vo = new MonthlyProfitVO();
            vo.setMonth(entry.getKey());
            vo.setTradeCount(monthTrades.size());
            vo.setProfitLoss(monthTrades.stream().map(TradeVO::getProfitLoss)
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
            long winCount = monthTrades.stream()
                    .filter(t -> t.getProfitLoss().compareTo(BigDecimal.ZERO) > 0)
                    .count();
            vo.setWinRate(BigDecimal.valueOf(winCount)
                    .divide(BigDecimal.valueOf(monthTrades.size()), 4, RoundingMode.HALF_UP));
            return vo;
        }).collect(Collectors.toCollection(ArrayList::new));
    }

    private TradeQueryDTO query(LocalDate start, LocalDate end) {
        TradeQueryDTO dto = new TradeQueryDTO();
        dto.setUserId(USER);
        dto.setStartDate(start);
        dto.setEndDate(end);
        return dto;
    }

    private void trade(String code, int type, String date, String profitLoss) {
        tradeRecordMapper.insert(record(USER, code, type, date, profitLoss));
    }

    private TradeRecord record(Long userId, String code, int type, String date, String profitLoss) {
        TradeRecord record = new TradeRecord();
        record.setUserId(userId);
        record.setStockCode(code);
        record.setStockName(code);
        record.setTradeType(type);
        record.setTradePrice(new BigDecimal("10.0000"));
        record.setTradeQuantity(100);
        record.setTradeAmount(new BigDecimal("1000.0000"));
        record.setTotalCost(new BigDecimal("1000.0000"));
        record.setProfitLoss(profitLoss == null ? null : new BigDecimal(profitLoss));
        record.setTradeDate(LocalDate.parse(date));
        record.setDeleted(0);
        return record;
    }
}
