package com.fuli.data.market;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 腾讯美股源的解析口径。全部用固定报文，不连网络——字段位次错一位（腾讯是「开收高低」）
 * 不会报错，只会让 K 线高低点颠倒，肉眼很难发现。
 */
class TencentUsDataSourceTest {

    @Test
    void parsesQuoteIntoExchangeAndTencentCode() {
        String body = "v_usAAPL=\"200~苹果~AAPL.OQ~353.00~348.51~349.59~3495805~0~0~0\";";

        UsMarket.UsStockMatch match = TencentUsDataSource.parseQuote("AAPL", body);

        assertNotNull(match);
        assertEquals("AAPL", match.code());
        assertEquals("苹果", match.name());
        assertEquals("NASDAQ", match.exchange());
        assertEquals("AAPL.OQ", match.quoteId());
    }

    @Test
    void mapsExchangeSuffixes() {
        assertEquals("NASDAQ", TencentUsDataSource.exchangeName("OQ"));
        assertEquals("NYSE", TencentUsDataSource.exchangeName("N"));
        assertEquals("AMEX", TencentUsDataSource.exchangeName("AM"));
    }

    @Test
    void quoteFromAnotherSymbolOrFailedStatusIsRejected() {
        assertNull(TencentUsDataSource.parseQuote("AAPL", "v_usAAPL=\"200~苹果~MSFT.OQ~1~1\";"));
        assertNull(TencentUsDataSource.parseQuote("AAPL", "v_usAAPL=\"500~苹果~AAPL.OQ~1~1\";"));
        assertNull(TencentUsDataSource.parseQuote("AAPL", ""));
    }

    @Test
    void klineRowsAreOpenCloseHighLow() {
        String json = "{\"code\":0,\"data\":{\"AAPL.OQ\":{\"qfqday\":["
                + "[\"2026-11-03\",\"325.58\",\"331.90\",\"334.50\",\"325.33\",\"63160026.00\"],"
                + "[\"2026-11-04\",\"332.10\",\"330.00\",\"333.00\",\"329.50\",\"51000000.00\"]"
                + "]}}}";

        List<UsMarket.UsCandle> candles = TencentUsDataSource.parseCandles("day", json(json));

        assertEquals(2, candles.size());
        UsMarket.UsCandle first = candles.get(0);
        assertEquals(LocalDate.of(2026, 11, 3), first.date());
        assertEquals("325.58", first.open().toPlainString());
        assertEquals("331.90", first.close().toPlainString());
        assertEquals("334.50", first.high().toPlainString());
        assertEquals("325.33", first.low().toPlainString());
        assertEquals("63160026.00", first.volume().toPlainString());
        assertNull(first.amount(), "腾讯不给成交额，不要拿 volume×close 编一个");
        // 高 >= 开/收、低 <= 开/收，字段错位时这一条会先炸
        assertTrue(first.high().compareTo(first.close()) >= 0);
        assertTrue(first.low().compareTo(first.close()) <= 0);
    }

    @Test
    void emptyPayloadIsAnEmptyListNotAnError() {
        assertTrue(TencentUsDataSource.parseCandles("day", json("{\"code\":0,\"data\":{}}")).isEmpty());
        assertTrue(TencentUsDataSource.parseCandles("day", json("{\"code\":0,\"data\":[]}")).isEmpty());
    }

    @Test
    void priceScaleFollowsTheMostPreciseClose() {
        List<UsMarket.UsCandle> candles = List.of(
                candle("10.5"), candle("10.25"), candle("10.125"));
        assertEquals(3, TencentUsDataSource.priceScale(candles));
        assertEquals(2, TencentUsDataSource.priceScale(List.of(candle("10.50"))));
    }

    @Test
    void paginationMergesPagesAscendingAndStopsWhenEmpty() {
        String firstPage = "{\"code\":0,\"data\":{\"AAPL.OQ\":{\"qfqday\":["
                + "[\"2026-11-01\",\"1\",\"2\",\"3\",\"1\",\"10\"],"
                + "[\"2026-11-02\",\"2\",\"3\",\"4\",\"2\",\"10\"]]}}}";
        String secondPage = "{\"code\":0,\"data\":{\"AAPL.OQ\":{\"qfqday\":["
                + "[\"2026-10-30\",\"1\",\"2\",\"3\",\"1\",\"10\"],"
                + "[\"2026-11-01\",\"1\",\"2\",\"3\",\"1\",\"10\"]]}}}";

        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        TencentUsDataSource ds = new TencentUsDataSource(url -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                return firstPage.getBytes(StandardCharsets.UTF_8);
            }
            if (n == 2) {
                return secondPage.getBytes(StandardCharsets.UTF_8);
            }
            return "{\"code\":0,\"data\":{}}".getBytes(StandardCharsets.UTF_8);
        });

        UsMarket.UsDailyResult result = ds.fetchDaily("AAPL.OQ", LocalDate.of(2026, 10, 1));

        assertEquals(List.of(
                LocalDate.of(2026, 10, 30),
                LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 2)), result.candles().stream().map(UsMarket.UsCandle::date).toList());
        assertEquals(3, result.candles().size(), "跨页重复的日期要去重");
        assertEquals(3, calls.get(), "第二页已覆盖 since，第三页返回空后应停止");
    }

    private static com.fasterxml.jackson.databind.JsonNode json(String text) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static UsMarket.UsCandle candle(String close) {
        java.math.BigDecimal c = new java.math.BigDecimal(close);
        return new UsMarket.UsCandle(LocalDate.of(2026, 11, 3), c, c, c, c, java.math.BigDecimal.ONE, null);
    }
}
