package com.fuli.data.okx;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 符号映射必须同时吃下两种输入：配置里的裸 instId、接口里的完整符号。
 *
 * <p>回归背景：定时任务拿配置里的 {@code BTC-USDT} 去调 {@code toInstId}，
 * 早期实现一律按「完整符号」交给 {@code StockCodeUtil.resolve} 解析，
 * 结果 3 个标的全被拒、日志只留 WARN、一根 K 线都没写——典型的静默失败。
 */
class OkxSymbolsTest {

    @Test
    @DisplayName("裸 instId 与完整符号都能转成 .CRYPTO")
    void toSymbolAcceptsBothForms() {
        assertEquals("BTC-USDT.CRYPTO", OkxSymbols.toSymbol("BTC-USDT"));
        assertEquals("BTC-USDT.CRYPTO", OkxSymbols.toSymbol("BTC-USDT.CRYPTO"));
        assertEquals("BTC-USDT-SWAP.CRYPTO", OkxSymbols.toSymbol("BTC-USDT-SWAP"));
        assertEquals("1INCH-USDT.CRYPTO", OkxSymbols.toSymbol("1INCH-USDT"));
    }

    @Test
    @DisplayName("横线必须保留，去横线会让 1INCH-USDT 与 USDT-USDC 这类歧义")
    void keepsHyphensVerbatim() {
        assertEquals("USDT-USDC.CRYPTO", OkxSymbols.toSymbol("USDT-USDC"));
        assertEquals("BABYDOGE-USDT.CRYPTO", OkxSymbols.toSymbol("BABYDOGE-USDT"));
    }

    @Test
    @DisplayName("toInstId：两种输入都还原成 OKX 原生 ID，且可逆")
    void toInstIdIsReversible() {
        for (String id : new String[]{"BTC-USDT", "ETH-USDT", "BTC-USDT-SWAP", "1INCH-USDT"}) {
            assertEquals(id, OkxSymbols.toInstId(OkxSymbols.toSymbol(id)));
            assertEquals(id, OkxSymbols.toInstId(id));
        }
    }

    @Test
    @DisplayName("别家市场符号必须拒绝，避免把 A 股代码发给 OKX")
    void rejectsForeignMarkets() {
        for (String bad : new String[]{"600519.SH", "AAPL.US", "600519"}) {
            // 裸 6 位数字不是合法 OKX instId，也应拒绝
            assertThrows(IllegalArgumentException.class, () -> OkxSymbols.toInstId(bad), bad);
        }
    }

    @Test
    @DisplayName("空值直接报参数错")
    void rejectsBlank() {
        assertThrows(IllegalArgumentException.class, () -> OkxSymbols.toSymbol(""));
        assertThrows(IllegalArgumentException.class, () -> OkxSymbols.toInstId(null));
        assertThrows(IllegalArgumentException.class, () -> OkxSymbols.toSymbol("  "));
    }
}
