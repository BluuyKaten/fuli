package com.fuli.trade.quant;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.List;

/** 量化排行模块的接口数据类型。 */
public final class QuantRankingDtos {
    private QuantRankingDtos() {
    }

    public record NormalizedRow(String code, String name, String metric,
                                BigDecimal metricValue, Integer rank,
                                Map<String, String> rawFields) {
    }

    public record ImportResult(LocalDate tradingDate, int rowCount, boolean replaced, int skippedRows) {
        public ImportResult(LocalDate tradingDate, int rowCount, boolean replaced) {
            this(tradingDate, rowCount, replaced, 0);
        }
    }

    public record DailyItem(String code, String name, String metric,
                            BigDecimal metricValue, Integer rank,
                            Map<String, String> rawFields) {
    }

    public record SummaryItem(String code, String name, long appearanceCount,
                              BigDecimal averageRank, Integer bestRank,
                              Integer latestRank, LocalDate lastSeenDate) {
    }

    public record DailyData(LocalDate date, List<DailyItem> rows) {
    }

    public record SummaryData(List<SummaryItem> rows, long total) {
    }

    public record LogItem(long id, LocalDate date, String status, String source,
                          String message, Integer importedCount, LocalDateTime createdAt) {
    }

    public record NotificationConfig(boolean enabled, String webhookUrl,
                                     LocalDateTime updatedAt) {
    }

    public record NotificationResult(boolean sent, String status, String message) {
    }

    public record CollectResult(boolean success, String sourceName, String text,
                               String failureReason) {
        public static CollectResult success(String sourceName, String text) {
            return new CollectResult(true, sourceName, text, null);
        }

        public static CollectResult failure(String reason) {
            return new CollectResult(false, null, null, reason);
        }
    }

    public record SchedulerResult(String status, LocalDate tradingDate,
                                  int importedRows, String message) {
    }
}
