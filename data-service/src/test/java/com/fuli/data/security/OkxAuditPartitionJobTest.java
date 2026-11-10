package com.fuli.data.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 审计分区维护（V13）：补未来分区、丢老分区、未分区时自动让位。 */
class OkxAuditPartitionJobTest {

    private static final YearMonth NOW = YearMonth.of(2026, 11);

    private JdbcTemplate jdbc;

    private OkxAuditPartitionJob job;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        job = new OkxAuditPartitionJob(jdbc);
    }

    private void partitions(String... names) {
        when(jdbc.queryForList(anyString(), eq(String.class), eq("okx_audit_log")))
                .thenReturn(List.of(names));
    }

    @Test
    @DisplayName("分区名与月份互转：p202611 ↔ 2026-11")
    void nameRoundTrip() {
        assertEquals("p202611", OkxAuditPartitionJob.nameOf(YearMonth.of(2026, 11)));
        assertEquals(YearMonth.of(2026, 11), OkxAuditPartitionJob.monthOf("p202611"));
        assertNull(OkxAuditPartitionJob.monthOf("pmax"));
        assertNull(OkxAuditPartitionJob.monthOf("bogus"));
    }

    @Test
    @DisplayName("未分区（V13 之前）：直接让位给 DELETE 清理，不做任何 ALTER")
    void notPartitionedIsNoOp() {
        partitions();
        OkxAuditPartitionJob.Result result = job.maintain(NOW);

        assertFalse(result.partitioned());
        assertTrue(result.added().isEmpty());
        assertTrue(result.dropped().isEmpty());
        verify(jdbc, never()).execute(anyString());
    }

    @Test
    @DisplayName("补齐未来 3 个月：已经有的不重复建")
    void createsMissingFuturePartitions() {
        partitions("p202610", "p202611", "p202612", "pmax");

        OkxAuditPartitionJob.Result result = job.maintain(NOW);

        assertTrue(result.partitioned());
        // 已有 2026-11/12，缺 2027-01 与 2027-02
        assertEquals(List.of("p202701", "p202702"), result.added());
        verify(jdbc).execute(contains("REORGANIZE PARTITION pmax INTO (PARTITION `p202701`"));
        verify(jdbc).execute(contains("VALUES LESS THAN (TO_DAYS('2027-02-01'))"));
    }

    @Test
    @DisplayName("丢弃早于「当前月 - 2」的分区：2026-11 时保留 09/10/11，丢 08 及更早")
    void dropsPartitionsOlderThanRetention() {
        partitions("p202607", "p202608", "p202609", "p202610", "p202611", "pmax");

        OkxAuditPartitionJob.Result result = job.maintain(NOW);

        assertEquals(List.of("p202607", "p202608"), result.dropped());
        verify(jdbc).execute("ALTER TABLE `okx_audit_log` DROP PARTITION `p202607`");
        verify(jdbc).execute("ALTER TABLE `okx_audit_log` DROP PARTITION `p202608`");
        verify(jdbc, never()).execute(contains("DROP PARTITION `p202609`"));
    }

    @Test
    @DisplayName("非法分区名不执行 DROP（标识符拼接必须白名单）")
    void refusesToDropUnknownPartitionName() {
        job.dropPartition("p2026; DROP TABLE x");
        verify(jdbc, never()).execute(anyString());
    }

    @Test
    @DisplayName("分区已存在导致 ALTER 失败：吞掉异常，不影响其它月份")
    void addFailureIsTolerated() {
        partitions("p202610", "p202611", "p202612", "pmax");
        org.mockito.Mockito.doThrow(new org.springframework.jdbc.BadSqlGrammarException(
                        "add", "ALTER", new java.sql.SQLException("duplicate")))
                .when(jdbc).execute(contains("REORGANIZE"));

        OkxAuditPartitionJob.Result result = job.maintain(NOW);

        // 两次都失败 → added 为空，但 dropped 仍正常执行、不抛异常
        assertTrue(result.added().isEmpty());
    }
}
