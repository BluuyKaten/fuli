-- ============================================
-- V12 回滚：账户余额快照表
--
-- 状态：**未执行**。只 DROP V12 新建的表，不触碰任何存量对象。
-- 回滚前建议先导出：mysqldump --single-transaction data_db crypto_account > crypto_account.sql
-- ============================================

USE `data_db`;

DROP TABLE IF EXISTS `crypto_account`;
