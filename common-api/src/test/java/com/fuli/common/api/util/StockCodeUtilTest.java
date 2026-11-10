package com.fuli.common.api.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 符号解析是全系统唯一的身份入口，错一个分支就会让行情查不到、让 T+1 判断失真，
 * 因此规则逐条钉死。
 */
class StockCodeUtilTest {

    @Test
    @DisplayName("完整符号原样返回，大小写与空白规范化")
    void resolvesQualifiedSymbols() {
        assertEquals("600519.SH", StockCodeUtil.resolve("600519.SH"));
        assertEquals("600519.SH", StockCodeUtil.resolve(" 600519.sh "));
        assertEquals("AAPL.US", StockCodeUtil.resolve("aapl.us"));
        assertEquals("BTCUSDT.CRYPTO", StockCodeUtil.resolve("BTCUSDT.CRYPTO"));
    }

    @Test
    @DisplayName("裸 6 位按 A 股规则推断，920 段必须归北交所而不是沪市")
    void infersAShareMarketFromBareCode() {
        assertEquals("600519.SH", StockCodeUtil.resolve("600519"));
        assertEquals("688981.SH", StockCodeUtil.resolve("688981"));
        assertEquals("900901.SH", StockCodeUtil.resolve("900901"));   // 沪 B
        assertEquals("000001.SZ", StockCodeUtil.resolve("000001"));
        assertEquals("300750.SZ", StockCodeUtil.resolve("300750"));
        assertEquals("200002.SZ", StockCodeUtil.resolve("200002"));   // 深 B
        assertEquals("920000.BJ", StockCodeUtil.resolve("920000"));
        assertEquals("430047.BJ", StockCodeUtil.resolve("430047"));
        assertEquals("830799.BJ", StockCodeUtil.resolve("830799"));
        assertEquals("870204.BJ", StockCodeUtil.resolve("870204"));
    }

    @Test
    @DisplayName("裸代码只兼容 A 股；美股/加密必须带后缀")
    void rejectsBareForeignCodes() {
        assertThrows(IllegalArgumentException.class, () -> StockCodeUtil.resolve("AAPL"));
        assertThrows(IllegalArgumentException.class, () -> StockCodeUtil.resolve("BTCUSDT"));
        assertThrows(IllegalArgumentException.class, () -> StockCodeUtil.resolve("700000"));
        assertThrows(IllegalArgumentException.class, () -> StockCodeUtil.resolve("60051"));
        assertThrows(IllegalArgumentException.class, () -> StockCodeUtil.resolve(""));
        assertThrows(IllegalArgumentException.class, () -> StockCodeUtil.resolve(null));
    }

    @Test
    @DisplayName("两段式符号必须市场合法")
    void rejectsIllegalMarketSuffix() {
        assertThrows(IllegalArgumentException.class, () -> StockCodeUtil.resolve("600519.HK"));
        assertThrows(IllegalArgumentException.class, () -> StockCodeUtil.resolve("600519."));
        assertThrows(IllegalArgumentException.class, () -> StockCodeUtil.resolve(".SH"));
        assertThrows(IllegalArgumentException.class, () -> StockCodeUtil.resolve("600519.SH.X"));
    }

    @Test
    @DisplayName("宽松版只返回 null，不抛异常（批量端点用）")
    void resolveOrNullNeverThrows() {
        assertNull(StockCodeUtil.resolveOrNull("AAPL"));
        assertNull(StockCodeUtil.resolveOrNull(null));
        assertNull(StockCodeUtil.resolveOrNull("   "));
        assertEquals("600519.SH", StockCodeUtil.resolveOrNull("600519"));
    }

    @Test
    @DisplayName("pureCode 只截字符串，不推断市场")
    void pureCodeStripsSuffixOnly() {
        assertEquals("600519", StockCodeUtil.pureCode("600519.SH"));
        assertEquals("AAPL", StockCodeUtil.pureCode("AAPL.US"));
        assertEquals("600519", StockCodeUtil.pureCode("600519"));
        assertEquals("", StockCodeUtil.pureCode(null));
    }

    @Test
    @DisplayName("marketOf 与 isAShareMarket 决定 T+1/T+0")
    void reportsMarket() {
        assertEquals("SH", StockCodeUtil.marketOf("600519.SH"));
        assertEquals("BJ", StockCodeUtil.marketOf("920000"));
        assertEquals("US", StockCodeUtil.marketOf("AAPL.US"));
        assertEquals("CRYPTO", StockCodeUtil.marketOf("BTCUSDT.CRYPTO"));

        assertTrue(StockCodeUtil.isAShareMarket("SH"));
        assertTrue(StockCodeUtil.isAShareMarket("SZ"));
        assertTrue(StockCodeUtil.isAShareMarket("BJ"));
        assertFalse(StockCodeUtil.isAShareMarket("US"));
        assertFalse(StockCodeUtil.isAShareMarket("CRYPTO"));
    }
}
