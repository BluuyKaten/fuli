package com.fuli.trade.quant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 量化排行导入和历史聚合服务。 */
@Service
public class QuantRankingService {
    private final QuantRankingRepository repository;
    private final QuantRankingParser parser;

    public QuantRankingService(QuantRankingRepository repository, QuantRankingParser parser) {
        this.repository = repository;
        this.parser = parser;
    }

    @Transactional(rollbackFor = Exception.class)
    public QuantRankingDtos.ImportResult importText(long userId, LocalDate tradingDate,
                                                     String text, String sourceName,
                                                     Map<String, String> mapping) {
        if (tradingDate == null) {
            throw new IllegalArgumentException("交易日不能为空");
        }
        LocalDate sourceDate = parser.extractSourceDate(text);
        if (sourceDate != null && !sourceDate.equals(tradingDate)) {
            throw new IllegalArgumentException("源数据日期“" + sourceDate + "”与导入交易日“" + tradingDate + "”不一致，请重新复制对应日期的数据");
        }
        QuantRankingParser.ParsedRanking parsed = parser.parseResult(text, mapping);
        List<QuantRankingDtos.NormalizedRow> rows = parsed.rows();
        boolean replaced = repository.snapshotExists(userId, tradingDate);
        repository.replaceSnapshot(userId, tradingDate, sourceName, mapping, rows);
        return new QuantRankingDtos.ImportResult(tradingDate, rows.size(), replaced, parsed.skippedRows());
    }

    public List<QuantRankingDtos.DailyItem> daily(long userId, LocalDate tradingDate, int limit) {
        if (tradingDate == null) {
            throw new IllegalArgumentException("交易日不能为空");
        }
        return repository.findDaily(userId, tradingDate, Math.max(1, Math.min(limit, 1000)));
    }

    public List<QuantRankingDtos.SummaryItem> summary(long userId, int limit) {
        Map<String, SummaryAccumulator> grouped = new LinkedHashMap<>();
        for (QuantRankingRepository.StoredItem item : repository.findAll(userId)) {
            SummaryAccumulator accumulator = grouped.computeIfAbsent(item.code(), ignored -> new SummaryAccumulator(item.code(), item.name()));
            accumulator.add(item);
        }
        return grouped.values().stream().map(SummaryAccumulator::toItem)
                .sorted((left, right) -> {
                    int count = Long.compare(right.appearanceCount(), left.appearanceCount());
                    if (count != 0) return count;
                    int average = left.averageRank().compareTo(right.averageRank());
                    if (average != 0) return average;
                    return left.code().compareTo(right.code());
                })
                .limit(Math.max(1, Math.min(limit, 1000))).toList();
    }

    public long summaryCount(long userId) {
        return repository.findAll(userId).stream().map(QuantRankingRepository.StoredItem::code).distinct().count();
    }

    public List<QuantRankingDtos.LogItem> logs(long userId, int limit) {
        return repository.findLogs(userId, limit).stream()
                .map(log -> new QuantRankingDtos.LogItem(log.id(), log.date(), toChineseStatus(log.status()),
                        log.source(), log.message(), null, log.createdAt()))
                .toList();
    }

    private String toChineseStatus(String status) {
        return switch (status) {
            case "SENT" -> "成功";
            case "SKIPPED" -> "跳过";
            case "FAILED" -> "失败";
            default -> status;
        };
    }

    private static final class SummaryAccumulator {
        private final String code;
        private String name;
        private long count;
        private BigDecimal rankTotal = BigDecimal.ZERO;
        private int best = Integer.MAX_VALUE;
        private Integer latest;
        private LocalDate lastDate;

        private SummaryAccumulator(String code, String name) {
            this.code = code;
            this.name = name;
        }

        private void add(QuantRankingRepository.StoredItem item) {
            if (count == 0) {
                name = item.name();
            }
            count++;
            rankTotal = rankTotal.add(BigDecimal.valueOf(item.rank()));
            best = Math.min(best, item.rank());
            if (latest == null) {
                latest = item.rank();
                lastDate = item.tradingDate();
            }
        }

        private QuantRankingDtos.SummaryItem toItem() {
            return new QuantRankingDtos.SummaryItem(code, name, count,
                    rankTotal.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP), best, latest, lastDate);
        }

        private String code() { return code; }
        private long appearanceCount() { return count; }
        private BigDecimal averageRank() { return rankTotal.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP); }
    }
}
