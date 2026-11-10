-- ============================================
-- V11（auth_db 部分）回滚：用户级 OKX 写权限
--
-- 状态：**未执行**。执行后所有用户立即失去 OKX 交易权限（data-service 读不到该列会
-- fail-closed 拒绝写操作），因此必须与代码回滚同步进行。
--
-- 该列不承载历史数据（授权状态可从审计日志重建），但仍建议先导出：
--   mysqldump --single-transaction --no-create-info auth_db sys_user > sys_user_backup.sql
-- ============================================

USE `auth_db`;

SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'auth_db' AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'okx_trade_enabled'
);
SET @ddl := IF(@col_exists = 1,
  'ALTER TABLE `sys_user` DROP COLUMN `okx_trade_enabled`',
  'SELECT ''okx_trade_enabled 不存在，跳过'' AS msg');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
