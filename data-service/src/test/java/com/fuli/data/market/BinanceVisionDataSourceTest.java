package com.fuli.data.market;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BinanceVisionDataSourceTest {

    private static final long DAY = 86_400L;
    private static final long TS0 = 1_609_459_200L; // 2021-01-01 00:00 UTC

    @Test
    void parsesCsvColumnsAndFiltersRange() {
        // Binance open_time 是「毫秒」：CSV 首列要写毫秒值
        String csv = (TS0 * 1000) + ",29000,30000,28000,29500,100,"
                + ((TS0 + DAY - 1) * 1000) + ",2950000,10,50,1475000,0\n"
                + ((TS0 + DAY) * 1000) + ",30000,31000,29000,30500,200,"
                + ((TS0 + 2 * DAY - 1) * 1000) + ",6100000,20,100,3050000,0\n";
        List<CryptoCandle> candles = BinanceVisionDataSource.parseCsv(csv, TS0, TS0 + DAY);
        assertEquals(1, candles.size());
        CryptoCandle c = candles.get(0);
        assertEquals(TS0, c.ts());
        assertEquals(0, new BigDecimal("29000").compareTo(c.open()));
        assertEquals(0, new BigDecimal("30000").compareTo(c.high()));
        assertEquals(0, new BigDecimal("28000").compareTo(c.low()));
        assertEquals(0, new BigDecimal("29500").compareTo(c.close()));
        assertEquals(0, new BigDecimal("100").compareTo(c.volume()));
        assertEquals(0, new BigDecimal("2950000").compareTo(c.amount()));
    }

    @Test
    void parsesMicrosecondTimestampsFrom2025Onwards() {
        // 2025 起 Binance 把 open_time 改成微秒（16 位）
        String csv = (TS0 * 1_000_000L) + ",29000,30000,28000,29500,100,"
                + ((TS0 + DAY - 1) * 1_000_000L) + ",2950000,10,50,1475000,0\n";
        List<CryptoCandle> candles = BinanceVisionDataSource.parseCsv(csv, TS0, TS0 + DAY);
        assertEquals(1, candles.size());
        assertEquals(TS0, candles.get(0).ts(), "微秒时间戳应换算成秒");
    }

    @Test
    void monthsCoversInclusiveRange() {
        assertEquals(List.of("2021-01", "2021-02", "2021-03"),
                BinanceVisionDataSource.months(
                        TS0, // 2021-01-01
                        TS0 + 90L * DAY)); // 2021-04-01
    }

    @Test
    void monthsSingleMonth() {
        assertEquals(List.of("2021-01"),
                BinanceVisionDataSource.months(TS0, TS0 + DAY));
    }
}
