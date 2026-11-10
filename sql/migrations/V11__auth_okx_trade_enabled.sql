-- ============================================
-- V11（auth_db 部分）：用户级 OKX 写权限
--
-- 状态：**待执行**。归属服务：auth-service。
-- 关联 ADR：docs/adr/0010-okx-multiuser-credentials-and-agent-readonly.md（决策 2）
--
-- 手工执行：
--   mysql -uroot -p auth_db < sql/migrations/V11__auth_okx_trade_enabled.sql
--
-- 与 V11__okx_multiuser_credentials.sql 同属一次发布：一个改 data_db、一个改 auth_db，
-- 拆成两个文件是为了回滚粒度（凭证表可以单独回滚，用户权限列不能）。
--
-- 设计说明：
--   * 默认 0 = 所有人默认不可交易。这是「替换 OKX_WRITE_USER_IDS 环境变量白名单」的核心：
--     白名单是运维改配置，用户级开关是数据、可审计、可授权到个人。
--   * 本脚本只加列，不改任何现有列与索引；sys_user 只有 3 行，ALTER 是秒级。
--   * 列可重复执行（先查 information_schema 再决定是否 ALTER）。
--
-- 回滚：V11__auth_okx_trade_enabled_rollback.sql（DROP COLUMN，会丢失授权状态）
-- ============================================

USE `auth_db`;

SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'auth_db' AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'okx_trade_enabled'
);
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE `sys_user` ADD COLUMN `okx_trade_enabled` TINYINT NOT NULL DEFAULT 0 COMMENT ''OKX 交易权限：0-禁止 1-允许（仍需 okx.trade-enabled 全局总闸）'' AFTER `status`',
  'SELECT ''okx_trade_enabled 已存在，跳过'' AS msg');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 授权（按需手工执行，不要放进迁移脚本） ----------
-- 查看当前授权：
--   SELECT id, username, okx_trade_enabled FROM auth_db.sys_user;
-- 授权给某个用户（示例：id=1）：
--   UPDATE auth_db.sys_user SET okx_trade_enabled = 1 WHERE id = 1;
-- 收回：
--   UPDATE auth_db.sys_user SET okx_trade_enabled = 0 WHERE id = 1;
--
-- 生效时延：data-service 侧有 30 秒的权限缓存（okx.auth.permission-cache-seconds），
-- 收回权限最迟 30 秒后生效；撤销令牌可立即生效。
