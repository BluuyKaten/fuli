package com.fuli.data.okx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuli.data.entity.Bar;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * OKX K 线解析的三个易错点：
 *
 * <p>1. <b>毫秒 vs 秒</b>：OKX 给 13 位毫秒，{@code bar.ts} 是秒，漏除 1000 会让所有时间戳跑到 5 万年后；
 * <p>2. <b>confirm</b>：未完结那根的 OHLC 还在变，写进事实表就是把瞬时快照当历史；
 * <p>3. <b>成交额取哪一列</b>：现货是第 7 列 volCcyQuote，退路是第 6 列 volCcy，
 *    取错列不会报错，只会让所有成交额差一个数量级。
 */
class OkxCandleParseTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 真实形状：9 个字符串，时间倒序里的最新一根。 */
    private static final String ONE_ROW =
            "[[\"1735689600000\",\"93000.1\",\"93500.2\",\"92800.0\",\"93200.5\","
                    + "\"123.45\",\"11500000.5\",\"11500000.5\",\"1\"]]";

    private static JsonNode row(String json, int index) throws Exception {
        return JSON.readTree(json).get(index);
    }

    @Test
    @DisplayName("毫秒 → 秒，价格与量原样（不做 ×100/×1000）")
    void mapsRowToBar() throws Exception {
        Bar bar = OkxMarketService.toBar("BTC-USDT.CRYPTO", "1d", row(ONE_ROW, 0));
        assertNotNull(bar);
        assertEquals("BTC-USDT.CRYPTO", bar.getInstrumentCode());
        assertEquals("1d", bar.getPeriod());
        assertEquals(1735689600L, bar.getTs(), "毫秒必须除 1000");
        assertEquals(0, new BigDecimal("93000.1").compareTo(bar.getOpenPrice()));
        assertEquals(0, new BigDecimal("93500.2").compareTo(bar.getHighPrice()));
        assertEquals(0, new BigDecimal("92800.0").compareTo(bar.getLowPrice()));
        assertEquals(0, new BigDecimal("93200.5").compareTo(bar.getClosePrice()));
        // vol 是基础币个数，不是 A 股那种「手」
        assertEquals(0, new BigDecimal("123.45").compareTo(bar.getVolume()));
        assertEquals(0, new BigDecimal("11500000.5").compareTo(bar.getAmount()));
        assertNull(bar.getPreClose(), "OKX 不给 pre_close，硬算会误导指标");
    }

    @Test
    @DisplayName("confirm=0 的未完结 K 线必须丢掉")
    void skipsUnconfirmed() {
        JsonNode unconfirmed = null;
        try {
            unconfirmed = JSON.readTree(
                    "[[\"1735689600000\",\"1\",\"1\",\"1\",\"1\",\"1\",\"1\",\"1\",\"0\"]]").get(0);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertNull(OkxMarketService.toBar("BTC-USDT.CRYPTO", "1d", unconfirmed));
    }

    @Test
    @DisplayName("成交额优先级：volCcyQuote(7) → volCcy(6)")
    void amountFallsBackToVolCcy() throws Exception {
        JsonNode row = JSON.readTree(
                "[[\"1735689600000\",\"1\",\"2\",\"0.5\",\"1.5\",\"10\",\"11.5\",\"\",\"1\"]]").get(0);
        Bar bar = OkxMarketService.toBar("BTC-USDT.CRYPTO", "1d", row);
        assertNotNull(bar);
        assertEquals(0, new BigDecimal("11.5").compareTo(bar.getAmount()));
    }

    @Test
    @DisplayName("列数不足的行直接跳过，不要用 null 价格写库")
    void skipsMalformedRow() throws Exception {
        JsonNode shortRow = JSON.readTree("[[\"1735689600000\",\"1\",\"2\"]]").get(0);
        assertNull(OkxMarketService.toBar("BTC-USDT.CRYPTO", "1d", shortRow));
        assertNull(OkxMarketService.toBar("BTC-USDT.CRYPTO", "1d", null));
    }

    @Test
    @DisplayName("翻页游标取本批最旧一根（返回是倒序，最后一行最旧）")
    void oldestTimestampForPaging() throws Exception {
        JsonNode batch = JSON.readTree(
                "[[\"1735776000000\",\"1\",\"1\",\"1\",\"1\",\"1\",\"1\",\"1\",\"1\"],"
                        + "[\"1735689600000\",\"1\",\"1\",\"1\",\"1\",\"1\",\"1\",\"1\",\"1\"]]");
        assertEquals(1735689600000L, OkxMarketService.oldestTsMs(batch));
        assertEquals(0, OkxMarketService.oldestTsMs(JSON.readTree("[]")));
    }

    @Test
    @DisplayName("价格精度由 tickSz 的小数位决定")
    void priceScaleFromTickSize() {
        assertEquals(1, OkxMarketService.scaleOf("0.1"));
        assertEquals(8, OkxMarketService.scaleOf("0.00000001"));
        assertEquals(0, OkxMarketService.scaleOf("1"));
        assertEquals(2, OkxMarketService.scaleOf(""), "缺失时退回 2 位，与 A 股一致");
    }
}
