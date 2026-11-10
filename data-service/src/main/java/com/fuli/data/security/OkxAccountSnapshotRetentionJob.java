package com.fuli.data.security;

import com.fuli.data.crypto.CryptoAccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 账户余额快照保留策略（V12）：只留 90 天。
 *
 * <p>为什么要清：{@code account} 频道会定时推送，一天下来单账户几十行，
 * 长期不清会让这张「视图表」涨成账本大小。余额的历史价值主要在近三个月，
 * 更长的趋势看 K 线或资金曲线即可。
 *
 * <p>与审计表不同，这张表**不分**区：量级差一个数量级（每天几十行 vs 每秒一条），
 * 分批 DELETE 足够，没必要为了它付分区表的主键约束代价。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OkxAccountSnapshotRetentionJob {

    public static final int RETENTION_DAYS = 90;

    private static final int BATCH_SIZE = 1000;

    private static final int MAX_BATCHES = 50;

    private final CryptoAccountService accountService;

    /** 每天 03:45，避开审计清理的两个窗口。 */
    @Scheduled(cron = "${okx.audit.snapshot-cleanup-cron:0 45 3 * * *}")
    public void scheduled() {
        try {
            purge(LocalDateTime.now());
        } catch (Exception e) {
            log.error("[OKX-ACC] 快照清理异常：{}", e.getMessage());
        }
    }

    public int purge(LocalDateTime now) {
        LocalDateTime cutoff = now.minusDays(RETENTION_DAYS);
        int deleted = accountService.purge(cutoff, BATCH_SIZE, MAX_BATCHES);
        if (deleted > 0) {
            log.info("[OKX-ACC] 清理余额快照 {} 行（早于 {}）", deleted, cutoff);
        }
        return deleted;
    }
}
