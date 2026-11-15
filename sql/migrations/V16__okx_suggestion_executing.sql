-- ============================================
-- V16：建议单增加 executing（执行中/待核实）状态
--
-- executing 表示请求已经原子抢占建议单，但外部下单结果尚未被本地确认。
-- 本文件只提供静态迁移脚本，不在开发环境或本任务中执行。
-- 前置条件：V15 已将 accepted 归一为 executed。
-- 可重复执行：删除 CHECK 与增加 CHECK 均先检查元数据；已有五态约束时不重复添加。
-- ============================================

USE `data_db`;

SET @has_check := (
  SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS
  WHERE CONSTRAINT_SCHEMA = 'data_db'
    AND CONSTRAINT_NAME = 'chk_okx_suggestion_status'
);
SET @drop_sql := IF(@has_check > 0,
  'ALTER TABLE `okx_order_suggestion` DROP CHECK `chk_okx_suggestion_status`',
  'DO 0');
PREPARE stmt FROM @drop_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

ALTER TABLE `okx_order_suggestion`
  MODIFY COLUMN `status` VARCHAR(16) NOT NULL DEFAULT 'new'
  COMMENT 'new/executing/executed/rejected/expired；executing 表示执行中/待核实';

SET @has_check := (
  SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS
  WHERE CONSTRAINT_SCHEMA = 'data_db'
    AND CONSTRAINT_NAME = 'chk_okx_suggestion_status'
);
SET @add_sql := IF(@has_check = 0,
  'ALTER TABLE `okx_order_suggestion` ADD CONSTRAINT `chk_okx_suggestion_status` CHECK (`status` IN (''new'', ''executing'', ''executed'', ''rejected'', ''expired''))',
  'DO 0');
PREPARE stmt FROM @add_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 自检：约束应允许 new/executing/executed/rejected/expired 五态。
SELECT CONSTRAINT_NAME, CHECK_CLAUSE
FROM information_schema.CHECK_CONSTRAINTS
WHERE CONSTRAINT_SCHEMA = 'data_db'
  AND CONSTRAINT_NAME = 'chk_okx_suggestion_status';
