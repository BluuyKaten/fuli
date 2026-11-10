-- ============================================
-- V15 回滚：把建议单状态约束改回 V11 的 new/accepted/rejected/expired
--
-- 注意：**回滚会丢失「已下单」信息**——executed 会退化成 accepted，
-- 与「人工同意但没下成」无法区分。仅在没有真实 executed 数据的开发环境使用；
-- 生产环境不要回滚这一步，改代码而不是改约束。
-- ============================================

USE `data_db`;

UPDATE `okx_order_suggestion` SET `status` = 'accepted' WHERE `status` = 'executed';

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
  COMMENT 'new/accepted/rejected/expired，仅表示人工处置';

ALTER TABLE `okx_order_suggestion`
  ADD CONSTRAINT `chk_okx_suggestion_status`
  CHECK (`status` IN ('new', 'accepted', 'rejected', 'expired'));
