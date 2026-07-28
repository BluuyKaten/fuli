package com.fuli.trade.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fuli.common.dto.TradeVO;
import com.fuli.common.model.PageResult;
import com.fuli.common.model.Result;
import com.fuli.trade.entity.TradeRecord;
import com.fuli.trade.service.TradeRecordService;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping
@RequiredArgsConstructor
public class TradeRecordController {

    private final TradeRecordService tradeRecordService;

    @PostMapping("/trade/records")
    public Result<Void> create(@RequestBody TradeRecord record) {
        tradeRecordService.save(record);
        return Result.ok("创建成功", null);
    }

    @PutMapping("/trade/records/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody TradeRecord record) {
        record.setId(id);
        tradeRecordService.updateById(record);
        return Result.ok("更新成功", null);
    }

    @DeleteMapping("/trade/records/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        tradeRecordService.removeById(id);
        return Result.ok("删除成功", null);
    }

    @GetMapping("/trade/records/{id}")
    public Result<TradeRecord> detail(@PathVariable Long id) {
        return Result.ok(tradeRecordService.getById(id));
    }

    @GetMapping("/trade/records")
    public Result<PageResult<TradeRecord>> page(@RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "10") long size,
                                                 @RequestParam(required = false) String symbol,
                                                 @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate start,
                                                 @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate end) {
        Page<TradeRecord> data = tradeRecordService.pageQuery(page, size, symbol, start, end);
        return Result.ok(new PageResult<>(data.getTotal(), data.getCurrent(), data.getSize(), data.getRecords()));
    }

    @GetMapping("/internal/trades")
    public Result<List<TradeVO>> listByUserId(@RequestParam("userId") Long userId,
                                              @RequestParam(value = "start", required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate start,
                                              @RequestParam(value = "end", required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate end) {
        List<TradeVO> list = tradeRecordService.listByUserId(userId, start, end).stream().map(this::toVO).toList();
        return Result.ok(list);
    }

    private TradeVO toVO(TradeRecord record) {
        TradeVO vo = new TradeVO();
        BeanUtils.copyProperties(record, vo);
        return vo;
    }
}
