package com.fuli.data.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuli.data.entity.Instrument;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * 标的主表 Mapper。
 */
@Mapper
public interface InstrumentMapper extends BaseMapper<Instrument> {

    /**
     * 幂等 upsert：主键 {@code code} 冲突时刷新名称与状态。
     */
    @Insert("INSERT INTO instrument (code, market, name, currency, price_scale, lot_size, tick_size, "
            + "tz, session, status, exchange, create_time, update_time) "
            + "VALUES (#{code}, #{market}, #{name}, #{currency}, #{priceScale}, #{lotSize}, #{tickSize}, "
            + "#{tz}, #{session}, #{status}, #{exchange}, NOW(), NOW()) "
            + "ON DUPLICATE KEY UPDATE name = VALUES(name), status = VALUES(status), "
            + "price_scale = VALUES(price_scale), lot_size = VALUES(lot_size), "
            + "tick_size = VALUES(tick_size), update_time = NOW()")
    int upsert(Instrument instrument);

    /**
     * 从 {@code stock_info} 批量刷新 A 股 instrument（名称/状态），每周 stock_basic 同步后调用。
     *
     * <p>market 一律取自符号后缀：{@code stock_info.market} 存的是 Tushare 板块
     * （主板/创业板/科创板/北交所），不是交易所，不能直接当 marketCode 用。
     */
    @Insert("INSERT INTO instrument (code, market, name, currency, price_scale, lot_size, tick_size, "
            + "tz, session, status, exchange) "
            + "SELECT stock_code, SUBSTRING_INDEX(stock_code, '.', -1), COALESCE(stock_name, ''), "
            + "'CNY', 2, 100, 0.01, 'Asia/Shanghai', 'DAY', IFNULL(status, 1), NULL "
            + "FROM stock_info WHERE stock_code REGEXP '^[0-9]{6}\\\\.(SH|SZ|BJ)$' "
            + "ON DUPLICATE KEY UPDATE name = VALUES(name), status = VALUES(status), "
            + "market = VALUES(market), update_time = NOW()")
    int upsertFromStockInfo();
}
