package com.fuli.trade.quant;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.DayOfWeek;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QuantCollectorSchedulerTest {

    @Test
    void shouldSkipWeekendsBeforeCallingTheCollector() {
        QuantCollectorScheduler scheduler = scheduler((() -> {
            throw new AssertionError("周末不应调用采集器");
        }));

        var result = scheduler.run(7L, LocalDate.of(2026, 11, 15));

        assertEquals("SKIPPED_NON_TRADING_DAY", result.status());
    }

    @Test
    void shouldRecordCollectorFailureAndKeepRetrySafeResult() {
        QuantCollectorScheduler scheduler = scheduler(() -> QuantRankingDtos.CollectResult.failure("大智慧导出文件不存在"));

        var result = scheduler.run(7L, LocalDate.of(2026, 11, 16));

        assertEquals("COLLECT_FAILED", result.status());
        assertEquals(0, result.importedRows());
        assertEquals("大智慧导出文件不存在", result.message());
    }

    @Test
    void shouldImportClipboardTextAndReturnSuccess() {
        QuantCollectorScheduler scheduler = scheduler(() -> QuantRankingDtos.CollectResult.failure("不应调用文件采集器"));

        var result = scheduler.runText(7L, LocalDate.of(2026, 11, 16),
                "代码\t名称\t人气{wyz}\n'600000\t浦发银行\t3.65\n",
                "大智慧当前窗口复制");

        assertEquals("SUCCESS", result.status());
        assertEquals(1, result.importedRows());
    }

    private QuantCollectorScheduler scheduler(QuantCollector collector) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:scheduler_test;DB_CLOSE_DELAY=-1;MODE=MySQL", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE IF NOT EXISTS quant_rank_snapshot (id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT NOT NULL,trading_date DATE NOT NULL,source_type VARCHAR(32),source_name VARCHAR(255),raw_column_mapping_json CLOB,imported_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,UNIQUE(user_id,trading_date))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS quant_rank_item (id BIGINT AUTO_INCREMENT PRIMARY KEY,snapshot_id BIGINT NOT NULL,user_id BIGINT NOT NULL,trading_date DATE NOT NULL,stock_code VARCHAR(32),stock_name VARCHAR(128),metric VARCHAR(128),metric_value DECIMAL(20,8),rank INT,raw_fields_json CLOB)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS quant_notification_config (id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT UNIQUE,enabled TINYINT,webhook_url VARCHAR(1024),updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS quant_notification_log (id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT,trading_date DATE,channel VARCHAR(32),status VARCHAR(16),message CLOB,error VARCHAR(1024),attempt_count INT,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        var repository = new QuantRankingRepository(jdbc);
        var notifier = new QuantNotificationService(repository, new WecomWebhookNotifier((url, body) -> 200));
        return new QuantCollectorScheduler(new QuantRankingService(repository, new QuantRankingParser()), notifier,
                collector, date -> date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY);
    }
}
