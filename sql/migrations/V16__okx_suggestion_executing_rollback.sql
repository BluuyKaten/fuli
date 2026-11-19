-- ============================================
-- V16 回滚：恢复 V15 建议单状态约束
--
-- 仅适用于没有 executing 记录的环境；生产环境不要回滚未核实状态。
-- ============================================

USE `data_db`;

SET @executing_count := (
  SELECT COUNT(*) FROM `okx_order_suggestion` WHERE `status` = 'executing'
);
SET @fail_sql := IF(@executing_count = 0, 'DO 0',
  'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''executing 状态仍存在，不能回滚 V16''');
PREPARE stmt FROM @fail_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

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

ALTER TABLE `okx_order_suggestion`
  ADD CONSTRAINT `chk_okx_suggestion_status`
  CHECK (`status` IN ('new', 'executed', 'rejected', 'expired'));
