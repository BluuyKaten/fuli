-- =====================================================================
-- 0.9 退役 data_db.stock_daily_data（阶段 0 收尾）
--
-- ⚠️ 前置条件（未满足前不要执行，任一不满足都会失去回滚能力）：
--   [ ] 双写观察满 2 周（自新代码部署日起算；当前**尚未部署**，所以时钟还没开始走）
--   [ ] 停双写的代码已改并部署（见 V9__retire_stock_daily_data.md 的 4 处改动）
--   [ ] 部署后至少跑过一次全市场同步，且 bar 与旧表行数一致（V1 + §13 每日对账）
--   [ ] 已重新导出旧表（它比 11-04 的全量备份多了新数据）：
--       mysqldump -uroot -p --single-transaction data_db stock_daily_data \
--         > F:/backups/data_db-stock_daily_data-<日期>.sql
--
-- 为什么不能提前删：双写期的旧表是**唯一**的回滚靶子。新代码读 bar 不假，
-- 但一旦 bar 的换算/时间轴出问题，回滚 = 换回旧代码 + 旧表仍在且最新。
-- 提前删表等于把回滚路径一起删了；而 bar 重建要重跑 7.67M 行迁移 + 5,178 天因子。
--
-- 更稳的做法是先归档一个月再删（推荐）：
--   RENAME TABLE `data_db`.`stock_daily_data` TO `data_db`.`stock_daily_data_archive_2026m11`;
-- 归档表不参与任何读写，一个月后确认无回滚需求再 DROP。
-- =====================================================================

USE `data_db`;

-- 1) 删表前最后一次对账（两个数必须相等，否则停手）
SELECT
  (SELECT COUNT(*) FROM `bar` WHERE period = '1d')  AS bar_rows,
  (SELECT COUNT(*) FROM `stock_daily_data`)         AS legacy_rows;

-- 2) 归档（推荐） —— 与 3) 二选一
-- RENAME TABLE `stock_daily_data` TO `stock_daily_data_archive_2026m11`;

-- 3) 直接删除（不可回滚，只能靠 dump 恢复）
-- DROP TABLE IF EXISTS `stock_daily_data`;

-- 4) 复核：data_db 应只剩 instrument / bar / adj_factor / stock_info
--    （stock_info 的退役属于后续阶段，`StockDataController#info(s)` 还在读）
SELECT table_name, table_rows
FROM information_schema.tables
WHERE table_schema = 'data_db'
ORDER BY table_name;
