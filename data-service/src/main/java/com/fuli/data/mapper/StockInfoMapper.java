package com.fuli.data.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuli.data.entity.StockInfo;
import org.apache.ibatis.annotations.Mapper;

/**
 * 股票基础信息 Mapper（data-service 自有）。
 */
@Mapper
public interface StockInfoMapper extends BaseMapper<StockInfo> {
}
