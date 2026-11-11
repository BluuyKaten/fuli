package com.fuli.trade.practice;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 记录经过 {@code query(String, RowMapper, Object...)} / {@code update(String, Object...)} 的 SQL。
 * 用来断言「某条 SELECT 根本不会被发出」——这比断言返回结果更能锁住读放大这个目标。
 */
final class SqlRecordingJdbcTemplate extends JdbcTemplate {
    private final List<String> statements = new CopyOnWriteArrayList<>();

    SqlRecordingJdbcTemplate(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
        statements.add(sql);
        return super.query(sql, rowMapper, args);
    }

    @Override
    public int update(String sql, Object... args) {
        statements.add(sql);
        return super.update(sql, args);
    }

    List<String> statements() {
        return List.copyOf(statements);
    }

    int count(String prefix) {
        return (int) statements.stream().filter(sql -> sql.startsWith(prefix)).count();
    }
}
