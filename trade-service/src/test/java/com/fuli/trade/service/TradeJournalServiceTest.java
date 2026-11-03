package com.fuli.trade.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fuli.trade.dto.JournalCalendarItemVO;
import com.fuli.trade.dto.TradeJournalDTO;
import com.fuli.trade.dto.TradeJournalQueryDTO;
import com.fuli.trade.dto.TradeJournalVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"spring.cloud.nacos.config.enabled=false", "spring.cloud.nacos.discovery.enabled=false"})
@ActiveProfiles("test")
@Transactional
class TradeJournalServiceTest {

    @Autowired
    private TradeJournalService tradeJournalService;

    @Test
    void testJournalCrudAndCalendar() {
        Long userId = 1001L;

        // 1. Create
        TradeJournalDTO dto = new TradeJournalDTO();
        dto.setTitle("突破买入贵州茅台");
        dto.setStockCode("600519.SH");
        dto.setStockName("贵州茅台");
        dto.setJournalDate(LocalDate.now());
        dto.setEmotionScore(4);
        dto.setMarketSentiment("普涨突破");
        dto.setStrategyType("趋势突破");
        dto.setContent("早盘放量突破20日均线，执行买入策略");
        dto.setReflection("执行坚决，符合交易计划");
        dto.setTags("突破,茅台,执行力");

        Long id = tradeJournalService.createJournal(dto, userId);
        assertNotNull(id);

        // 2. Read
        TradeJournalVO vo = tradeJournalService.getJournalById(id, userId);
        assertNotNull(vo);
        assertEquals("突破买入贵州茅台", vo.getTitle());
        assertEquals("600519.SH", vo.getStockCode());
        assertEquals(4, vo.getEmotionScore());

        // 3. Update
        TradeJournalDTO updateDto = new TradeJournalDTO();
        updateDto.setTitle("突破买入贵州茅台 - 修正");
        updateDto.setEmotionScore(3);
        boolean updated = tradeJournalService.updateJournal(id, updateDto, userId);
        assertTrue(updated);

        TradeJournalVO updatedVo = tradeJournalService.getJournalById(id, userId);
        assertEquals("突破买入贵州茅台 - 修正", updatedVo.getTitle());
        assertEquals(3, updatedVo.getEmotionScore());

        // 4. Page
        TradeJournalQueryDTO queryDTO = new TradeJournalQueryDTO();
        queryDTO.setKeyword("茅台");
        Page<TradeJournalVO> page = tradeJournalService.pageJournals(queryDTO, userId);
        assertTrue(page.getTotal() >= 1);

        // 5. Calendar Summary
        String ym = LocalDate.now().getYear() + "-" + String.format("%02d", LocalDate.now().getMonthValue());
        List<JournalCalendarItemVO> calendar = tradeJournalService.getCalendarSummary(ym, userId);
        assertFalse(calendar.isEmpty());
        assertEquals(1, calendar.get(0).getCount());

        // 6. Delete
        boolean deleted = tradeJournalService.deleteJournal(id, userId);
        assertTrue(deleted);
    }
}
