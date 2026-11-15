-- ============================================
-- V16 回滚：移除 executing，恢复 V15 的四态约束
--
-- 回滚风险：执行中记录会先被改回 new，无法再区分「尚未抢占」与「外部结果待核实」。
-- 若 OKX 已接受委托，回滚后再次确认可能造成重复处置；生产环境应先核对外部委托与幂等号。
-- 本文件只提供静态回滚脚本，不在开发环境或本任务中执行。
-- 可重复执行：executing 转换与 CHECK 重建均可重复运行。
-- ============================================

USE `data_db`;

-- 必须先归一 executing，随后才能安全恢复 V15 的四态 CHECK。
UPDATE `okx_order_suggestion`
SET `status` = 'new'
WHERE `status` = 'executing';

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
  COMMENT 'new/executed/rejected/expired，仅表示人工处置结果';

SET @has_check := (
  SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS
  WHERE CONSTRAINT_SCHEMA = 'data_db'
    AND CONSTRAINT_NAME = 'chk_okx_suggestion_status'
);
SET @add_sql := IF(@has_check = 0,
  'ALTER TABLE `okx_order_suggestion` ADD CONSTRAINT `chk_okx_suggestion_status` CHECK (`status` IN (''new'', ''executed'', ''rejected'', ''expired''))',
  'DO 0');
PREPARE stmt FROM @add_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 自检：约束应恢复为 V15 的四态。
SELECT CONSTRAINT_NAME, CHECK_CLAUSE
FROM information_schema.CHECK_CONSTRAINTS
WHERE CONSTRAINT_SCHEMA = 'data_db'
  AND CONSTRAINT_NAME = 'chk_okx_suggestion_status';
