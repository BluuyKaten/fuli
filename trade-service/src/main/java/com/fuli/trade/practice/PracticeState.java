package com.fuli.trade.practice;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Server-only mutable state, serialized under the same database lock as its version. */
public final class PracticeState {
    public String id;
    public String stockCode;
    public String startDate;
    public String endDate;
    public String createdAt;
    public String status = "ACTIVE";
    public long version;
    public int cursor;
    public int backgroundCount;
    public int totalSteps;
    public BigDecimal initialCash;
    public BigDecimal cash;
    public long quantity;
    public long availableQuantity;
    public BigDecimal equity;
    public BigDecimal benchmarkCash;
    public long benchmarkQuantity;
    public boolean benchmarkAttempted;
    public BigDecimal benchmarkEquity;
    public BigDecimal peakEquity;
    public BigDecimal maxDrawdown = BigDecimal.ZERO;
    public BigDecimal fees = new BigDecimal("0.00");
    // A copy of all training rules is locked into every created practice.
    public BigDecimal commissionRate = new BigDecimal("0.0003");
    public BigDecimal minimumCommission = new BigDecimal("5.00");
    public BigDecimal sellTaxRate = new BigDecimal("0.0005");
    public int lotSize = 100;
    public String ruleVersion = "A_SHARE_DAILY_V1";
    public List<PracticeDtos.Order> orders = new ArrayList<>();
    public List<PracticeDtos.Fill> fills = new ArrayList<>();
    public List<PracticeDtos.EquityPoint> equityCurve = new ArrayList<>();
    public List<PracticeDtos.Annotation> annotations = new ArrayList<>();

    /** Immutable market input; never returned directly by a controller. */
    public record FrozenBar(String date, BigDecimal open, BigDecimal high, BigDecimal low,
                            BigDecimal close, BigDecimal volume, BigDecimal preClose) {
        PracticeDtos.Bar visible() {
            return new PracticeDtos.Bar(date, open, high, low, close, volume);
        }
    }
    public record Snapshot(List<FrozenBar> bars, int backgroundCount) {
        public Snapshot {
            bars = List.copyOf(bars);
        }
    }
}
