package com.fuli.trade.quant;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuantRankingServiceTest {

    private JdbcTemplate jdbc;
    private QuantRankingService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:quant_service;DB_CLOSE_DELAY=-1;MODE=MySQL", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE IF NOT EXISTS quant_rank_snapshot ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, trading_date DATE NOT NULL,"
                + "source_type VARCHAR(32), source_name VARCHAR(255), raw_column_mapping_json CLOB,"
                + "imported_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, UNIQUE(user_id, trading_date))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS quant_rank_item ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY, snapshot_id BIGINT NOT NULL, user_id BIGINT NOT NULL,"
                + "trading_date DATE NOT NULL, stock_code VARCHAR(32) NOT NULL, stock_name VARCHAR(128) NOT NULL,"
                + "metric VARCHAR(128), metric_value DECIMAL(20,8), rank INT NOT NULL, raw_fields_json CLOB)");
        jdbc.update("DELETE FROM quant_rank_item");
        jdbc.update("DELETE FROM quant_rank_snapshot");
        service = new QuantRankingService(new QuantRankingRepository(jdbc), new QuantRankingParser());
    }

    @Test
    void shouldReplaceRowsWhenImportingTheSameTradingDate() {
        LocalDate date = LocalDate.of(2026, 11, 16);
        String first = "代码\t名称\t人气{wyz}\n'600001\t旧股票\t1.2\n";
        String second = "代码\t名称\t人气{wyz}\n'600002\t新股票\t9.8\n";

        service.importText(7L, date, first, "测试", null);
        var result = service.importText(7L, date, second, "测试", null);

        var daily = service.daily(7L, date, 20);
        assertEquals(1, daily.size());
        assertEquals("600002", daily.get(0).code());
        assertTrue(result.replaced());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM quant_rank_snapshot", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM quant_rank_item", Integer.class));
    }

    @Test
    void shouldStoreAllRowsButCountOnlyEachDaysPopularityTopTwenty() {
        StringBuilder text = new StringBuilder("序号\t代码\t名称\t涨幅\t人气{wyz}\n");
        for (int i = 1; i <= 22; i++) {
            text.append(i).append('\t').append(600000 + i).append("\t示例\t1%\t").append(i).append('\n');
        }
        var first = service.importText(7L, LocalDate.of(2026, 11, 16), text.toString(), "测试", null);
        service.importText(7L, LocalDate.of(2026, 11, 17),
                "序号\t代码\t名称\t涨幅\t人气{wyz}\n9\t600001\t第一只\t1%\t99\n1\t600003\t第三只\t9%\t50\n", "测试", null);

        var daily = service.daily(7L, LocalDate.of(2026, 11, 16), 100);
        var summary = service.summary(7L, 100);

        assertEquals(22, first.rowCount());
        assertEquals(22, daily.size());
        assertEquals("600022", daily.get(0).code());
        assertEquals("600001", daily.get(21).code());
        assertEquals(21, summary.size());
        assertEquals(20, service.summary(7L, 20).size());
        assertEquals(21, service.summaryCount(7L));
        assertFalse(summary.stream().anyMatch(item -> item.code().equals("600002")));
        var repeated = summary.stream().filter(item -> item.code().equals("600003")).findFirst().orElseThrow();
        assertEquals(2L, repeated.appearanceCount());
        assertEquals(2, repeated.bestRank());
        assertEquals(2, repeated.latestRank());
        assertEquals(11.0, repeated.averageRank().doubleValue(), 0.0001);
        assertEquals("2026-11-17", repeated.lastSeenDate().toString());
        var firstTime = summary.stream().filter(item -> item.code().equals("600001")).findFirst().orElseThrow();
        assertEquals(1L, firstTime.appearanceCount());
    }

    @Test
    void shouldKeepLegacyMetricSnapshotsOutOfPopularityQueries() {
        LocalDate date = LocalDate.of(2026, 11, 16);
        jdbc.update("INSERT INTO quant_rank_item(snapshot_id,user_id,trading_date,stock_code,stock_name,metric,metric_value,rank) VALUES(?,?,?,?,?,?,?,?)",
                999, 7, date, "600999", "旧口径", "涨幅", 9.9, 1);

        assertEquals(List.of(), service.daily(7L, date, 20));
        assertEquals(List.of(), service.summary(7L, 20));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM quant_rank_item", Integer.class));
    }

    @Test
    void shouldKeepExactPopularityOrderWhenStoredDecimalRoundsToEightPlaces() {
        LocalDate date = LocalDate.of(2026, 11, 16);
        service.importText(7L, date,
                "代码\t名称\t人气{wyz}\n600001\t较低\t1.000000001\n600002\t较高\t1.000000002\n", "测试", null);

        var daily = service.daily(7L, date, 20);

        assertEquals(List.of("600002", "600001"), daily.stream().map(QuantRankingDtos.DailyItem::code).toList());
        assertEquals(List.of(1, 2), daily.stream().map(QuantRankingDtos.DailyItem::rank).toList());
        assertEquals("1.000000002", daily.get(0).rawFields().get("人气{wyz}"));
    }

    @Test
    void shouldRejectSourceDateMismatchWithoutReplacingExistingSnapshot() {
        LocalDate date = LocalDate.of(2026, 11, 16);
        service.importText(7L, date, "代码\t名称\t人气{wyz}\n600001\t原快照\t1\n", "测试", null);

        var error = assertThrows(IllegalArgumentException.class, () -> service.importText(7L, date,
                "2026/11/15\t代码\t名称\t人气{wyz}\n1\t600002\t陈旧剪贴板\t99\n", "测试", null));

        assertTrue(error.getMessage().contains("2026-11-15"));
        assertTrue(error.getMessage().contains("2026-11-16"));
        assertEquals("600001", service.daily(7L, date, 20).get(0).code());
    }

    @Test
    void shouldAcceptMatchingSourceHeaderDate() {
        var result = service.importText(7L, LocalDate.of(2026, 11, 16),
                "2026/11/16\t代码\t名称\t人气{wyz}\n8\t600001\t示例\t12\n", "测试", null);

        assertEquals(1, result.rowCount());
    }

    @Test
    void shouldNotGuessDateFromSourceSequenceOrFileName() {
        var result = service.importText(7L, LocalDate.of(2026, 11, 16),
                "序号\t代码\t名称\t涨幅\t人气{wyz}\n20261115\t600001\t示例\t9%\t12\n", "2026-11-15.txt", null);

        assertEquals(1, result.rowCount());
    }

    @Test
    void shouldKeepExistingSnapshotWhenPopularityIsMissing() {
        LocalDate date = LocalDate.of(2026, 11, 16);
        service.importText(7L, date, "代码\t名称\t人气{wyz}\n600001\t原快照\t1\n", "测试", null);

        assertThrows(IllegalArgumentException.class, () -> service.importText(7L, date,
                "序号\t代码\t名称\t涨幅\n1\t600002\t错误口径\t99\n", "测试", null));

        assertEquals("600001", service.daily(7L, date, 20).get(0).code());
    }

    @Test
    void shouldReportSkippedRowsForUncalculatedPopularity() {
        var result = service.importText(7L, LocalDate.of(2026, 11, 16),
                "代码\t名称\t人气(wyz)\n600001\t已计算\t12\n600002\t未计算\t\n600003\t未计算\t--\n600004\t未计算\t—\n", "测试", null);

        assertEquals(1, result.rowCount());
        assertEquals(3, JSON.parseObject(JSON.toJSONString(result)).getInteger("skippedRows"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM quant_rank_item", Integer.class));
    }

    @Test
    void shouldKeepExistingSnapshotWhenEveryPopularityValueIsUncalculated() {
        LocalDate date = LocalDate.of(2026, 11, 16);
        service.importText(7L, date, "代码\t名称\t人气{wyz}\n600001\t原快照\t1\n", "测试", null);

        assertThrows(IllegalArgumentException.class, () -> service.importText(7L, date,
                "代码\t名称\t人气(wyz)\n600002\t未计算\t--\n600003\t未计算\t\n", "测试", null));

        assertEquals("600001", service.daily(7L, date, 20).get(0).code());
    }
}
