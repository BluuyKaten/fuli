package com.fuli.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fuli.trade.entity.TradeRecord;
import com.fuli.trade.mapper.TradeRecordMapper;
import com.fuli.trade.service.TradeRecordService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class TradeRecordServiceImpl extends ServiceImpl<TradeRecordMapper, TradeRecord> implements TradeRecordService {

    @Override
    public Page<TradeRecord> pageQuery(long page, long size, String symbol, LocalDate start, LocalDate end) {
        LambdaQueryWrapper<TradeRecord> wrapper = buildWrapper(null, symbol, start, end);
        return page(new Page<>(page, size), wrapper);
    }

    @Override
    public List<TradeRecord> listByUserId(Long userId, LocalDate start, LocalDate end) {
        return list(buildWrapper(userId, null, start, end));
    }

    private LambdaQueryWrapper<TradeRecord> buildWrapper(Long userId, String symbol, LocalDate start, LocalDate end) {
        LambdaQueryWrapper<TradeRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(userId != null, TradeRecord::getUserId, userId);
        wrapper.eq(StringUtils.hasText(symbol), TradeRecord::getSymbol, symbol);
        if (start != null) {
            wrapper.ge(TradeRecord::getTradeTime, LocalDateTime.of(start, java.time.LocalTime.MIN));
        }
        if (end != null) {
            wrapper.le(TradeRecord::getTradeTime, LocalDateTime.of(end, java.time.LocalTime.MAX));
        }
        wrapper.orderByDesc(TradeRecord::getTradeTime);
        return wrapper;
    }
}
