-- ============================================
-- 数据迁移脚本：从 trade_db 迁移行情数据到 data_db
-- 执行前提：data_db 已通过 sql/data_db.sql 创建
-- ============================================

USE `data_db`;

-- 1. 迁移股票基础信息（INSERT IGNORE 避免主键冲突，若 data_db 已有数据则跳过）
INSERT IGNORE INTO `data_db`.`stock_info`
    (`stock_code`, `stock_name`, `area`, `industry`, `list_date`, `market`, `status`, `create_time`, `update_time`)
SELECT
    `stock_code`, `stock_name`, `area`, `industry`, `list_date`, `market`, `status`, `create_time`, `update_time`
FROM `trade_db`.`stock_info`;

-- 2. 迁移股票日线行情数据
INSERT IGNORE INTO `data_db`.`stock_daily_data`
    (`stock_code`, `trade_date`, `open_price`, `high_price`, `low_price`, `close_price`, `pre_close`, `change_amount`, `pct_chg`, `vol`, `amount`, `create_time`, `update_time`)
SELECT
    `stock_code`, `trade_date`, `open_price`, `high_price`, `low_price`, `close_price`, `pre_close`, `change_amount`, `pct_chg`, `vol`, `amount`, `create_time`, `update_time`
FROM `trade_db`.`stock_daily_data`;

-- 3. 验证迁移结果
SELECT 'stock_info' AS tbl, COUNT(*) AS row_count FROM `data_db`.`stock_info`
UNION ALL
SELECT 'stock_daily_data', COUNT(*) FROM `data_db`.`stock_daily_data`;
