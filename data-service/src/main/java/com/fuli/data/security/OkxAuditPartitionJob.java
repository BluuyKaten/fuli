package com.fuli.data.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 审计表分区维护（V13）：按月 RANGE 分区，保留最近 2 个月。
 *
 * <p>切换动机：V11 的「每日 DELETE 分批删」在千万级会产生大量 undo 和长时间持锁；
 * {@code DROP PARTITION} 是元数据操作，秒级完成且不产生 undo。
 *
 * <p>本任务做两件事：
 * <ol>
 *   <li><b>补未来分区</b>：当前月 + 未来 {@value #AHEAD_MONTHS} 个月。做法是把 {@code pmax}
 *       重组（{@code REORGANIZE PARTITION pmax INTO (...)}）——MySQL 不允许在 MAXVALUE
 *       之后直接 ADD PARTITION，这是唯一可用的写法。</li>
 *   <li><b>丢老分区</b>：分区月份早于 {@code now - 2 个月} 的整个 DROP 掉。</li>
 * </ol>
 *
 * <p>粒度说明：分区只能按月丢，所以实际保留是「当月 + 前 2 个月」，
 * 比「精确 2 个月」可能多留最多 1 个月的尾巴。这是分区方案的固有代价，
 * 换来的是清理成本从「分钟级锁」降到「毫秒级元数据操作」。
 *
 * <p>没有分区（未执行 V13 或已回滚）时本任务自动跳过，由
 * {@link OkxAuditRetentionJob} 退回分批 DELETE。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OkxAuditPartitionJob {

    /** 保留窗口（月）。 */
    public static final int RETENTION_MONTHS = 2;

    /** 提前创建多少个月的分区。 */
    public static final int AHEAD_MONTHS = 3;

    private static final String TABLE = "okx_audit_log";

    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.ofPattern("yyyyMM");

    private final JdbcTemplate jdbc;

    /** 每天 03:10，排在清理任务之前（先备好分区，再删旧数据）。 */
    @Scheduled(cron = "${okx.audit.partition-cron:0 10 3 * * *}")
    public void scheduled() {
        try {
            maintain(YearMonth.now());
        } catch (Exception e) {
            // 调度线程里抛异常会静默终止后续调度，必须自己吞掉
            log.error("[OKX-AUDIT] 分区维护异常：{}", e.getMessage());
        }
    }

    public record Result(boolean partitioned, List<String> added, List<String> dropped) {
    }

    /**
     * @param now 当前月份（测试注入）
     */
    public Result maintain(YearMonth now) {
        List<String> existing = partitions();
        if (existing.isEmpty()) {
            return new Result(false, List.of(), List.of());
        }
        List<String> added = new ArrayList<>();
        for (int i = 0; i <= AHEAD_MONTHS; i++) {
            YearMonth target = now.plusMonths(i);
            if (!existing.contains(nameOf(target))) {
                if (addPartition(target)) {
                    added.add(nameOf(target));
                }
            }
        }

        List<String> dropped = new ArrayList<>();
        YearMonth keepFrom = now.minusMonths(RETENTION_MONTHS);
        for (String partition : existing) {
            YearMonth month = monthOf(partition);
            if (month != null && month.isBefore(keepFrom)) {
                dropPartition(partition);
                dropped.add(partition);
            }
        }
        if (!added.isEmpty() || !dropped.isEmpty()) {
            log.info("[OKX-AUDIT] 分区维护：新增 {}，丢弃 {}（保留 {} 起）", added, dropped, keepFrom);
        }
        return new Result(true, added, dropped);
    }

    /** 表是否已按月分区。 */
    public boolean isPartitioned() {
        return !partitions().isEmpty();
    }

    public List<String> partitions() {
        try {
            return jdbc.queryForList(
                    "SELECT PARTITION_NAME FROM information_schema.PARTITIONS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND PARTITION_NAME IS NOT NULL",
                    String.class, TABLE);
        } catch (Exception e) {
            log.warn("[OKX-AUDIT] 读取分区信息失败：{}", e.getMessage());
            return List.of();
        }
    }

    /** 把 pmax 重组出一个月分区；已存在则跳过（并发/重复调用安全）。 */
    boolean addPartition(YearMonth month) {
        String name = nameOf(month);
        String upperBound = month.plusMonths(1).atDay(1).toString();
        String sql = "ALTER TABLE `" + TABLE + "` REORGANIZE PARTITION pmax INTO ("
                + "PARTITION `" + name + "` VALUES LESS THAN (TO_DAYS('" + upperBound + "')),"
                + "PARTITION pmax VALUES LESS THAN (MAXVALUE))";
        try {
            jdbc.execute(sql);
            return true;
        } catch (Exception e) {
            // 分区已存在 / 并发创建：都不该让任务失败
            log.warn("[OKX-AUDIT] 新增分区 {} 失败（可能已存在）：{}", name, e.getMessage());
            return false;
        }
    }

    void dropPartition(String partition) {
        // 分区名由本类生成（p + 6 位数字），不接受外部输入，避免标识符注入
        if (!partition.matches("p\\d{6}")) {
            log.warn("[OKX-AUDIT] 拒绝丢弃非法分区名：{}", partition);
            return;
        }
        jdbc.execute("ALTER TABLE `" + TABLE + "` DROP PARTITION `" + partition + "`");
    }

    static String nameOf(YearMonth month) {
        return "p" + month.format(SUFFIX);
    }

    static YearMonth monthOf(String partition) {
        if (partition == null || !partition.matches("p\\d{6}")) {
            return null;
        }
        try {
            return YearMonth.parse(partition.substring(1), SUFFIX);
        } catch (Exception e) {
            return null;
        }
    }

    /** 仅用于运维核对：当前分区按时间排序。 */
    public List<String> sortedPartitions() {
        List<String> all = new ArrayList<>(partitions());
        all.sort(Comparator.comparing(p -> monthOf(p) == null ? YearMonth.of(9999, 12) : monthOf(p)));
        return all;
    }
}
