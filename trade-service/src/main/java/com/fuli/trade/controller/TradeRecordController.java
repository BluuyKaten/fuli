package com.fuli.trade.controller;

import com.fuli.common.api.Result;
import com.fuli.common.api.dto.TradeDTO;
import com.fuli.common.api.dto.TradeQueryDTO;
import com.fuli.common.api.vo.StatisticsVO;
import com.fuli.common.api.vo.TradeVO;
import com.fuli.trade.service.TradeRecordService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/trade")
public class TradeRecordController {

    private final TradeRecordService tradeRecordService;
    private final HttpServletRequest request;

    public TradeRecordController(TradeRecordService tradeRecordService, HttpServletRequest request) {
        this.tradeRecordService = tradeRecordService;
        this.request = request;
    }

    private Long getCurrentUserId() {
        String userIdHeader = request.getHeader("X-User-Id");
        if (userIdHeader != null && !userIdHeader.isEmpty()) {
            return Long.parseLong(userIdHeader);
        }
        return null;
    }

    @PostMapping
    public Result<Long> create(@RequestBody TradeDTO tradeDTO) {
        if (tradeDTO.getUserId() == null) {
            tradeDTO.setUserId(getCurrentUserId());
        }
        Long id = tradeRecordService.createTrade(tradeDTO);
        return Result.success("创建成功", id);
    }

    @PutMapping("/{id}")
    public Result<Boolean> update(@PathVariable Long id, @RequestBody TradeDTO tradeDTO) {
        boolean result = tradeRecordService.updateTrade(id, tradeDTO);
        return Result.success(result ? "更新成功" : "更新失败", result);
    }

    @DeleteMapping("/{id}")
    public Result<Boolean> delete(@PathVariable Long id) {
        boolean result = tradeRecordService.deleteTradeWithRollback(id);
        return Result.success(result ? "删除成功" : "删除失败", result);
    }

    @DeleteMapping("/internal/clearAll")
    public Result<Boolean> clearAll(@RequestParam Long userId) {
        boolean result = tradeRecordService.clearAllByUserId(userId);
        return Result.success(result ? "清空成功" : "清空失败", result);
    }

    @GetMapping("/{id}")
    public Result<TradeVO> getById(@PathVariable Long id) {
        TradeVO vo = tradeRecordService.getTradeById(id);
        return vo != null ? Result.success(vo) : Result.error("记录不存在");
    }

    @PostMapping("/list")
    public Result<List<TradeVO>> list(@RequestBody TradeQueryDTO queryDTO) {
        if (queryDTO.getUserId() == null) {
            queryDTO.setUserId(getCurrentUserId());
        }
        List<TradeVO> list = tradeRecordService.listTrades(queryDTO);
        return Result.success(list);
    }

    @PostMapping("/page")
    public Result<?> page(@RequestBody TradeQueryDTO queryDTO) {
        if (queryDTO.getUserId() == null) {
            queryDTO.setUserId(getCurrentUserId());
        }
        return Result.success(tradeRecordService.pageTrades(queryDTO));
    }

    @PostMapping("/queryByCondition")
    public Result<List<TradeVO>> queryByCondition(@RequestBody TradeQueryDTO queryDTO) {
        if (queryDTO.getUserId() == null) {
            queryDTO.setUserId(getCurrentUserId());
        }
        List<TradeVO> list = tradeRecordService.queryByCondition(queryDTO);
        return Result.success(list);
    }

    @PostMapping("/statistics")
    public Result<StatisticsVO> statistics(@RequestBody TradeQueryDTO queryDTO) {
        if (queryDTO.getUserId() == null) {
            queryDTO.setUserId(getCurrentUserId());
        }
        StatisticsVO statistics = tradeRecordService.getStatistics(queryDTO);
        return Result.success(statistics);
    }
}
