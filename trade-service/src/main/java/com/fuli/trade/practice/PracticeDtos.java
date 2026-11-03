package com.fuli.trade.practice;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.math.BigDecimal;
import java.util.List;

/** Public wire types. Persistence and snapshot types are deliberately separate. */
public final class PracticeDtos {
    private PracticeDtos() {}

    public record CreateRequest(String stockCode, String startDate, String endDate, BigDecimal initialCash) {}
    public record VersionRequest(@JsonDeserialize(using = ExpectedVersionDeserializer.class) Long expectedVersion) {}
    public record OrderRequest(@JsonDeserialize(using = ExpectedVersionDeserializer.class) Long expectedVersion,
                               String side, BigDecimal quantity) {}
    public record AnnotationsRequest(@JsonDeserialize(using = ExpectedVersionDeserializer.class) Long expectedVersion,
                                     List<Annotation> annotations) {}
    public record Bar(String date, BigDecimal open, BigDecimal high, BigDecimal low,
                      BigDecimal close, BigDecimal volume) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Order(String id, String side, long quantity, String submittedDate, String status,
                        String reason, String executedDate) {}
    public record Fill(String id, String orderId, String side, String date, BigDecimal price,
                       long quantity, BigDecimal amount, BigDecimal commission, BigDecimal tax, BigDecimal fee) {}
    public record EquityPoint(String date, BigDecimal equity, BigDecimal benchmarkEquity) {}
    public record Point(String date, BigDecimal price) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Annotation(String id, String type, List<Point> points, String text) {}
    public record Summary(String id, String stockCode, String startDate, String endDate,
                          String currentDate, String status, long version, BigDecimal equity,
                          BigDecimal returnRate, String createdAt) {}
    public record PracticeView(String id, String stockCode, String startDate, String endDate,
                               String currentDate, String status, long version, String createdAt,
                               BigDecimal initialCash, BigDecimal cash, long quantity, long availableQuantity,
                               BigDecimal equity, BigDecimal returnRate, BigDecimal benchmarkEquity,
                               BigDecimal benchmarkReturnRate, BigDecimal excessReturnRate,
                               BigDecimal maxDrawdown, BigDecimal fees, int backgroundCount,
                               int revealedSteps, int totalSteps, List<Bar> bars,
                               @JsonInclude(JsonInclude.Include.NON_NULL) List<Bar> reviewBars,
                               Order pendingOrder, List<Order> orders, List<Fill> fills,
                               List<EquityPoint> equityCurve, List<Annotation> annotations) {}
}
