package com.fuli.trade.quant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuantRankingParserTest {

    private final QuantRankingParser parser = new QuantRankingParser();

    @Test
    void shouldRankByPopularityInsteadOfSourceSequenceOrPriceChange() {
        String text = "2026/11/16\t代码\t名称\t最新\t涨幅\t人气{wyz}\t换手率\n"
                + "1\t'600003\t第三只\t10.78\t9.65%\t-3.25\t0.29%\n"
                + "9\t'600002\t第二只\t8.20\t-2.00%\t10.25\t0.12%\n"
                + "2\t'600001\t第一只\t9.10\t1.00%\t10.25\t0.18%\n";

        List<QuantRankingDtos.NormalizedRow> rows = parser.parse(text, null);

        assertEquals(List.of("600001", "600002", "600003"), rows.stream().map(QuantRankingDtos.NormalizedRow::code).toList());
        assertEquals(List.of(1, 2, 3), rows.stream().map(QuantRankingDtos.NormalizedRow::rank).toList());
        assertEquals("人气{wyz}", rows.get(0).metric());
        assertEquals(new BigDecimal("-3.25"), rows.get(2).metricValue());
        assertEquals("10.78", rows.get(2).rawFields().get("最新"));
        assertEquals("9.65%", rows.get(2).rawFields().get("涨幅"));
        assertEquals("0.29%", rows.get(2).rawFields().get("换手率"));
        assertEquals("1", rows.get(2).rawFields().get("2026/11/16"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"人气{wyz}", "人气[wyz]", "人气(wyz)", " 人气 ｛ WY Z ｝ ", "人气［wyz］", "人气（wyz）", "人气【wyz】", "人气\u3000{ wyz }"})
    void shouldAcceptPopularityHeaderVariantsWithoutSourceRank(String header) {
        var rows = parser.parse("代码\t名称\t" + header + "\n'000001\t平安银行\t9.80\n", null);

        assertEquals("000001", rows.get(0).code());
        assertEquals(1, rows.get(0).rank());
        assertEquals("人气{wyz}", rows.get(0).metric());
        assertEquals(new BigDecimal("9.80"), rows.get(0).metricValue());
    }

    @Test
    void shouldSortExactDecimalsAndKeepTheLastDuplicateBeforeRanking() {
        String text = "代码,名称,人气[wyz]\n"
                + "'600001,旧名称,99\n"
                + "'600002,第二只,1.00000002\n"
                + "'600001,新名称,1.00000001\n"
                + "'600003,第三只,-1\n"
                + "'600004,第四只,-10\n";

        var rows = parser.parse(text, Map.of("metricValue", "人气[wyz]"));

        assertEquals(List.of("600002", "600001", "600003", "600004"), rows.stream().map(QuantRankingDtos.NormalizedRow::code).toList());
        assertEquals(List.of(1, 2, 3, 4), rows.stream().map(QuantRankingDtos.NormalizedRow::rank).toList());
        assertEquals("新名称", rows.get(1).name());
    }

    @Test
    void shouldCleanQuotedStockCodesWithoutRemovingMarketSuffixes() {
        String text = "代码\t名称\t人气{wyz}\n"
                + "'600000'\t浦发银行\t1\n"
                + "'000001.SZ'\t平安银行\t2\n"
                + "='430047.BJ'\t示例\t3\n";

        var rows = parser.parse(text, null);

        assertEquals(List.of("430047.BJ", "000001.SZ", "600000"), rows.stream().map(QuantRankingDtos.NormalizedRow::code).toList());
    }

    @Test
    void shouldSkipUncalculatedPopularityWithoutUsingPriceChange() {
        String text = "序号\t代码\t名称\t涨幅\t人气(wyz)\n"
                + "1\t600001\t已计算\t1%\t1.2\n"
                + "2\t600002\t空值\t99%\t\n"
                + "3\t600003\t双横线\t99%\t--\n"
                + "4\t600004\t长横线\t99%\t—\n"
                + "5\t600005\t单横线\t99%\t-\n"
                + "6\t600006\t负人气\t99%\t-3\n";

        var rows = parser.parse(text, null);

        assertEquals(List.of("600001", "600006"), rows.stream().map(QuantRankingDtos.NormalizedRow::code).toList());
        assertEquals(List.of(1, 2), rows.stream().map(QuantRankingDtos.NormalizedRow::rank).toList());
    }

    @Test
    void shouldPreserveAllRepeatedIndicatorColumns() {
        String text = "代码\t名称\t人气(wyz)\t单量差\t单量差\t六剑合一\t六剑合一\n"
                + "600001\t示例\t1.2\t-6\t-412\t\t9\n";

        var fields = parser.parse(text, null).get(0).rawFields();

        assertEquals(7, fields.size());
        assertEquals("-6", fields.get("单量差"));
        assertEquals("-412", fields.get("单量差(2)"));
        assertEquals("", fields.get("六剑合一"));
        assertEquals("9", fields.get("六剑合一(2)"));
    }

    @Test
    void shouldRejectMissingPopularityEvenWhenPriceChangeAndPriceExist() {
        String text = "序号\t代码\t名称\t涨幅\t最新\n1\t600000\t浦发银行\t3.65%\t10.78\n";

        var error = assertThrows(IllegalArgumentException.class, () -> parser.parse(text, null));

        assertTrue(error.getMessage().contains("人气{wyz}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"人气", "人气{other}", "人气{wyz]", "人:气{wyz}", "人气:{wyz}", "人气{wyz}评分"})
    void shouldRejectSimilarButDifferentPopularityHeaders(String header) {
        String text = "序号\t代码\t名称\t涨幅\t" + header + "\n1\t600000\t浦发银行\t3.65%\t12\n";

        var error = assertThrows(IllegalArgumentException.class, () -> parser.parse(text, null));

        assertTrue(error.getMessage().contains("人气{wyz}"));
    }

    @Test
    void shouldRejectMappingPopularityToAnotherMetric() {
        String text = "排名\t代码\t名称\t涨幅\t人气{wyz}\n1\t600000\t浦发银行\t3.65%\t12\n";

        var error = assertThrows(IllegalArgumentException.class,
                () -> parser.parse(text, Map.of("metricValue", "涨幅")));

        assertTrue(error.getMessage().contains("人气{wyz}"));
    }

    @Test
    void shouldRejectMissingExplicitMappingInsteadOfFallingBack() {
        String text = "排名\t代码\t名称\t人气{wyz}\n1\t600000\t浦发银行\t12\n";

        assertThrows(IllegalArgumentException.class,
                () -> parser.parse(text, Map.of("metricValue", "人气[wyz]")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "--", "未知", "3.5%"})
    void shouldRejectMissingOrNonNumericPopularity(String value) {
        String text = "排名\t代码\t名称\t涨幅\t人气{wyz}\n1\t600000\t浦发银行\t3.65%\t" + value + "\n";

        var error = assertThrows(IllegalArgumentException.class, () -> parser.parse(text, null));

        assertTrue(error.getMessage().contains("人气{wyz}"));
    }

    @Test
    void shouldRejectWhenStockCodeIsMissing() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("名称\t人气{wyz}\n浦发银行\t12\n", null));

        assertTrue(error.getMessage().contains("代码"));
    }
}
