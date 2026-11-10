package com.fuli.data.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EastMoneyUsDataSourceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parseKlinesMapsFieldOrder() throws Exception {
        // 东财日线行字段序：日期,开盘,收盘,最高,最低,成交量,成交额,...
        JsonNode klines = mapper.readTree(
                "[\"2026-11-03,325.580,331.900,334.500,325.330,63160026,20877909760.000,2.71,-1.90,-6.420,0.43\"]");
        List<UsMarket.UsCandle> candles = EastMoneyUsDataSource.parseKlines(klines);
        assertEquals(1, candles.size());
        UsMarket.UsCandle c = candles.get(0);
        assertEquals(LocalDate.of(2026, 11, 3), c.date());
        assertEquals(0, new BigDecimal("325.580").compareTo(c.open()));
        assertEquals(0, new BigDecimal("334.500").compareTo(c.high()));
        assertEquals(0, new BigDecimal("325.330").compareTo(c.low()));
        assertEquals(0, new BigDecimal("331.900").compareTo(c.close()));
        assertEquals(0, new BigDecimal("63160026").compareTo(c.volume()));
        assertEquals(0, new BigDecimal("20877909760.000").compareTo(c.amount()));
    }

    @Test
    void searchReturnsExactMatch() {
        HttpGet fake = url -> ("{\"QuotationCodeTable\":{\"Data\":["
                + "{\"Code\":\"AAPL\",\"Name\":\"苹果\",\"JYS\":\"NASDAQ\",\"Classify\":\"UsStock\",\"QuoteID\":\"105.AAPL\"},"
                + "{\"Code\":\"AAPL22\",\"Name\":\"Apple Inc Notes\",\"JYS\":\"NASDAQ\",\"Classify\":\"UsStock\",\"QuoteID\":\"105.AAPL22\"}"
                + "]}}").getBytes(StandardCharsets.UTF_8);
        EastMoneyUsDataSource ds = new EastMoneyUsDataSource(fake);
        UsMarket.UsStockMatch m = ds.search("AAPL");
        assertNotNull(m);
        assertEquals("AAPL", m.code());
        assertEquals("苹果", m.name());
        assertEquals("NASDAQ", m.exchange());
        assertEquals("105.AAPL", m.quoteId());
    }

    @Test
    void searchReturnsNullWhenNoExactMatch() {
        HttpGet fake = url -> "{\"QuotationCodeTable\":{\"Data\":[]}}".getBytes(StandardCharsets.UTF_8);
        EastMoneyUsDataSource ds = new EastMoneyUsDataSource(fake);
        assertNull(ds.search("NOPE"));
    }
}
