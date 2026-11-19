-- ============================================
-- V16：建议单增加 executing 中间状态
--
-- executing 表示人工确认请求已经抢占建议单，但交易结果仍在处理或待核实。
-- 网络结果不确定时不能自动回到 new，否则重试可能重复下单。
-- 本脚本只调整 CHECK，不会触碰订单表或既有行情表。
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
  COMMENT 'new/executing/executed/rejected/expired，仅表示人工处置与执行状态';

ALTER TABLE `okx_order_suggestion`
  ADD CONSTRAINT `chk_okx_suggestion_status`
  CHECK (`status` IN ('new', 'executing', 'executed', 'rejected', 'expired'));

SELECT CONSTRAINT_NAME, CHECK_CLAUSE
FROM information_schema.CHECK_CONSTRAINTS
WHERE CONSTRAINT_SCHEMA = 'data_db'
  AND CONSTRAINT_NAME = 'chk_okx_suggestion_status';
