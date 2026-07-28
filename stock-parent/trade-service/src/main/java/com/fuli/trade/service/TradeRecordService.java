package com.fuli.trade.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.fuli.trade.entity.TradeRecord;
import java.time.LocalDate;
import java.util.List;

public interface TradeRecordService extends IService<TradeRecord> {
    Page<TradeRecord> pageQuery(long page, long size, String symbol, LocalDate start, LocalDate end);

    List<TradeRecord> listByUserId(Long userId, LocalDate start, LocalDate end);
}
