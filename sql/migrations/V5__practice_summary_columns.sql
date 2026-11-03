-- Non-destructive upgrade: add summary columns to practice_session so the
-- practice list page no longer needs to parse full state_json per row.
USE `trade_db`;

-- Columns are nullable; pre-existing rows keep NULL and the application falls
-- back to parsing state_json for those rows (see PracticeService#list).
ALTER TABLE `practice_session`
    ADD COLUMN `stock_code` VARCHAR(20) DEFAULT NULL COMMENT '股票代码（摘要列）' AFTER `user_id`,
    ADD COLUMN `start_date` VARCHAR(10) DEFAULT NULL COMMENT '起始日（摘要列）' AFTER `stock_code`,
    ADD COLUMN `end_date` VARCHAR(10) DEFAULT NULL COMMENT '结束日（摘要列）' AFTER `start_date`,
    ADD COLUMN `current_day` VARCHAR(10) DEFAULT NULL COMMENT '当前决策日（摘要列）' AFTER `end_date`,
    ADD COLUMN `status` VARCHAR(20) DEFAULT NULL COMMENT '状态（摘要列）' AFTER `current_date`,
    ADD COLUMN `equity` DECIMAL(18,2) DEFAULT NULL COMMENT '当前权益（摘要列）' AFTER `status`,
    ADD COLUMN `initial_cash` DECIMAL(18,2) DEFAULT NULL COMMENT '初始资金（摘要列）' AFTER `equity`;
