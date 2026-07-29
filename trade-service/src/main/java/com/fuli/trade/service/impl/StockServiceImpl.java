package com.fuli.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fuli.trade.entity.StockDailyData;
import com.fuli.trade.entity.StockInfo;
import com.fuli.trade.mapper.StockDailyDataMapper;
import com.fuli.trade.mapper.StockInfoMapper;
import com.fuli.trade.service.StockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class StockServiceImpl implements StockService {

    private final StockInfoMapper stockInfoMapper;
    private final StockDailyDataMapper stockDailyDataMapper;

    public StockServiceImpl(StockInfoMapper stockInfoMapper, StockDailyDataMapper stockDailyDataMapper) {
        this.stockInfoMapper = stockInfoMapper;
        this.stockDailyDataMapper = stockDailyDataMapper;
    }

    @Override
    public List<StockInfo> searchStocks(String keyword) {
        QueryWrapper<StockInfo> wrapper = new QueryWrapper<>();
        wrapper.like("stock_code", keyword).or().like("stock_name", keyword);
        wrapper.orderByAsc("stock_code");
        wrapper.last("LIMIT 20");
        return stockInfoMapper.selectList(wrapper);
    }

    @Override
    public List<StockDailyData> getDailyData(String stockCode, String startDate, String endDate) {
        LambdaQueryWrapper<StockDailyData> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StockDailyData::getStockCode, stockCode);
        if (startDate != null && !startDate.isEmpty()) {
            wrapper.ge(StockDailyData::getTradeDate, startDate);
        }
        if (endDate != null && !endDate.isEmpty()) {
            wrapper.le(StockDailyData::getTradeDate, endDate);
        }
        wrapper.orderByAsc(StockDailyData::getTradeDate);
        return stockDailyDataMapper.selectList(wrapper);
    }

    @Override
    public StockInfo getStockInfo(String stockCode) {
        return stockInfoMapper.selectById(stockCode);
    }
}
