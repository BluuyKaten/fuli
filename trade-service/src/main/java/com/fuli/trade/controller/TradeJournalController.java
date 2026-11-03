package com.fuli.trade.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fuli.common.api.Result;
import com.fuli.common.api.security.AuthenticatedUser;
import com.fuli.trade.dto.JournalCalendarItemVO;
import com.fuli.trade.dto.TradeJournalDTO;
import com.fuli.trade.dto.TradeJournalQueryDTO;
import com.fuli.trade.dto.TradeJournalVO;
import com.fuli.trade.service.TradeJournalService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/trade/journal")
public class TradeJournalController {

    private final TradeJournalService tradeJournalService;
    private final HttpServletRequest request;

    public TradeJournalController(TradeJournalService tradeJournalService, HttpServletRequest request) {
        this.tradeJournalService = tradeJournalService;
        this.request = request;
    }

    private Long getCurrentUserId() {
        return AuthenticatedUser.requireId(request);
    }

    @PostMapping
    public Result<Long> create(@RequestBody TradeJournalDTO dto) {
        Long id = tradeJournalService.createJournal(dto, getCurrentUserId());
        return Result.success("创建笔记成功", id);
    }

    @PutMapping("/{id}")
    public Result<Boolean> update(@PathVariable Long id, @RequestBody TradeJournalDTO dto) {
        boolean ok = tradeJournalService.updateJournal(id, dto, getCurrentUserId());
        return Result.success(ok ? "更新成功" : "更新失败", ok);
    }

    @DeleteMapping("/{id}")
    public Result<Boolean> delete(@PathVariable Long id) {
        boolean ok = tradeJournalService.deleteJournal(id, getCurrentUserId());
        return Result.success(ok ? "删除成功" : "删除失败", ok);
    }

    @GetMapping("/{id}")
    public Result<TradeJournalVO> getById(@PathVariable Long id) {
        TradeJournalVO vo = tradeJournalService.getJournalById(id, getCurrentUserId());
        return Result.success(vo);
    }

    @PostMapping("/page")
    public Result<Page<TradeJournalVO>> page(@RequestBody TradeJournalQueryDTO queryDTO) {
        Page<TradeJournalVO> page = tradeJournalService.pageJournals(queryDTO, getCurrentUserId());
        return Result.success(page);
    }

    @GetMapping("/calendar-summary")
    public Result<List<JournalCalendarItemVO>> calendarSummary(@RequestParam(required = false) String yearMonth) {
        List<JournalCalendarItemVO> list = tradeJournalService.getCalendarSummary(yearMonth, getCurrentUserId());
        return Result.success(list);
    }
}
