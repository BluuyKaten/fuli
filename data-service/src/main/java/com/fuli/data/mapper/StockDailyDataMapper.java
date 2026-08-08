package com.fuli.data.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuli.data.entity.StockDailyData;
import org.apache.ibatis.annotations.Mapper;

/**
 * 股票日线行情 Mapper（data-service 自有）。
 */
@Mapper
public interface StockDailyDataMapper extends BaseMapper<StockDailyData> {
}
