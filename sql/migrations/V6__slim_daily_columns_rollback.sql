-- ============================================
-- V6 回滚：日线表字段还原
-- 关联 ADR：docs/adr/0003-daily-table-column-slimming.md
--
-- 仅在需要退回旧结构时执行。注意：回滚后所有按整型日期查询的代码
-- （StockDataController、PracticeSnapshotLoader）会因隐式类型转换失去索引，
-- 必须同时回滚代码。
-- ============================================

USE `data_db`;

ALTER TABLE `stock_daily_data`
    MODIFY COLUMN `stock_code` VARCHAR(32) NOT NULL COMMENT '股票代码, 如 000001.SZ',
    MODIFY COLUMN `trade_date` VARCHAR(8)  NOT NULL COMMENT '交易日期, yyyyMMdd';
