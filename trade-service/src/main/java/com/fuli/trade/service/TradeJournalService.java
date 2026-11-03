package com.fuli.trade.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.fuli.trade.dto.JournalCalendarItemVO;
import com.fuli.trade.dto.TradeJournalDTO;
import com.fuli.trade.dto.TradeJournalQueryDTO;
import com.fuli.trade.dto.TradeJournalVO;
import com.fuli.trade.entity.TradeJournal;

import java.util.List;

public interface TradeJournalService extends IService<TradeJournal> {

    Long createJournal(TradeJournalDTO dto, Long userId);

    boolean updateJournal(Long id, TradeJournalDTO dto, Long userId);

    boolean deleteJournal(Long id, Long userId);

    TradeJournalVO getJournalById(Long id, Long userId);

    Page<TradeJournalVO> pageJournals(TradeJournalQueryDTO queryDTO, Long userId);

    List<JournalCalendarItemVO> getCalendarSummary(String yearMonth, Long userId);
}
