package com.fuli.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuli.trade.dto.JournalCalendarItemVO;
import com.fuli.trade.dto.TradeJournalDTO;
import com.fuli.trade.dto.TradeJournalQueryDTO;
import com.fuli.trade.dto.TradeJournalVO;
import com.fuli.trade.entity.TradeJournal;
import com.fuli.trade.mapper.TradeJournalMapper;
import com.fuli.trade.service.TradeJournalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TradeJournalServiceImpl extends ServiceImpl<TradeJournalMapper, TradeJournal> implements TradeJournalService {

    @Override
    public Long createJournal(TradeJournalDTO dto, Long userId) {
        if (!StringUtils.hasText(dto.getTitle())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "笔记标题不能为空");
        }
        if (dto.getJournalDate() == null) {
            dto.setJournalDate(LocalDate.now());
        }
        if (dto.getEmotionScore() == null) {
            dto.setEmotionScore(3);
        }

        TradeJournal journal = new TradeJournal();
        BeanUtils.copyProperties(dto, journal);
        journal.setUserId(userId);

        save(journal);
        return journal.getId();
    }

    @Override
    public boolean updateJournal(Long id, TradeJournalDTO dto, Long userId) {
        TradeJournal existing = requireOwnedJournal(id, userId);

        if (StringUtils.hasText(dto.getTitle())) {
            existing.setTitle(dto.getTitle());
        }
        existing.setStockCode(dto.getStockCode());
        existing.setStockName(dto.getStockName());
        if (dto.getJournalDate() != null) {
            existing.setJournalDate(dto.getJournalDate());
        }
        if (dto.getEmotionScore() != null) {
            existing.setEmotionScore(dto.getEmotionScore());
        }
        existing.setMarketSentiment(dto.getMarketSentiment());
        existing.setStrategyType(dto.getStrategyType());
        if (dto.getContent() != null) {
            existing.setContent(dto.getContent());
        }
        existing.setReflection(dto.getReflection());
        existing.setTags(dto.getTags());

        return updateById(existing);
    }

    @Override
    public boolean deleteJournal(Long id, Long userId) {
        requireOwnedJournal(id, userId);
        return removeById(id);
    }

    @Override
    public TradeJournalVO getJournalById(Long id, Long userId) {
        TradeJournal journal = requireOwnedJournal(id, userId);
        return convertToVO(journal);
    }

    @Override
    public Page<TradeJournalVO> pageJournals(TradeJournalQueryDTO queryDTO, Long userId) {
        int pageNum = (queryDTO.getPageNum() != null && queryDTO.getPageNum() > 0) ? queryDTO.getPageNum() : 1;
        int pageSize = (queryDTO.getPageSize() != null && queryDTO.getPageSize() > 0) ? queryDTO.getPageSize() : 10;
        Page<TradeJournal> page = new Page<>(pageNum, pageSize);

        LambdaQueryWrapper<TradeJournal> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TradeJournal::getUserId, userId);

        if (StringUtils.hasText(queryDTO.getStockCode())) {
            wrapper.eq(TradeJournal::getStockCode, queryDTO.getStockCode().trim());
        }
        if (queryDTO.getStartDate() != null) {
            wrapper.ge(TradeJournal::getJournalDate, queryDTO.getStartDate());
        }
        if (queryDTO.getEndDate() != null) {
            wrapper.le(TradeJournal::getJournalDate, queryDTO.getEndDate());
        }
        if (StringUtils.hasText(queryDTO.getKeyword())) {
            String kw = queryDTO.getKeyword().trim();
            wrapper.and(w -> w.like(TradeJournal::getTitle, kw)
                    .or().like(TradeJournal::getContent, kw)
                    .or().like(TradeJournal::getReflection, kw)
                    .or().like(TradeJournal::getStockName, kw));
        }
        if (StringUtils.hasText(queryDTO.getTag())) {
            wrapper.like(TradeJournal::getTags, queryDTO.getTag().trim());
        }

        wrapper.orderByDesc(TradeJournal::getJournalDate);
        wrapper.orderByDesc(TradeJournal::getId);

        Page<TradeJournal> recordPage = page(page, wrapper);
        Page<TradeJournalVO> voPage = new Page<>(recordPage.getCurrent(), recordPage.getSize(), recordPage.getTotal());
        voPage.setRecords(recordPage.getRecords().stream().map(this::convertToVO).collect(Collectors.toList()));
        return voPage;
    }

    @Override
    public List<JournalCalendarItemVO> getCalendarSummary(String yearMonth, Long userId) {
        // yearMonth format: yyyy-MM
        LocalDate start;
        LocalDate end;
        try {
            if (StringUtils.hasText(yearMonth)) {
                start = LocalDate.parse(yearMonth + "-01");
                end = start.plusMonths(1).minusDays(1);
            } else {
                LocalDate now = LocalDate.now();
                start = now.withDayOfMonth(1);
                end = start.plusMonths(1).minusDays(1);
            }
        } catch (Exception e) {
            LocalDate now = LocalDate.now();
            start = now.withDayOfMonth(1);
            end = start.plusMonths(1).minusDays(1);
        }

        LambdaQueryWrapper<TradeJournal> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TradeJournal::getUserId, userId)
                .ge(TradeJournal::getJournalDate, start)
                .le(TradeJournal::getJournalDate, end)
                .select(TradeJournal::getId, TradeJournal::getTitle, TradeJournal::getJournalDate, TradeJournal::getEmotionScore);

        List<TradeJournal> list = list(wrapper);
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }

        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        Map<String, List<TradeJournal>> byDate = list.stream()
                .collect(Collectors.groupingBy(j -> j.getJournalDate().format(dtf)));

        List<JournalCalendarItemVO> items = new ArrayList<>();
        byDate.forEach((dateStr, journals) -> {
            double avgEmotion = journals.stream()
                    .mapToInt(j -> j.getEmotionScore() != null ? j.getEmotionScore() : 3)
                    .average().orElse(3.0);
            List<String> titles = journals.stream().map(TradeJournal::getTitle).collect(Collectors.toList());
            items.add(JournalCalendarItemVO.builder()
                    .date(dateStr)
                    .count(journals.size())
                    .avgEmotion(Math.round(avgEmotion * 10.0) / 10.0)
                    .titles(titles)
                    .build());
        });

        items.sort(Comparator.comparing(JournalCalendarItemVO::getDate));
        return items;
    }

    private TradeJournal requireOwnedJournal(Long id, Long userId) {
        TradeJournal journal = getById(id);
        if (journal == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "复盘笔记不存在");
        }
        if (!userId.equals(journal.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问此复盘笔记");
        }
        return journal;
    }

    private TradeJournalVO convertToVO(TradeJournal journal) {
        TradeJournalVO vo = new TradeJournalVO();
        BeanUtils.copyProperties(journal, vo);
        return vo;
    }
}
