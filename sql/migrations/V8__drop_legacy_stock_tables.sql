-- =====================================================================
-- 0.8 清理 trade_db 旧行情表（阶段 0）
--
-- 前置条件（已逐条核对）：
--   [x] trade-service 已全部改走 data-service Feign：
--       StockInfo / StockInfoMapper 为死代码（无 import、无注入）；
--       StockMinuteData/Monthly/Weekly 三实体仅有 @TableName，无业务引用；
--       trade-service 内无任何原生 SQL 触达这五张表。
--   [x] 三张周期表均为 0 行。
--   [x] trade_db.stock_daily_data 是 data_db 的陈旧副本（7,352,155 vs 7,668,759，
--       差 316,604 行），新读写全部落 data_db.bar。
--   [x] 备份：F:\backups\fuli-pre-bar-20261104.sql（两库全量，2,281,033,626 B）
--             F:\backups\v8-drop\trade_db-stock_info.sql（5,552 行，659,005 B）
--             F:\backups\v8-drop\trade_db-dead-tables-schema.sql（4 张表结构）
--
-- 删除顺序由外键决定：stock_daily_data.fk_daily_stock_code → stock_info
-- 必须先摘 FK（或先删子表），否则 stock_info 删不掉。
--
-- 【不可回滚提醒】本脚本删的是表，不是数据版本。恢复只能靠上面两个 dump。
-- 0.9（停双写 + DROP data_db.stock_daily_data）**不在本脚本内**，
-- 需双写观察满 2 周后单独执行。
-- =====================================================================

-- 1) 摘掉子表对 stock_info 的外键
ALTER TABLE `trade_db`.`stock_daily_data`
  DROP FOREIGN KEY `fk_daily_stock_code`;

-- 2) 删子表（陈旧日线副本）
DROP TABLE IF EXISTS `trade_db`.`stock_daily_data`;

-- 3) 删三张从未写入的周期表
DROP TABLE IF EXISTS `trade_db`.`stock_minute_data`;
DROP TABLE IF EXISTS `trade_db`.`stock_weekly_data`;
DROP TABLE IF EXISTS `trade_db`.`stock_monthly_data`;

-- 4) 删父表
DROP TABLE IF EXISTS `trade_db`.`stock_info`;

-- 5) 复核：trade_db 应只剩业务表（trade_record / position_summary / ...）
SELECT table_name, table_rows
FROM information_schema.tables
WHERE table_schema = 'trade_db'
ORDER BY table_name;

-- 6) 复核：data_db 的新表不受影响
SELECT 'bar' AS t, COUNT(*) AS n FROM `data_db`.`bar`
UNION ALL SELECT 'instrument', COUNT(*) FROM `data_db`.`instrument`
UNION ALL SELECT 'adj_factor', COUNT(*) FROM `data_db`.`adj_factor`
UNION ALL SELECT 'stock_daily_data', COUNT(*) FROM `data_db`.`stock_daily_data`;
