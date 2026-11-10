-- ============================================
-- V13：审计表切换为按月 RANGE 分区
--
-- 状态：**待执行**。归属服务：data-service。
-- 关联 ADR：docs/adr/0010-...（决策 3：审计保留 2 个月）
--
-- 为什么要切：V11 用的是「每日 DELETE 分批删」。量级上来以后，
-- DELETE 会产生大量 undo、长时间持锁，清理期间主库写入抖动；
-- 分区表可以直接 `DROP PARTITION`，秒级且不产生 undo。
--
-- 代价（已在 ADR 记录）：
--   * 分区列必须进主键 → 主键由 `id` 改为 `(id, create_time)`；
--     `id` 仍是第一列且自增，单表查询/插入行为不变，但不能再 `selectById(id)` 单独定位。
--   * 新月份的分区要在用之前建好，否则数据落进 `pmax`；
--     `OkxAuditPartitionJob` 每天检查并补齐（REORGANIZE pmax）。
--
-- 分区命名：pYYYYMM，上界是该月 1 日（TO_DAYS('YYYY-MM-01')）。
-- 即 p202611 存 2026-11-01 之前的数据？**不是** —— 见下方边界说明。
--
-- 边界说明（容易看错）：
--   PARTITION p202611 VALUES LESS THAN (TO_DAYS('2026-12-01'))
--   表示「小于 2026-12-01 的数据都在 p202611」，也就是 2026 年 11 月这一个月。
--   命名按「覆盖的月份」而不是「上界月份」，与维护任务的判断逻辑保持一致。
--
-- 回滚：V13__okx_audit_partition_rollback.sql（还原成普通表，保留全部数据）
-- ============================================

USE `data_db`;

-- ---------- 1. 建分区表（结构与 V11 一致，仅主键改为复合） ----------
DROP TABLE IF EXISTS `okx_audit_log_partitioned`;
CREATE TABLE `okx_audit_log_partitioned` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`     BIGINT UNSIGNED NULL COMMENT 'NULL 表示服务账号（type=SERVICE）',
  `action`      VARCHAR(64)   NOT NULL,
  `scope`       VARCHAR(32)   NOT NULL,
  `outcome`     VARCHAR(16)   NOT NULL,
  `inst_id`     VARCHAR(64)   NULL,
  `symbol`      VARCHAR(64)   NULL,
  `side`        VARCHAR(8)    NULL,
  `sz`          DECIMAL(28,8) NULL,
  `px`          DECIMAL(28,8) NULL,
  `ord_id`      VARCHAR(64)   NULL,
  `cl_ord_id`   VARCHAR(32)   NULL,
  `detail`      VARCHAR(512)  NULL,
  `trace_id`    VARCHAR(64)   NULL,
  `create_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`, `create_time`),
  KEY `idx_okx_audit_user_time` (`user_id`, `create_time`),
  KEY `idx_okx_audit_action_time` (`action`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='OKX 操作审计（按月 RANGE 分区，保留 2 个月）'
PARTITION BY RANGE (TO_DAYS(`create_time`)) (
  PARTITION p202610 VALUES LESS THAN (TO_DAYS('2026-11-01')),
  PARTITION p202611 VALUES LESS THAN (TO_DAYS('2026-12-01')),
  PARTITION p202612 VALUES LESS THAN (TO_DAYS('2027-01-01')),
  PARTITION p202701 VALUES LESS THAN (TO_DAYS('2027-02-01')),
  PARTITION p202702 VALUES LESS THAN (TO_DAYS('2027-03-01')),
  PARTITION p202703 VALUES LESS THAN (TO_DAYS('2027-04-01')),
  PARTITION pmax    VALUES LESS THAN (MAXVALUE)
);

-- ---------- 2. 搬历史数据 ----------
INSERT INTO `okx_audit_log_partitioned`
  (`id`, `user_id`, `action`, `scope`, `outcome`, `inst_id`, `symbol`, `side`, `sz`, `px`,
   `ord_id`, `cl_ord_id`, `detail`, `trace_id`, `create_time`)
SELECT `id`, `user_id`, `action`, `scope`, `outcome`, `inst_id`, `symbol`, `side`, `sz`, `px`,
       `ord_id`, `cl_ord_id`, `detail`, `trace_id`, `create_time`
FROM `okx_audit_log`;

-- ---------- 3. 换表 ----------
DROP TABLE `okx_audit_log`;
RENAME TABLE `okx_audit_log_partitioned` TO `okx_audit_log`;

-- ---------- 4. 自检 ----------
SELECT PARTITION_NAME, PARTITION_DESCRIPTION, TABLE_ROWS
FROM information_schema.PARTITIONS
WHERE TABLE_SCHEMA = 'data_db' AND TABLE_NAME = 'okx_audit_log'
ORDER BY PARTITION_ORDINAL_POSITION;
