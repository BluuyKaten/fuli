package com.fuli.trade.practice;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.fuli.trade.practice.PracticeSnapshotLoader.bad;

/** Pure deterministic account engine; no normal accounts, events, market queries or wall-clock trading. */
public final class PracticeEngine {
    public static final long MAX_ORDER_QUANTITY = 10_000_000;
    private static final BigDecimal ZERO_MONEY = new BigDecimal("0.00");
    private PracticeEngine() {}

    public static PracticeState create(PracticeSnapshotLoader.Inputs input, PracticeState.Snapshot snapshot) {
        PracticeState state = new PracticeState();
        state.id = UUID.randomUUID().toString();
        state.stockCode = input.stockCode();
        state.startDate = input.start().toString();
        state.endDate = input.end().toString();
        state.createdAt = Instant.now().toString();
        state.backgroundCount = snapshot.backgroundCount();
        state.cursor = state.backgroundCount - 1;
        state.totalSteps = snapshot.bars().size() - state.backgroundCount;
        state.initialCash = input.cash();
        state.cash = input.cash();
        state.equity = input.cash();
        state.benchmarkCash = input.cash();
        state.benchmarkEquity = input.cash();
        state.peakEquity = input.cash();
        state.equityCurve.add(new PracticeDtos.EquityPoint(current(state, snapshot).date(), state.equity, state.benchmarkEquity));
        return state;
    }

    public static void requireMutable(PracticeState state, Long expectedVersion) {
        if (expectedVersion == null || expectedVersion < 0) throw bad("expectedVersion必填且不能为负数");
        if (expectedVersion != state.version) throw conflict("练习版本已变化，请重新读取后操作");
        if (!"ACTIVE".equals(state.status)) throw conflict("练习已结束，只能复盘");
    }

    public static void order(PracticeState state, PracticeState.Snapshot snapshot, PracticeDtos.OrderRequest request) {
        if (request == null || !("BUY".equals(request.side()) || "SELL".equals(request.side()))) {
            throw bad("side须为BUY或SELL");
        }
        BigDecimal value = request.quantity();
        if (value == null || value.signum() <= 0 || value.compareTo(BigDecimal.valueOf(MAX_ORDER_QUANTITY)) > 0
                || value.stripTrailingZeros().scale() > 0 || value.remainder(BigDecimal.valueOf(state.lotSize)).signum() != 0) {
            throw bad("quantity须为100股整数倍，且不超过10000000股");
        }
        cancelPending(state, "已替换");
        state.orders.add(new PracticeDtos.Order(UUID.randomUUID().toString(), request.side(), value.longValueExact(),
                current(state, snapshot).date(), "PENDING", null, null));
        // Holdings/cash are checked at the next open, not at submission. Today's new shares settle then.
    }

    public static void cancel(PracticeState state) {
        cancelPending(state, "用户撤单");
    }

    public static void finish(PracticeState state) {
        cancelPending(state, "练习结束撤单");
        state.status = "FINISHED";
    }

    public static void advance(PracticeState state, PracticeState.Snapshot snapshot) {
        if (state.cursor + 1 >= snapshot.bars().size()) throw conflict("没有下一根行情");
        PracticeState.FrozenBar bar = snapshot.bars().get(state.cursor + 1);
        // Simulated next trading day: settle prior purchases before matching new orders.
        state.availableQuantity = state.quantity;
        matchPending(state, bar);
        if (!state.benchmarkAttempted && tradable(bar)) {
            state.benchmarkAttempted = true;
            long quantity = affordableQuantity(state, state.benchmarkCash, bar.open());
            if (quantity > 0) {
                BigDecimal amount = money(bar.open().multiply(BigDecimal.valueOf(quantity)));
                state.benchmarkCash = money(state.benchmarkCash.subtract(amount).subtract(commission(state, amount)));
                state.benchmarkQuantity = quantity;
            }
        }
        state.cursor++;
        state.equity = equity(state.cash, state.quantity, bar.close());
        state.benchmarkEquity = equity(state.benchmarkCash, state.benchmarkQuantity, bar.close());
        state.peakEquity = state.peakEquity.max(state.equity);
        BigDecimal drawdown = ratio(state.peakEquity.subtract(state.equity), state.peakEquity);
        state.maxDrawdown = state.maxDrawdown.max(drawdown);
        state.equityCurve.add(new PracticeDtos.EquityPoint(bar.date(), state.equity, state.benchmarkEquity));
        if (state.cursor == snapshot.bars().size() - 1) finish(state);
    }

    public static void annotations(PracticeState state, PracticeState.Snapshot snapshot, List<PracticeDtos.Annotation> values) {
        if (values == null || values.size() > 200) throw bad("annotations必填且最多200条");
        Set<String> dates = new HashSet<>();
        for (int i = 0; i <= state.cursor; i++) dates.add(snapshot.bars().get(i).date());
        Set<String> ids = new HashSet<>();
        for (PracticeDtos.Annotation annotation : values) {
            if (annotation == null || annotation.id() == null || annotation.id().isBlank() || annotation.id().length() > 100
                    || !ids.add(annotation.id()) || !("LINE".equals(annotation.type()) || "BUY".equals(annotation.type()) || "SELL".equals(annotation.type()))
                    || annotation.points() == null || annotation.points().size() != ("LINE".equals(annotation.type()) ? 2 : 1)
                    || annotation.text() != null && annotation.text().length() > 1000) {
                throw bad("标注格式无效：LINE需2点，BUY/SELL需1点，id须唯一");
            }
            for (PracticeDtos.Point point : annotation.points()) {
                if (point == null || !dates.contains(point.date()) || point.price() == null || point.price().signum() <= 0
                        || point.price().precision() > 20 || point.price().scale() > 8
                        || point.price().compareTo(new BigDecimal("1000000000000")) > 0) {
                    throw bad("标注只能使用已揭晓行情日期和有效正价格");
                }
            }
        }
        state.annotations = values.stream().map(a -> new PracticeDtos.Annotation(a.id(), a.type(), List.copyOf(a.points()), a.text())).toList();
    }

    public static PracticeDtos.PracticeView view(PracticeState state, PracticeState.Snapshot snapshot) {
        BigDecimal returnRate = returns(state.equity, state.initialCash);
        BigDecimal benchmarkReturnRate = returns(state.benchmarkEquity, state.initialCash);
        return new PracticeDtos.PracticeView(state.id, state.stockCode, state.startDate, state.endDate,
                current(state, snapshot).date(), state.status, state.version, state.createdAt,
                state.initialCash, state.cash, state.quantity, state.availableQuantity,
                state.equity, returnRate, state.benchmarkEquity, benchmarkReturnRate,
                returnRate.subtract(benchmarkReturnRate), state.maxDrawdown, state.fees,
                state.backgroundCount, state.cursor - state.backgroundCount + 1, state.totalSteps,
                snapshot.bars().subList(0, state.cursor + 1).stream().map(PracticeState.FrozenBar::visible).toList(),
                "FINISHED".equals(state.status) ? snapshot.bars().stream().map(PracticeState.FrozenBar::visible).toList() : null,
                pending(state), List.copyOf(state.orders), List.copyOf(state.fills), List.copyOf(state.equityCurve),
                List.copyOf(state.annotations));
    }

    public static PracticeDtos.Summary summary(PracticeState state) {
        String date = state.equityCurve.get(state.equityCurve.size() - 1).date();
        return new PracticeDtos.Summary(state.id, state.stockCode, state.startDate, state.endDate, date,
                state.status, state.version, state.equity, returns(state.equity, state.initialCash), state.createdAt);
    }

    private static void matchPending(PracticeState state, PracticeState.FrozenBar bar) {
        PracticeDtos.Order order = pending(state);
        if (order == null) return;
        String reason = null;
        BigDecimal amount = money(bar.open().multiply(BigDecimal.valueOf(order.quantity())));
        BigDecimal commission = commission(state, amount);
        BigDecimal tax = "SELL".equals(order.side()) ? money(amount.multiply(state.sellTaxRate)) : ZERO_MONEY;
        BigDecimal fee = commission.add(tax);
        if (!tradable(bar)) reason = "该日无成交量或为一字线，未成交且不自动追单";
        else if ("BUY".equals(order.side()) && state.cash.compareTo(amount.add(fee)) < 0) reason = "开盘成交所需资金不足，整单拒绝";
        else if ("SELL".equals(order.side()) && state.availableQuantity < order.quantity()) reason = "可卖股数不足，整单拒绝";
        if (reason != null) {
            replacePending(state, new PracticeDtos.Order(order.id(), order.side(), order.quantity(), order.submittedDate(),
                    "REJECTED", reason, bar.date()));
            return;
        }
        if ("BUY".equals(order.side())) {
            state.cash = money(state.cash.subtract(amount).subtract(fee));
            state.quantity += order.quantity();
        } else {
            state.cash = money(state.cash.add(amount).subtract(fee));
            state.quantity -= order.quantity();
            state.availableQuantity -= order.quantity();
        }
        state.fees = money(state.fees.add(fee));
        state.fills.add(new PracticeDtos.Fill(UUID.randomUUID().toString(), order.id(), order.side(), bar.date(),
                bar.open(), order.quantity(), amount, commission, tax, fee));
        replacePending(state, new PracticeDtos.Order(order.id(), order.side(), order.quantity(), order.submittedDate(),
                "FILLED", null, bar.date()));
    }

    private static void cancelPending(PracticeState state, String reason) {
        PracticeDtos.Order order = pending(state);
        if (order != null) replacePending(state, new PracticeDtos.Order(order.id(), order.side(), order.quantity(),
                order.submittedDate(), "CANCELLED", reason, null));
    }

    private static PracticeDtos.Order pending(PracticeState state) {
        if (state.orders.isEmpty()) return null;
        PracticeDtos.Order last = state.orders.get(state.orders.size() - 1);
        return "PENDING".equals(last.status()) ? last : null;
    }

    private static void replacePending(PracticeState state, PracticeDtos.Order replacement) {
        state.orders.set(state.orders.size() - 1, replacement);
    }

    private static boolean tradable(PracticeState.FrozenBar bar) {
        return bar.volume().signum() > 0 && bar.high().compareTo(bar.low()) > 0;
    }

    private static long affordableQuantity(PracticeState state, BigDecimal cash, BigDecimal price) {
        // Binary search includes per-fill rounding and the minimum commission exactly.
        long low = 0;
        long high = cash.divide(price.multiply(BigDecimal.valueOf(state.lotSize)), 0, RoundingMode.DOWN).longValueExact();
        while (low < high) {
            long mid = low + (high - low + 1) / 2;
            BigDecimal amount = money(price.multiply(BigDecimal.valueOf(mid * state.lotSize)));
            if (amount.add(commission(state, amount)).compareTo(cash) <= 0) low = mid;
            else high = mid - 1;
        }
        return low * state.lotSize;
    }

    private static BigDecimal commission(PracticeState state, BigDecimal amount) {
        return money(amount.multiply(state.commissionRate).max(state.minimumCommission));
    }

    private static BigDecimal equity(BigDecimal cash, long quantity, BigDecimal close) {
        return money(cash.add(close.multiply(BigDecimal.valueOf(quantity))));
    }

    private static BigDecimal returns(BigDecimal equity, BigDecimal initial) {
        return ratio(equity.subtract(initial), initial);
    }

    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        return numerator.divide(denominator, 12, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static PracticeState.FrozenBar current(PracticeState state, PracticeState.Snapshot snapshot) {
        return snapshot.bars().get(state.cursor);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
