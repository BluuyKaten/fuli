package com.fuli.trade.quant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

/** 显式开启时连接本机大智慧，只向隔离内存库写入，不向外发送通知。 */
@EnabledIfEnvironmentVariable(named = "FULI_DZH_LIVE", matches = "true")
class DazhihuiLiveCaptureTest {
    @Test
    void shouldCaptureRealPopularityTableAndImportIntoIsolatedDatabase() {
        var collector = new DazhihuiDesktopCollector();
        var captured = collector.collect();
        assertTrue(captured.success(), captured.failureReason());
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:live_dzh;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("RUNSCRIPT FROM 'classpath:schema-h2.sql'");
        var repository = new QuantRankingRepository(jdbc);
        var ranking = new QuantRankingService(repository, new QuantRankingParser());
        var notifications = new QuantNotificationService(repository, new WecomWebhookNotifier((url, body) -> {
            fail("现场验证不应向外发送通知"); return 500;
        }));
        var scheduler = new QuantCollectorScheduler(ranking, notifications, collector, date -> true);
        var date = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        var result = scheduler.runText(7001, date, captured.text(), captured.sourceName());
        assertEquals("SUCCESS", result.status(), result.message());
        assertTrue(result.importedRows() > 1000, "应取得完整股票列表");
        var top = ranking.daily(7001, date, 10);
        assertEquals("人气{wyz}", top.get(0).metric());
        assertEquals(10, top.size());
        assertTrue(top.get(0).metricValue().compareTo(top.get(1).metricValue()) >= 0);
        assertTrue(top.get(0).rawFields().containsKey("最新"));
        System.out.println("现场验证：导入 " + result.importedRows() + " 条，人气首名=" + top.get(0).name()
                + "，数值=" + top.get(0).metricValue() + "；" + result.message());
    }
}
