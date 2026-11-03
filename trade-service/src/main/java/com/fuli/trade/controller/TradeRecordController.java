package com.fuli.trade.controller;

import com.fuli.common.api.Result;
import com.fuli.common.api.dto.TradeDTO;
import com.fuli.common.api.dto.TradeQueryDTO;
import com.fuli.common.api.vo.PositionVO;
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
        return com.fuli.common.api.security.AuthenticatedUser.requireId(request);
    }

    private TradeVO requireOwnedTrade(Long id) {
        Long userId = getCurrentUserId();
        TradeVO record = tradeRecordService.getTradeById(id);
        if (record == null) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "记录不存在");
        }
        if (!userId.equals(record.getUserId())) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "无权访问该交易记录");
        }
        return record;
    }

    @PostMapping
    public Result<Long> create(@RequestBody TradeDTO tradeDTO) {
        tradeDTO.setUserId(getCurrentUserId());
        Long id = tradeRecordService.createTrade(tradeDTO);
        return Result.success("创建成功", id);
    }

    @PutMapping("/{id}")
    public Result<Boolean> update(@PathVariable Long id, @RequestBody TradeDTO tradeDTO) {
        requireOwnedTrade(id);
        tradeDTO.setUserId(getCurrentUserId());
        boolean result = tradeRecordService.updateTrade(id, tradeDTO);
        return Result.success(result ? "更新成功" : "更新失败", result);
    }

    @DeleteMapping("/{id}")
    public Result<Boolean> delete(@PathVariable Long id) {
        requireOwnedTrade(id);
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
        return Result.success(requireOwnedTrade(id));
    }

    @PostMapping("/list")
    public Result<List<TradeVO>> list(@RequestBody TradeQueryDTO queryDTO) {
        queryDTO.setUserId(getCurrentUserId());
        List<TradeVO> list = tradeRecordService.listTrades(queryDTO);
        return Result.success(list);
    }

    @PostMapping("/page")
    public Result<?> page(@RequestBody TradeQueryDTO queryDTO) {
        queryDTO.setUserId(getCurrentUserId());
        return Result.success(tradeRecordService.pageTrades(queryDTO));
    }

    @PostMapping("/queryByCondition")
    public Result<List<TradeVO>> queryByCondition(@RequestBody TradeQueryDTO queryDTO) {
        queryDTO.setUserId(getCurrentUserId());
        List<TradeVO> list = tradeRecordService.queryByCondition(queryDTO);
        return Result.success(list);
    }

    @PostMapping("/internal/queryByCondition")
    public Result<List<TradeVO>> internalQueryByCondition(@RequestBody TradeQueryDTO queryDTO) {
        requireInternalUserId(queryDTO);
        return Result.success(tradeRecordService.queryByCondition(queryDTO));
    }

    @PostMapping("/internal/statistics")
    public Result<StatisticsVO> internalStatistics(@RequestBody TradeQueryDTO queryDTO) {
        requireInternalUserId(queryDTO);
        return Result.success(tradeRecordService.getStatistics(queryDTO));
    }

    @GetMapping("/internal/positions")
    public Result<List<PositionVO>> internalPositions(@RequestParam Long userId) {
        if (userId == null || userId <= 0) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "内部查询需要用户ID");
        }
        return Result.success(tradeRecordService.getUserPositions(userId));
    }

    private void requireInternalUserId(TradeQueryDTO queryDTO) {
        if (queryDTO.getUserId() == null || queryDTO.getUserId() <= 0) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "内部查询需要用户ID");
        }
    }

    @PostMapping("/statistics")
    public Result<StatisticsVO> statistics(@RequestBody TradeQueryDTO queryDTO) {
        queryDTO.setUserId(getCurrentUserId());
        StatisticsVO statistics = tradeRecordService.getStatistics(queryDTO);
        return Result.success(statistics);
    }
}
