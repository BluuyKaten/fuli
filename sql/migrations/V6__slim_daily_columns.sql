-- ============================================
-- V6: 日线表字段瘦身
-- 归属服务：data-service
-- 关联 ADR：docs/adr/0003-daily-table-column-slimming.md
--
-- 手工执行（项目未接入 Flyway）：
--   mysql -uroot -p data_db < sql/migrations/V6__slim_daily_columns.sql
--
-- 预期效果：
--   key_len      164  -> 42
--   索引体积     583MB -> 约 150MB
--   停机窗口     约 2-5 分钟（766 万行，MySQL 8 走 COPY 重建表）
--
-- 前置条件（已校验）：
--   全部 7,663,187 行 trade_date 均为 8 位纯数字
--   stock_code 最大长度正好 9
--
-- 回滚：执行 V6__slim_daily_columns_rollback.sql
-- ============================================

USE `data_db`;

-- 用 VARCHAR(9) 而非 CHAR(9)：
--   CHAR(9) 在 utf8mb4 下按字符数预分配 36 字节定长，比 VARCHAR 的 10 字节更费；
--   且 CHAR 会在尾部补空格，导致不带后缀的裸代码（如 '600519'）匹配行为变化。
ALTER TABLE `stock_daily_data`
    MODIFY COLUMN `stock_code` VARCHAR(9) NOT NULL COMMENT '股票代码，如 000001.SZ',
    MODIFY COLUMN `trade_date` INT        NOT NULL COMMENT '交易日期，如 20231103';

-- 校验：key_len 应为 42，索引应重建为 uk_code_trade_date
-- EXPLAIN SELECT * FROM stock_daily_data
--   WHERE stock_code='600519.SH' AND trade_date BETWEEN 20230101 AND 20251231;
