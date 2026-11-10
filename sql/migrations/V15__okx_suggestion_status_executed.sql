-- ============================================
-- V15：建议单状态补 executed（修正 V11 的 CHECK 与代码不一致）
--
-- 问题：`OkxSuggestionExecutionService` 人工确认成功后写 `status='executed'`，
--       但 V11 建的 `chk_okx_suggestion_status` 只允许 new/accepted/rejected/expired。
--       MySQL 8.0.16+ **强制执行** CHECK，于是这一步抛 ERROR 3819，
--       出现「委托已经报到 OKX、状态却更新失败」的错位：
--       建议单卡在 new，接口报错，用户以为没下成而重试
--       （clOrdId=sugg-{id} 幂等能兜住不重复下单，但界面口径全错、人工无从判断）。
--
-- 修法：把 `executed` 纳入状态机，并去掉从未被写入过的 `accepted`
--       （它与 executed 语义重叠：一个表示「人工同意」，一个表示「已下单成功」，
--        实际只有后者是终态，留着 accepted 会让人以为还有第二条成功路径）。
--
-- 状态语义（此后以此为准）：
--   new       待人工确认
--   executed  已确认并成功下单（由 ExecutionService 写入，唯一成功终态）
--   rejected  人工拒绝
--   expired   过期未处理
--
-- 幂等：先按 information_schema 判断约束是否存在再 DROP，可重复执行。
-- 回滚：V15__okx_suggestion_status_executed_rollback.sql
-- ============================================

USE `data_db`;

-- 万一日后手工写入过 accepted，先归一，避免改约束时丢掉已处置记录
UPDATE `okx_order_suggestion` SET `status` = 'executed' WHERE `status` = 'accepted';

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

-- 自检：约束应只允许上述四态
SELECT CONSTRAINT_NAME, CHECK_CLAUSE
FROM information_schema.CHECK_CONSTRAINTS
WHERE CONSTRAINT_SCHEMA = 'data_db'
  AND CONSTRAINT_NAME = 'chk_okx_suggestion_status';
