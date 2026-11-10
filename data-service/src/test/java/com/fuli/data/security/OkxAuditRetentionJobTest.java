package com.fuli.data.security;

import com.fuli.data.okx.mapper.OkxAuditLogMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 审计保留策略测试（ADR-0010 决策 3）：2 个月窗口 + 分批删除。 */
class OkxAuditRetentionJobTest {

    /** 未分区（V13 之前的形态）：清理走分批 DELETE。 */
    private static OkxAuditPartitionJob partitionJob(boolean partitioned) {
        OkxAuditPartitionJob job = mock(OkxAuditPartitionJob.class);
        when(job.isPartitioned()).thenReturn(partitioned);
        return job;
    }

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 3, 30);

    @Test
    @DisplayName("截止时间恰好是 2 个月前")
    void cutoffIsTwoMonthsBefore() {
        assertEquals(LocalDateTime.of(2026, 9, 10, 3, 30), OkxAuditRetentionJob.cutoffOf(NOW));
    }

    @Test
    @DisplayName("按 1000 行一批循环，直到某批不足一批为止")
    void deletesInBatchesUntilDrained() {
        OkxAuditLogMapper mapper = mock(OkxAuditLogMapper.class);
        when(mapper.deleteOlderThan(any(), anyInt()))
                .thenReturn(1000, 1000, 200);

        int total = new OkxAuditRetentionJob(mapper, partitionJob(false)).purge(NOW);

        assertEquals(2200, total);
        verify(mapper, times(3)).deleteOlderThan(eq(OkxAuditRetentionJob.cutoffOf(NOW)),
                eq(OkxAuditRetentionJob.BATCH_SIZE));
    }

    @Test
    @DisplayName("没有过期数据：只查一次就停")
    void stopsWhenNothingToDelete() {
        OkxAuditLogMapper mapper = mock(OkxAuditLogMapper.class);
        when(mapper.deleteOlderThan(any(), anyInt())).thenReturn(0);

        assertEquals(0, new OkxAuditRetentionJob(mapper, partitionJob(false)).purge(NOW));
        verify(mapper, times(1)).deleteOlderThan(any(), anyInt());
    }

    @Test
    @DisplayName("批次数有上限：删不完不是错误，明天继续（避免一次清理占满 IO）")
    void stopsAtBatchCap() {
        OkxAuditLogMapper mapper = mock(OkxAuditLogMapper.class);
        when(mapper.deleteOlderThan(any(), anyInt())).thenReturn(1000);

        int total = new OkxAuditRetentionJob(mapper, partitionJob(false)).purge(NOW);

        assertEquals(OkxAuditRetentionJob.MAX_BATCHES * 1000, total);
        verify(mapper, times(OkxAuditRetentionJob.MAX_BATCHES)).deleteOlderThan(any(), anyInt());
    }

    @Test
    @DisplayName("中途异常：返回已删数量，不向上抛（调度线程不能被一次失败打死）")
    void failureReturnsPartialCount() {
        OkxAuditLogMapper mapper = mock(OkxAuditLogMapper.class);
        when(mapper.deleteOlderThan(any(), anyInt()))
                .thenReturn(1000)
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("lock wait timeout"));

        assertEquals(1000, new OkxAuditRetentionJob(mapper, partitionJob(false)).purge(NOW));
    }

    @Test
    @DisplayName("表已分区：DELETE 清理直接跳过（交给 DROP PARTITION）")
    void skipsWhenTableIsPartitioned() {
        OkxAuditLogMapper mapper = mock(OkxAuditLogMapper.class);
        assertEquals(0, new OkxAuditRetentionJob(mapper, partitionJob(true)).purge(NOW));
        verify(mapper, org.mockito.Mockito.never()).deleteOlderThan(any(), anyInt());
    }
}
