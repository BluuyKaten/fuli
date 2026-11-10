package com.fuli.data.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuli.data.entity.StockDailyData;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 股票日线行情 Mapper（data-service 自有）。
 */
@Mapper
public interface StockDailyDataMapper extends BaseMapper<StockDailyData> {

    /**
     * 幂等 upsert：唯一键 uk_code_trade_date (stock_code, trade_date) 冲突时更新，
     * 替代「先查后插/改」的两次 SQL。
     */
    @Insert("INSERT INTO stock_daily_data (stock_code, trade_date, open_price, high_price, low_price, close_price, " +
            "pre_close, change_amount, pct_chg, vol, amount, create_time, update_time) " +
            "VALUES (#{stockCode}, #{tradeDate}, #{openPrice}, #{highPrice}, #{lowPrice}, #{closePrice}, " +
            "#{preClose}, #{changeAmount}, #{pctChg}, #{vol}, #{amount}, NOW(), NOW()) " +
            "ON DUPLICATE KEY UPDATE open_price = VALUES(open_price), high_price = VALUES(high_price), " +
            "low_price = VALUES(low_price), close_price = VALUES(close_price), pre_close = VALUES(pre_close), " +
            "change_amount = VALUES(change_amount), pct_chg = VALUES(pct_chg), vol = VALUES(vol), " +
            "amount = VALUES(amount), update_time = NOW()")
    int upsert(StockDailyData dailyData);

    /**
     * 只取收盘价序列，供全历史缩略条使用。
     *
     * <p>只查两列，不触碰其它字段，避免回表拉整行；1559 行 × 2 列的数据量在毫秒级，
     * 降采样在服务端做（LTTB），前端只拿 240 个点。
     */
    @Select("SELECT trade_date AS d, close_price AS c FROM stock_daily_data " +
            "WHERE stock_code = #{code} ORDER BY trade_date ASC")
    List<Map<String, Object>> selectCloseSeries(@Param("code") String code);
}
