package com.fuli.data.market;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GateDataSourceTest {

    private static final long TS0 = 1_609_459_200L;

    @Test
    void parsesGateFieldOrder() throws Exception {
        // 字段序：[ts, 计价量, close, high, low, open, 基础量, 是否收线]
        String json = "[[" + TS0 + ",\"2950000\",\"29500\",\"30000\",\"28000\",\"29000\",\"100\",true]]";
        List<CryptoCandle> candles = GateDataSource.parse(json);
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
    void skipsUnclosedCandles() throws Exception {
        String json = "[[" + TS0 + ",\"1\",\"1\",\"1\",\"1\",\"1\",\"1\",true],"
                + "[" + (TS0 + 60) + ",\"2\",\"2\",\"2\",\"2\",\"2\",\"2\",false]]";
        List<CryptoCandle> candles = GateDataSource.parse(json);
        assertEquals(1, candles.size());
        assertEquals(TS0, candles.get(0).ts());
    }
}
