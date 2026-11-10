package com.fuli.data.tushare;

import com.fuli.data.entity.Bar;
import com.fuli.data.entity.StockDailyData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 单位换算与时间轴是阶段 0 唯一的「致命-静默」故障点：
 * 方向写反或漏乘不会报错，只会让均线、成交额、指标全部失真。
 * 这里把两件事钉死——换算系数，以及交易日 → UTC 秒的取值。
 */
class DailyBarConversionTest {

    @Test
    @DisplayName("Tushare 口径 → bar 口径：vol×100（手→股）、amount×1000（千元→元）")
    void convertsTushareUnits() {
        StockDailyData raw = new StockDailyData();
        raw.setStockCode("600519.SH");
        raw.setTradeDate(20261103);
        raw.setOpenPrice(new BigDecimal("1500.0000"));
        raw.setHighPrice(new BigDecimal("1525.5000"));
        raw.setLowPrice(new BigDecimal("1490.0000"));
        raw.setClosePrice(new BigDecimal("1518.0000"));
        raw.setPreClose(new BigDecimal("1510.0000"));
        raw.setVol(new BigDecimal("123"));          // 手
        raw.setAmount(new BigDecimal("1.5"));       // 千元

        Bar bar = TushareSyncServiceImpl.toBar(raw);

        assertEquals("600519.SH", bar.getInstrumentCode());
        assertEquals("1d", bar.getPeriod());
        assertEquals(0, new BigDecimal("12300").compareTo(bar.getVolume()), "123 手应为 12300 股");
        assertEquals(0, new BigDecimal("1500").compareTo(bar.getAmount()), "1.5 千元应为 1500 元");
        // OHLC 与 pre_close 原样搬运，不做任何缩放
        assertEquals(0, raw.getOpenPrice().compareTo(bar.getOpenPrice()));
        assertEquals(0, raw.getClosePrice().compareTo(bar.getClosePrice()));
        assertEquals(0, raw.getPreClose().compareTo(bar.getPreClose()));
    }

    @Test
    @DisplayName("pre_close 缺失时保留 null，不补 0")
    void keepsNullPreClose() {
        StockDailyData raw = new StockDailyData();
        raw.setStockCode("000001.SZ");
        raw.setTradeDate(20261103);
        raw.setVol(new BigDecimal("10"));
        raw.setAmount(new BigDecimal("2"));

        Bar bar = TushareSyncServiceImpl.toBar(raw);
        assertNull(bar.getPreClose());
        assertEquals(0, new BigDecimal("1000").compareTo(bar.getVolume()));
        assertEquals(0, new BigDecimal("2000").compareTo(bar.getAmount()));
    }

    @Test
    @DisplayName("空值不放大：vol/amount 为 null 时仍为 null")
    void keepsNullVolumeAndAmount() {
        StockDailyData raw = new StockDailyData();
        raw.setStockCode("600000.SH");
        raw.setTradeDate(20261103);

        Bar bar = TushareSyncServiceImpl.toBar(raw);
        assertNull(bar.getVolume());
        assertNull(bar.getAmount());
    }

    @Test
    @DisplayName("交易日 09:30 CST = 当日 01:30 UTC")
    void mapsTradeDateToUtcOpen() {
        // 2026-11-03 01:30:00 UTC = 1793669400（与迁移脚本 UNIX_TIMESTAMP(...)-28800 在 UTC 会话下一致）
        assertEquals(1793669400L, TushareSyncServiceImpl.tradeDateToTs(20261103));
        assertNull(TushareSyncServiceImpl.tradeDateToTs(null));
    }
}
