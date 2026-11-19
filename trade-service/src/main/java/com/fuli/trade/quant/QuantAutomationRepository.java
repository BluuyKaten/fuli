package com.fuli.trade.quant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 持久化自动采集开关、当天执行次数和最后结果。 */
@Repository
public class QuantAutomationRepository {
    private final JdbcTemplate jdbc;

    public QuantAutomationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public State get(long userId) {
        return jdbc.query("SELECT * FROM quant_automation_config WHERE user_id=?", (rs, n) -> {
            var attempt = rs.getTimestamp("last_attempt_at");
            var date = rs.getDate("last_run_date");
            var success = rs.getDate("last_success_date");
            return new State(userId, new QuantAutomationConfig(rs.getBoolean("enabled"),
                    rs.getString("executable_path"), "15:10", rs.getBoolean("startup_catch_up")),
                    attempt == null ? null : attempt.toLocalDateTime(),
                    success == null ? null : success.toLocalDate(), rs.getInt("attempt_count"),
                    date == null ? null : new QuantRankingDtos.SchedulerResult(rs.getString("last_status"),
                            date.toLocalDate(), rs.getInt("last_imported_rows"), rs.getString("last_message")));
        }, userId).stream().findFirst().orElse(new State(userId,
                new QuantAutomationConfig(false, "", "15:10", true), null, null, 0, null));
    }

    public void save(long userId, QuantAutomationConfig config) {
        int changed = jdbc.update("UPDATE quant_automation_config SET enabled=?,executable_path=?,startup_catch_up=? WHERE user_id=?",
                config.enabled(), config.executablePath(), config.startupCatchUp(), userId);
        if (changed == 0) {
            jdbc.update("INSERT INTO quant_automation_config(user_id,enabled,executable_path,startup_catch_up) VALUES(?,?,?,?)",
                    userId, config.enabled(), config.executablePath(), config.startupCatchUp());
        }
    }

    public List<Long> enabledUsers() {
        return jdbc.query("SELECT user_id FROM quant_automation_config WHERE enabled=1 ORDER BY user_id", (rs,n) -> rs.getLong(1));
    }

    public void record(long userId, LocalDateTime now, QuantRankingDtos.SchedulerResult result) {
        State previous = get(userId);
        jdbc.update("INSERT INTO quant_automation_config(user_id) SELECT ? WHERE NOT EXISTS (SELECT 1 FROM quant_automation_config WHERE user_id=?)", userId, userId);
        int attempts = previous.lastAttempt() != null && previous.lastAttempt().toLocalDate().equals(now.toLocalDate())
                ? previous.attemptCount() + 1 : 1;
        LocalDate successDate = "SUCCESS".equals(result.status()) ? result.tradingDate() : previous.lastSuccess();
        jdbc.update("UPDATE quant_automation_config SET last_attempt_at=?,last_success_date=?,attempt_count=?,last_run_date=?,last_status=?,last_imported_rows=?,last_message=? WHERE user_id=?",
                now, successDate, attempts, result.tradingDate(), result.status(), result.importedRows(), result.message(), userId);
    }

    public record State(long userId, QuantAutomationConfig config, LocalDateTime lastAttempt,
                        LocalDate lastSuccess, int attemptCount, QuantRankingDtos.SchedulerResult lastRun) {}
}
