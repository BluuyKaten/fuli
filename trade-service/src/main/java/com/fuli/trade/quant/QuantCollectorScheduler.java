package com.fuli.trade.quant;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.stream.Collectors;

/** 统一处理采集结果、导入和通知，定时入口由自动化服务管理。 */
@Component
public class QuantCollectorScheduler {
    private final QuantRankingService rankingService;
    private final QuantNotificationService notificationService;
    private final QuantCollector collector;
    private final TradingDayCalendar calendar;

    public QuantCollectorScheduler(QuantRankingService rankingService,
                                   QuantNotificationService notificationService,
                                   QuantCollector collector,
                                   TradingDayCalendar calendar) {
        this.rankingService = rankingService;
        this.notificationService = notificationService;
        this.collector = collector;
        this.calendar = calendar;
    }

    public QuantRankingDtos.SchedulerResult run(long userId, LocalDate date) {
        return run(userId, date, "");
    }

    public QuantRankingDtos.SchedulerResult run(long userId, LocalDate date, String executablePath) {
        if (!calendar.isTradingDay(date)) {
            return new QuantRankingDtos.SchedulerResult("SKIPPED_NON_TRADING_DAY", date, 0, "非交易日，已跳过采集");
        }
        QuantRankingDtos.CollectResult collected = collector.collect(executablePath);
        if (!collected.success()) {
            notificationService.recordFailure(userId, date, collected.failureReason());
            return new QuantRankingDtos.SchedulerResult("COLLECT_FAILED", date, 0, collected.failureReason());
        }
        return runText(userId, date, collected.text(), collected.sourceName());
    }

    /** 处理已经从大智慧剪贴板或其他来源取得的表格文本。 */
    public QuantRankingDtos.SchedulerResult runText(long userId, LocalDate date, String text, String sourceName) {
        if (!calendar.isTradingDay(date)) {
            return new QuantRankingDtos.SchedulerResult("SKIPPED_NON_TRADING_DAY", date, 0, "非交易日，已跳过采集");
        }
        try {
            QuantRankingDtos.ImportResult imported = rankingService.importText(userId, date, text, sourceName, null);
            String message = buildMessage(date, imported.rowCount(), rankingService.daily(userId, date, 10), rankingService.summary(userId, 20));
            var notification = notificationService.notifyDaily(userId, date, message);
            String detail = "已按人气{wyz}导入 " + imported.rowCount() + " 条";
            if (imported.skippedRows() > 0) detail += "，跳过 " + imported.skippedRows() + " 条未计算人气的数据";
            detail += "；" + notification.message();
            return new QuantRankingDtos.SchedulerResult("SUCCESS", date, imported.rowCount(), detail);
        } catch (RuntimeException error) {
            notificationService.recordFailure(userId, date, error.getMessage());
            return new QuantRankingDtos.SchedulerResult("IMPORT_FAILED", date, 0, error.getMessage());
        }
    }

    private String buildMessage(LocalDate date, int rows, java.util.List<QuantRankingDtos.DailyItem> daily,
                                java.util.List<QuantRankingDtos.SummaryItem> summary) {
        String dailyText = daily.stream().map(item -> item.rank() + ". " + item.code() + " " + item.name() + " 人气=" + item.metricValue())
                .collect(Collectors.joining("\n"));
        String summaryText = summary.stream().limit(20).map(item -> item.code() + " " + item.name()
                        + " 出现" + item.appearanceCount() + "次，最新第" + item.latestRank() + "名")
                .collect(Collectors.joining("\n"));
        return "量化排行\n数据日期：" + date + "\n采集行数：" + rows + "\n\n当日前10：\n"
                + dailyText + "\n\n总表前20：\n" + summaryText;
    }
}
