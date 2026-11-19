package com.fuli.trade.quant;

import com.alibaba.fastjson2.JSON;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 量化排行持久化访问，集中处理快照替换和通知日志。 */
@Repository
public class QuantRankingRepository {
    private final JdbcTemplate jdbc;

    public QuantRankingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean snapshotExists(long userId, LocalDate date) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM quant_rank_snapshot WHERE user_id=? AND trading_date=?",
                Integer.class, userId, date);
        return count != null && count > 0;
    }

    public long replaceSnapshot(long userId, LocalDate date, String sourceName,
                                Map<String, String> mapping,
                                List<QuantRankingDtos.NormalizedRow> rows) {
        List<Long> ids = jdbc.query("SELECT id FROM quant_rank_snapshot WHERE user_id=? AND trading_date=?",
                (rs, rowNum) -> rs.getLong(1), userId, date);
        for (Long id : ids) {
            jdbc.update("DELETE FROM quant_rank_item WHERE snapshot_id=?", id);
        }
        jdbc.update("DELETE FROM quant_rank_snapshot WHERE user_id=? AND trading_date=?", userId, date);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO quant_rank_snapshot(user_id,trading_date,source_type,source_name,raw_column_mapping_json) VALUES(?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, userId);
            statement.setObject(2, date);
            statement.setString(3, "dazhihui");
            statement.setString(4, sourceName);
            statement.setString(5, mapping == null ? null : JSON.toJSONString(mapping));
            return statement;
        }, keyHolder);
        Number generatedId = null;
        if (keyHolder.getKeys() != null) {
            for (Map.Entry<String, Object> entry : keyHolder.getKeys().entrySet()) {
                if ("id".equalsIgnoreCase(entry.getKey()) && entry.getValue() instanceof Number number) {
                    generatedId = number;
                    break;
                }
            }
        }
        if (generatedId == null) {
            generatedId = keyHolder.getKey();
        }
        long snapshotId = generatedId.longValue();
        for (QuantRankingDtos.NormalizedRow row : rows) {
            jdbc.update("INSERT INTO quant_rank_item(snapshot_id,user_id,trading_date,stock_code,stock_name,metric,metric_value,`rank`,raw_fields_json) VALUES(?,?,?,?,?,?,?,?,?)",
                    snapshotId, userId, date, row.code(), row.name(), row.metric(), row.metricValue(), row.rank(),
                    JSON.toJSONString(row.rawFields()));
        }
        return snapshotId;
    }

    public List<QuantRankingDtos.DailyItem> findDaily(long userId, LocalDate date, int limit) {
        return jdbc.query("SELECT stock_code,stock_name,metric,metric_value,`rank`,raw_fields_json "
                        + "FROM quant_rank_item WHERE user_id=? AND trading_date=? AND metric=? ORDER BY `rank` ASC,stock_code ASC LIMIT ?",
                (rs, rowNum) -> new QuantRankingDtos.DailyItem(rs.getString("stock_code"), rs.getString("stock_name"),
                        rs.getString("metric"), rs.getBigDecimal("metric_value"), rs.getInt("rank"),
                        parseRaw(rs.getString("raw_fields_json"))), userId, date, QuantRankingParser.POPULARITY_METRIC, limit);
    }

    public List<StoredItem> findAll(long userId) {
        return jdbc.query("SELECT stock_code,stock_name,`rank`,trading_date FROM quant_rank_item "
                        + "WHERE user_id=? AND metric=? AND `rank` BETWEEN 1 AND 20 ORDER BY trading_date DESC, `rank` ASC",
                (rs, rowNum) -> new StoredItem(rs.getString("stock_code"), rs.getString("stock_name"),
                        rs.getInt("rank"), rs.getDate("trading_date").toLocalDate()), userId, QuantRankingParser.POPULARITY_METRIC);
    }

    public Optional<QuantRankingDtos.NotificationConfig> findNotificationConfig(long userId) {
        List<QuantRankingDtos.NotificationConfig> list = jdbc.query("SELECT enabled,webhook_url,updated_at FROM quant_notification_config WHERE user_id=?",
                (rs, rowNum) -> new QuantRankingDtos.NotificationConfig(rs.getInt("enabled") != 0,
                        rs.getString("webhook_url"), rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toLocalDateTime()), userId);
        return list.stream().findFirst();
    }

    public void saveNotificationConfig(long userId, boolean enabled, String webhookUrl) {
        int updated = jdbc.update("UPDATE quant_notification_config SET enabled=?,webhook_url=?,updated_at=CURRENT_TIMESTAMP WHERE user_id=?",
                enabled ? 1 : 0, webhookUrl, userId);
        if (updated == 0) {
            jdbc.update("INSERT INTO quant_notification_config(user_id,enabled,webhook_url) VALUES(?,?,?)",
                    userId, enabled ? 1 : 0, webhookUrl);
        }
    }

    public void insertNotificationLog(long userId, LocalDate date, String status,
                                      String message, String error, int attempts) {
        jdbc.update("INSERT INTO quant_notification_log(user_id,trading_date,channel,status,message,error,attempt_count) VALUES(?,?,?,?,?,?,?)",
                userId, date, "wecom", status, message, error, attempts);
    }

    public List<StoredLog> findLogs(long userId, int limit) {
        return jdbc.query("SELECT id,trading_date,channel,status,message,created_at FROM quant_notification_log "
                        + "WHERE user_id=? ORDER BY id DESC LIMIT ?",
                (rs, rowNum) -> new StoredLog(rs.getLong("id"),
                        rs.getDate("trading_date") == null ? null : rs.getDate("trading_date").toLocalDate(),
                        rs.getString("channel"), rs.getString("status"), rs.getString("message"),
                        rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toLocalDateTime()),
                userId, Math.max(1, Math.min(limit, 200)));
    }

    private Map<String, String> parseRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        return JSON.parseObject(raw, Map.class);
    }

    public record StoredItem(String code, String name, int rank, LocalDate tradingDate) {
    }

    public record StoredLog(long id, LocalDate date, String source, String status,
                            String message, LocalDateTime createdAt) {
    }
}
