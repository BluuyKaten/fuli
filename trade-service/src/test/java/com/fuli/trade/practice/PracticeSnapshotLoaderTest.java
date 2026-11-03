package com.fuli.trade.practice;

import com.fuli.common.api.feign.DataFeignClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PracticeSnapshotLoaderTest {
    private DataFeignClient market;
    private PracticeSnapshotLoader loader;

    @BeforeEach
    void setUp() {
        market = mock(DataFeignClient.class);
        loader = new PracticeSnapshotLoader(market);
    }

    @Test
    void normalizesOnlyAShareCodesValidatesDatesAndBoundsMoneyAndRange() {
        assertEquals("600519.SH", inputs("600519", "2026-01-05", "2026-01-08", null).stockCode());
        assertEquals("000001.SZ", inputs("000001.sz", "20260105", "20260108", null).stockCode());
        assertEquals("920001.BJ", inputs("920001", "2026-01-05", "2026-01-08", null).stockCode());
        for (String code : List.of("00700.HK", "900901.SH", "200002.SZ", "600519.SZ", "510300.SH", "123456", "600519 OR 1=1")) {
            assertThrows(ResponseStatusException.class, () -> inputs(code, "2026-01-05", "2026-01-08", null));
        }
        for (String date : List.of("2026-02-30", "20260230", "26-01-05", "2026-1-5")) {
            assertThrows(ResponseStatusException.class, () -> inputs("600519", date, "2026-03-01", null));
        }
        for (String cash : List.of("0", "99.99", "1000000000.01", "100.001")) {
            assertThrows(ResponseStatusException.class, () -> inputs("600519", "2026-01-05", "2026-01-08", cash));
        }
        assertThrows(ResponseStatusException.class, () -> inputs("600519", "2000-01-01", "2026-01-08", null));
        assertThrows(ResponseStatusException.class, () -> inputs("600519", "2026-01-08", "2026-01-05", null));
        assertThrows(ResponseStatusException.class, () -> inputs("600519", "2026-01-05", "2099-01-01", null));
        verifyNoInteractions(market);
    }

    @Test
    void storesAtMost120BackgroundBarsSortsAndChoosesLastExistingAnchor() {
        var rows = new ArrayList<Map<String, Object>>();
        LocalDate first = LocalDate.parse("2025-08-01");
        for (int i = 0; i < 150; i++) rows.add(row(first.plusDays(i).format(DateTimeFormatter.BASIC_ISO_DATE)));
        var anchor = first.plusDays(147);
        java.util.Collections.reverse(rows);
        when(market.getDailyData(anyString(), anyString(), anyString())).thenReturn(rows);
        var snapshot = loader.load(inputs("600519", anchor.toString(), first.plusDays(149).toString(), null));
        assertEquals(120, snapshot.backgroundCount());
        assertEquals(122, snapshot.bars().size());
        assertEquals(anchor.toString(), snapshot.bars().get(119).date());
        verify(market).getDailyData("600519.SH", anchor.minusYears(3).format(DateTimeFormatter.BASIC_ISO_DATE), first.plusDays(149).format(DateTimeFormatter.BASIC_ISO_DATE));
    }

    @Test
    void nonTradingStartUsesPreviousStoredDateAndNoMissingBarsAreInvented() {
        when(market.getDailyData(anyString(), anyString(), anyString())).thenReturn(List.of(row("20260102"), row("20260106"), row("20260108")));
        var input = inputs("600519", "2026-01-05", "2026-01-08", null);
        var snapshot = loader.load(input);
        assertEquals(3, snapshot.bars().size());
        var state = PracticeEngine.create(input, snapshot);
        assertEquals("2026-01-02", PracticeEngine.view(state, snapshot).currentDate());
        assertEquals(2, state.totalSteps);
    }

    @Test
    void invalidFutureOHLCVolumeReferenceAndDuplicateDatesUseIdenticalOpaqueError() {
        for (Map.Entry<String, Object> invalid : List.<Map.Entry<String, Object>>of(
                Map.entry("openPrice", "99.8765"), Map.entry("highPrice", "8"), Map.entry("lowPrice", "11"),
                Map.entry("closePrice", "0"), Map.entry("preClose", "10.0501"), Map.entry("vol", "-1"),
                Map.entry("vol", "NaN"), Map.entry("tradeDate", "20260230"), Map.entry("stockCode", "000001.SZ"))) {
            var future = row("20260106");
            future.put(invalid.getKey(), invalid.getValue());
            opaqueFailure(List.of(row("20260105"), future));
        }
        var missing = row("20260106");
        missing.remove("preClose");
        opaqueFailure(List.of(row("20260105"), missing));
        opaqueFailure(List.of(row("20260105"), row("20260106"), row("20260106")));
    }

    @Test
    void exactlyHalfPercentContinuityAndZeroVolumeAreValidButNotExecutable() {
        var future = row("20260106");
        future.put("preClose", new BigDecimal("10.05"));
        future.put("vol", BigDecimal.ZERO);
        when(market.getDailyData(anyString(), anyString(), anyString())).thenReturn(List.of(row("20260105"), future));
        var snapshot = loader.load(inputs("600519", "2026-01-05", "2026-01-08", null));
        assertEquals(0, snapshot.bars().get(1).volume().signum());
    }

    @Test
    void missingAnchorOrNextBarRejectsAndUpstreamExceptionsNeverEchoTheirContents() {
        var input = inputs("600519", "2026-01-05", "2026-01-08", null);
        for (List<Map<String, Object>> rows : List.of(List.<Map<String, Object>>of(), List.of(row("20260105")), List.of(row("20260106")))) {
            when(market.getDailyData(anyString(), anyString(), anyString())).thenReturn(rows);
            assertEquals(400, assertThrows(ResponseStatusException.class, () -> loader.load(input)).getStatusCode().value());
        }
        when(market.getDailyData(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("2026-01-06 price99.8765 upward"));
        var failure = assertThrows(ResponseStatusException.class, () -> loader.load(input));
        assertEquals(503, failure.getStatusCode().value());
        assertFalse(failure.getReason().contains("99.8765"));
        assertFalse(failure.getReason().contains("2026-01-06"));
    }

    private void opaqueFailure(List<Map<String, Object>> rows) {
        when(market.getDailyData(anyString(), anyString(), anyString())).thenReturn(rows);
        var failure = assertThrows(ResponseStatusException.class, () -> loader.load(inputs("600519", "2026-01-05", "2026-01-08", null)));
        assertEquals(400, failure.getStatusCode().value());
        assertEquals(PracticeSnapshotLoader.DATA_ERROR, failure.getReason());
    }

    private PracticeSnapshotLoader.Inputs inputs(String code, String start, String end, String cash) {
        return loader.inputs(new PracticeDtos.CreateRequest(code, start, end, cash == null ? null : new BigDecimal(cash)));
    }

    private Map<String, Object> row(String date) {
        return new HashMap<>(Map.of("stockCode", "600519.SH", "tradeDate", date, "openPrice", BigDecimal.TEN,
                "highPrice", new BigDecimal("11"), "lowPrice", new BigDecimal("9"), "closePrice", BigDecimal.TEN,
                "preClose", BigDecimal.TEN, "vol", new BigDecimal("10000")));
    }
}
