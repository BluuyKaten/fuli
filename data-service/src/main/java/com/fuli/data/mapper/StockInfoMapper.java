package com.fuli.data.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuli.data.entity.StockInfo;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * 股票基础信息 Mapper（data-service 自有）。
 */
@Mapper
public interface StockInfoMapper extends BaseMapper<StockInfo> {

    /**
     * 幂等 upsert：主键 stock_code 冲突时更新，替代「先查后插/改」的两次 SQL。
     */
    @Insert("INSERT INTO stock_info (stock_code, stock_name, area, industry, list_date, market, status, create_time, update_time) " +
            "VALUES (#{stockCode}, #{stockName}, #{area}, #{industry}, #{listDate}, #{market}, #{status}, NOW(), NOW()) " +
            "ON DUPLICATE KEY UPDATE stock_name = VALUES(stock_name), area = VALUES(area), industry = VALUES(industry), " +
            "list_date = VALUES(list_date), market = VALUES(market), status = VALUES(status), update_time = NOW()")
    int upsert(StockInfo stockInfo);
}
