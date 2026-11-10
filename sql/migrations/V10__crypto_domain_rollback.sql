-- ============================================
-- V10 回滚：删除加密域独立表
--
-- 前提：V10__crypto_domain.sql 已执行，且这些表尚未被业务代码写入长期数据。
-- 手工执行：mysql -uroot -p data_db < sql/migrations/V10__crypto_domain_rollback.sql
--
-- 安全性：V10 只 CREATE 新表、不改任何存量表，因此回滚只需 DROP 这 8 张表；
--         instrument / bar / adj_factor / trade_record 等存量对象完全不受影响。
--         A 股与美股链路零依赖这些表，DROP 后系统行为回到 P2 之前。
--
-- ⚠️ DROP 会丢失表内数据且不可恢复。执行前先确认表内是测试数据，或按需先备份：
--   mysqldump --single-transaction data_db crypto_order crypto_fill crypto_position > v10_backup.sql
-- ============================================

USE `data_db`;

DROP TABLE IF EXISTS `crypto_option_greeks`;
DROP TABLE IF EXISTS `crypto_mark_price`;
DROP TABLE IF EXISTS `crypto_funding_rate`;
DROP TABLE IF EXISTS `crypto_book_snapshot`;
DROP TABLE IF EXISTS `crypto_position`;
DROP TABLE IF EXISTS `crypto_fill`;
DROP TABLE IF EXISTS `crypto_order`;
DROP TABLE IF EXISTS `crypto_instrument`;
