package com.fuli.trade.practice;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PracticeEngineTest {
    @Test
    void nextOpenFeesBenchmarkAndClosingDrawdownMatchHandCalculation() {
        var snapshot = snapshot(
                bar("2026-01-05", "10", "11", "9", "10", "100"),
                bar("2026-01-06", "11", "13", "10", "12", "100"),
                bar("2026-01-07", "13", "14", "11", "11", "100"),
                bar("2026-01-08", "10", "11", "8", "9", "100"));
        var state = create(snapshot, "100000");
        order(state, snapshot, "BUY", 100);
        assertEquals("2026-01-05", PracticeEngine.view(state, snapshot).currentDate());
        assertTrue(state.fills.isEmpty());
        PracticeEngine.advance(state, snapshot);
        money("98895.00", state.cash);
        money("100095.00", state.equity);
        money("108970.30", state.benchmarkEquity);
        assertEquals(9000, state.benchmarkQuantity);
        assertEquals(0, state.availableQuantity);
        // Selling today's purchases tomorrow is valid: settlement occurs before tomorrow's open.
        order(state, snapshot, "SELL", 100);
        PracticeEngine.advance(state, snapshot);
        money("100189.35", state.cash);
        money("10.65", state.fees);
        money("0.65", state.fills.get(1).tax());
        assertEquals(0, state.quantity);
        PracticeEngine.advance(state, snapshot);
        assertEquals("FINISHED", state.status);
        money("100189.35", state.equity);
        assertEquals(4, state.equityCurve.size());
    }

    @Test
    void heldPositionIsMarkedToMarketAndDrawdownIncludesInitialCapital() {
        var snapshot = snapshot(
                bar("2026-01-05", "10", "11", "9", "10", "100"),
                bar("2026-01-06", "10", "11", "8", "9", "100"),
                bar("2026-01-07", "9", "10", "7", "8", "100"));
        var state = create(snapshot, "100000");
        order(state, snapshot, "BUY", 100);
        PracticeEngine.advance(state, snapshot);
        money("99895", state.equity);
        money("0.00105", state.maxDrawdown);
        PracticeEngine.advance(state, snapshot);
        money("99795", state.equity);
        money("0.00205", state.maxDrawdown);
        assertEquals(100, state.quantity);
        assertEquals(1, state.fills.size());
    }

    @Test
    void gapInsufficientFundsAndUntradableBarsRejectWholeOrderWithoutRetry() {
        var snapshot = snapshot(
                bar("2026-01-05", "10", "11", "9", "10", "100"),
                bar("2026-01-06", "12", "13", "11", "12", "100"),
                bar("2026-01-07", "10", "11", "9", "10", "0"),
                bar("2026-01-08", "10", "10", "10", "10", "100"),
                bar("2026-01-09", "10", "11", "9", "10", "100"));
        var state = create(snapshot, "100000");
        order(state, snapshot, "BUY", 9000);
        PracticeEngine.advance(state, snapshot);
        assertEquals("REJECTED", state.orders.get(0).status());
        money("100000", state.cash);
        order(state, snapshot, "BUY", 100);
        PracticeEngine.advance(state, snapshot);
        assertEquals("REJECTED", state.orders.get(1).status());
        order(state, snapshot, "BUY", 100);
        PracticeEngine.advance(state, snapshot);
        assertEquals("REJECTED", state.orders.get(2).status());
        PracticeEngine.advance(state, snapshot);
        assertTrue(state.fills.isEmpty());
        money("0", state.fees);
    }

    @Test
    void benchmarkSkipsUntradableBarsButDoesNotRetryAfterFirstAffordableCheck() {
        var snapshot = snapshot(
                bar("2026-01-05", "10", "11", "9", "10", "100"),
                bar("2026-01-06", "5", "6", "4", "5", "0"),
                bar("2026-01-07", "20", "21", "19", "20", "100"),
                bar("2026-01-08", "1", "2", "1", "1", "100"));
        var state = create(snapshot, "1000");
        PracticeEngine.advance(state, snapshot);
        assertFalse(state.benchmarkAttempted);
        PracticeEngine.advance(state, snapshot);
        assertTrue(state.benchmarkAttempted);
        assertEquals(0, state.benchmarkQuantity);
        PracticeEngine.advance(state, snapshot);
        assertEquals(0, state.benchmarkQuantity);
        money("1000", state.benchmarkEquity);
    }

    @Test
    void benchmarkIncludesMinimumCommissionAndUsesLongQuantityWithoutOverflow() {
        var snapshot = snapshot(
                bar("2026-01-05", "1", "2", "1", "1", "100"),
                bar("2026-01-06", "1", "2", "1", "1", "100"));
        var small = create(snapshot, "100");
        PracticeEngine.advance(small, snapshot);
        assertEquals(0, small.benchmarkQuantity);
        var largeSnapshot = snapshot(snapshot.bars().get(0), bar("2026-01-06", "0.01", "0.02", "0.01", "0.01", "100"));
        var large = create(largeSnapshot, "1000000000");
        PracticeEngine.advance(large, largeSnapshot);
        assertTrue(large.benchmarkQuantity > Integer.MAX_VALUE);
        assertTrue(large.benchmarkCash.signum() >= 0);
        money("1000000000", large.benchmarkEquity.add(new BigDecimal("299910.03")));
    }

    @Test
    void explicitViewAndSerializedReloadDoNotLeakFutureUntilFinishAndMetricsFreeze() {
        var snapshot = snapshot(
                bar("2026-01-05", "10", "11", "9", "10", "100"),
                bar("2026-01-06", "11", "13", "10", "12", "100"),
                bar("2026-01-07", "77.1234", "80", "70", "75", "100"));
        var state = create(snapshot, "100000");
        String active = JSON.toJSONString(PracticeEngine.view(state, snapshot));
        assertFalse(active.contains("77.1234"));
        assertFalse(active.contains("preClose"));
        assertFalse(active.contains("2026-01-07"));
        state = JSON.parseObject(JSON.toJSONString(state), PracticeState.class);
        snapshot = JSON.parseObject(JSON.toJSONString(snapshot), PracticeState.Snapshot.class);
        order(state, snapshot, "BUY", 100);
        PracticeEngine.advance(state, snapshot);
        var equity = state.equity;
        var benchmark = state.benchmarkEquity;
        order(state, snapshot, "SELL", 100);
        PracticeEngine.finish(state);
        var view = PracticeEngine.view(state, snapshot);
        assertEquals(2, view.bars().size());
        assertEquals(3, view.reviewBars().size());
        money(equity.toString(), view.equity());
        money(benchmark.toString(), view.benchmarkEquity());
        assertEquals("CANCELLED", view.orders().get(1).status());
        assertNull(view.pendingOrder());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> PracticeEngine.requireMutable(stateRef(view), view.version())).getStatusCode().value());
    }

    @Test
    void validatesVersionsQuantitiesAnnotationsAndReplacementsBeforeMutation() {
        var snapshot = snapshot(bar("2026-01-05", "10", "11", "9", "10", "100"), bar("2026-01-06", "11", "13", "10", "12", "100"));
        var state = create(snapshot, "100000");
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> PracticeEngine.requireMutable(state, null)).getStatusCode().value());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> PracticeEngine.requireMutable(state, 1L)).getStatusCode().value());
        for (String invalid : List.of("0", "-100", "100.1", "99", "10000100")) {
            assertThrows(ResponseStatusException.class, () -> PracticeEngine.order(state, snapshot, new PracticeDtos.OrderRequest(0L, "BUY", new BigDecimal(invalid))));
        }
        order(state, snapshot, "BUY", 100);
        order(state, snapshot, "SELL", 100);
        assertEquals("CANCELLED", state.orders.get(0).status());
        assertTrue(state.fills.isEmpty());
        assertThrows(ResponseStatusException.class, () -> PracticeEngine.annotations(state, snapshot, List.of(
                new PracticeDtos.Annotation("a", "BUY", List.of(new PracticeDtos.Point("2026-01-06", BigDecimal.TEN)), null))));
        PracticeEngine.annotations(state, snapshot, List.of(new PracticeDtos.Annotation("a", "BUY", List.of(new PracticeDtos.Point("2026-01-05", BigDecimal.TEN)), null)));
        assertEquals(1, state.annotations.size());
        PracticeEngine.advance(state, snapshot);
        assertEquals("REJECTED", state.orders.get(1).status());
    }

    private PracticeState stateRef(PracticeDtos.PracticeView view) {
        var state = new PracticeState();
        state.version = view.version();
        state.status = view.status();
        return state;
    }

    static PracticeState create(PracticeState.Snapshot snapshot, String cash) {
        return PracticeEngine.create(new PracticeSnapshotLoader.Inputs("600519.SH", LocalDate.parse("2026-01-05"),
                LocalDate.parse("2026-01-09"), new BigDecimal(cash).setScale(2)), snapshot);
    }
    static PracticeState.Snapshot snapshot(PracticeState.FrozenBar... bars) { return new PracticeState.Snapshot(List.of(bars), 1); }
    static PracticeState.FrozenBar bar(String date, String open, String high, String low, String close, String volume) {
        return new PracticeState.FrozenBar(date, new BigDecimal(open), new BigDecimal(high), new BigDecimal(low),
                new BigDecimal(close), new BigDecimal(volume), BigDecimal.TEN);
    }
    static void order(PracticeState state, PracticeState.Snapshot snapshot, String side, long quantity) {
        PracticeEngine.order(state, snapshot, new PracticeDtos.OrderRequest(state.version, side, BigDecimal.valueOf(quantity)));
    }
    static void money(String expected, BigDecimal actual) { assertEquals(0, new BigDecimal(expected).compareTo(actual), "Expected " + expected + " but got " + actual); }
}
