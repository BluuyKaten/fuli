package com.fuli.data.security;

import com.fuli.data.okx.mapper.OkxAuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 审计保留策略：只留最近 2 个月（ADR-0010 决策 3）。
 *
 * <p><b>分工</b>：表已按月分区（V13）时，清理交给 {@link OkxAuditPartitionJob} 的
 * {@code DROP PARTITION}；本任务只在**未分区**（未执行 V13 或已回滚）时兜底，
 * 用分批 DELETE。两条路都保留，是为了让「回滚分区」不至于把清理能力一起回滚掉。
 *
 * <p><b>分批删除</b>是这里的重点：一条 {@code DELETE ... WHERE create_time < ?} 删几百万行
 * 会长时间持锁、撑爆 undo，把主库拖到不可写。每次 1000 行、最多 200 批（20 万行/天），
 * 既能追上每日增量，又不会把一次调度拖成事故。删不完不是错误——明天继续。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OkxAuditRetentionJob {

    /** 保留窗口：2 个月。 */
    public static final int RETENTION_MONTHS = 2;

    /** 单批删除行数。 */
    public static final int BATCH_SIZE = 1000;

    /** 单次调度最多删多少批，防止一次清理占满 IO。 */
    public static final int MAX_BATCHES = 200;

    private final OkxAuditLogMapper mapper;

    private final OkxAuditPartitionJob partitionJob;

    /** 每天 03:30（低峰）。cron 可配置，便于测试环境临时改到分钟级验证。 */
    @Scheduled(cron = "${okx.audit.cleanup-cron:0 30 3 * * *}")
    public void scheduledPurge() {
        try {
            purge(LocalDateTime.now());
        } catch (Exception e) {
            // 调度线程里抛异常会静默终止后续调度，必须自己吞掉并记账
            log.error("[OKX-AUDIT] 保留期清理异常：{}", e.getMessage());
        }
    }

    /**
     * 清理入口，返回删除行数。
     *
     * @param now 当前时间（测试注入，避免依赖系统时钟）
     */
    public int purge(LocalDateTime now) {
        if (partitionJob != null && partitionJob.isPartitioned()) {
            // 分区表的清理由 DROP PARTITION 完成，DELETE 只会白扫一遍分区元数据
            return 0;
        }
        LocalDateTime cutoff = now.minusMonths(RETENTION_MONTHS);
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            int deleted;
            try {
                deleted = mapper.deleteOlderThan(cutoff, BATCH_SIZE);
            } catch (Exception e) {
                log.error("[OKX-AUDIT] 清理失败（已删 {} 行，cutoff={}）：{}", total, cutoff, e.getMessage());
                break;
            }
            total += deleted;
            if (deleted < BATCH_SIZE) {
                break;
            }
        }
        if (total > 0) {
            log.info("[OKX-AUDIT] 保留期清理完成：删除 {} 行（早于 {}）", total, cutoff);
        }
        return total;
    }

    /** 截止时间，供运维核对「应该保留到哪天」。 */
    public static LocalDateTime cutoffOf(LocalDateTime now) {
        return now.minusMonths(RETENTION_MONTHS);
    }
}
