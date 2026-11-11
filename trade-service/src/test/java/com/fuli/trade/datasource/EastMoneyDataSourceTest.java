package com.fuli.trade.datasource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 东财适配器的口径测试。
 *
 * <p>重点钉住两件曾经错过、且错了很难被人眼发现的事：
 * <ol>
 *   <li><b>klt 映射</b>：周线是 102、月线是 103。写成 101/102 会「周线显示日线、月线显示周线」，
 *       都有数据、形态也像，不看成交量周期根本看不出来。</li>
 *   <li><b>secid 前缀</b>：美股必须用真实交易所的 105/106/107，
 *       前缀错了东财返回 {@code data:null} 而不是报错。</li>
 * </ol>
 */
class EastMoneyDataSourceTest {

    private final EastMoneyDataSource dataSource = new EastMoneyDataSource();

    // ---------------------------------------------------------------- secid

    @Test
    void aShareSecidUsesExchangePrefix() {
        assertEquals("1.600519", dataSource.secidOf("600519.SH"));
        assertEquals("0.000001", dataSource.secidOf("000001.SZ"));
        assertEquals("0.300750", dataSource.secidOf("300750.SZ"));
    }

    @Test
    void beijingExchangeSharesShenzhenPrefix() {
        // 东财现状用 0 前缀（与深市同号）；改成 1 会让北交所标的全部取不到数据
        assertEquals("0.920001", dataSource.secidOf("920001.BJ"));
        assertEquals("0.430047", dataSource.secidOf("430047.BJ"));
    }

    @Test
    void bareSixDigitCodeStillResolves() {
        // 兼容旧调用方：裸 6 位按 A 股规则推断
        assertEquals("1.600519", dataSource.secidOf("600519"));
    }

    @Test
    void cryptoMarketIsRejected() {
        // 加密走 /market/bars，不该落进东财这条链路
        assertThrows(IllegalArgumentException.class, () -> dataSource.secidOf("BTC-USDT.CRYPTO"));
    }

    // ---------------------------------------------------------------- 美股 QuoteID

    @Test
    void picksExactMatchAndSkipsOtherAssetClasses() throws Exception {
        JsonNode items = new ObjectMapper().readTree("""
                [
                  {"Classify":"UsStock","Code":"AAPL.WS","QuoteID":"105.AAPL.WS"},
                  {"Classify":"UsStock","Code":"AAPLL","QuoteID":"105.AAPLL"},
                  {"Classify":"UsStock","Code":"AAPL","QuoteID":"105.AAPL"}
                ]
                """);
        assertEquals("105.AAPL", EastMoneyDataSource.pickUsQuoteId(items, "AAPL"));
    }

    @Test
    void honoursNyseAndAmexPrefixes() throws Exception {
        JsonNode items = new ObjectMapper().readTree("""
                [
                  {"Classify":"UsStock","Code":"BABA","QuoteID":"106.BABA"},
                  {"Classify":"UsStock","Code":"SPY","QuoteID":"107.SPY"}
                ]
                """);
        assertEquals("106.BABA", EastMoneyDataSource.pickUsQuoteId(items, "BABA"));
        assertEquals("107.SPY", EastMoneyDataSource.pickUsQuoteId(items, "SPY"));
    }

    @Test
    void skipsNonUsClassify() throws Exception {
        JsonNode items = new ObjectMapper().readTree("""
                [
                  {"Classify":"HKStock","Code":"00700","QuoteID":"116.00700"},
                  {"Classify":"UsStock","Code":"TCEHY","QuoteID":"105.TCEHY"}
                ]
                """);
        // 搜腾讯时港股那条不能顶上来
        assertEquals("105.TCEHY", EastMoneyDataSource.pickUsQuoteId(items, "TCEHY"));
        assertNull(EastMoneyDataSource.pickUsQuoteId(items, "00700"));
    }

    @Test
    void returnsNullWhenNothingMatches() throws Exception {
        assertNull(EastMoneyDataSource.pickUsQuoteId(new ObjectMapper().readTree("[]"), "AAPL"));
        assertNull(EastMoneyDataSource.pickUsQuoteId(null, "AAPL"));
    }

    // ---------------------------------------------------------------- klt

    @Test
    void kltConstantsMatchEastMoneySemantics() {
        assertEquals(101, EastMoneyDataSource.KLT_DAILY);
        assertEquals(102, EastMoneyDataSource.KLT_WEEKLY, "周线必须是 102；写 101 会把日线当周线返回");
        assertEquals(103, EastMoneyDataSource.KLT_MONTHLY, "月线必须是 103；写 102 会把周线当月线返回");
    }

    @Test
    void klineUrlCarriesExpectedKlt() {
        assertTrue(dataSource.klineUrl("1.600519", EastMoneyDataSource.KLT_WEEKLY, 5000).contains("klt=102"));
        assertTrue(dataSource.klineUrl("1.600519", EastMoneyDataSource.KLT_MONTHLY, 5000).contains("klt=103"));
        assertTrue(dataSource.klineUrl("105.AAPL", EastMoneyDataSource.KLT_DAILY, 5000).contains("klt=101"));
    }
}
