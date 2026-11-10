-- ============================================
-- V12：账户余额快照表（WS account 频道落库）
--
-- 状态：**待执行**。归属服务：data-service。
-- 关联 ADR：docs/adr/0010-...（决策 3 审计、决策 5 WS 身份）
--
-- 背景：V10 按 Q8 隔离方案只建了 8 张加密域表，`account` 频道一直只记 debug。
-- 余额是「账户视角」的事实，与订单/持仓分开存，避免把快照塞进订单表。
--
-- 设计要点：
--   1) 每次推送按 (user_id, ccy, u_time) 唯一：WS 会定时重推同一份快照，
--      靠唯一键去重，而不是靠应用层「看起来一样就跳过」。
--   2) `ccy='TOTAL'` 是合成行，存 OKX 的 `totalEq`（全账户折合 USD 权益），
--      方便画一条总权益曲线；真实币种行来自 `details[]`。
--   3) 只存快照，不存流水：这是「视图」，不是账本。
--   4) 保留 90 天，由 OkxAccountSnapshotRetentionJob 每天分批删。
--
-- 回滚：V12__crypto_account_snapshot_rollback.sql
-- ============================================

USE `data_db`;

CREATE TABLE IF NOT EXISTS `crypto_account` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`     BIGINT UNSIGNED NOT NULL COMMENT '归属用户（WS 的 okx.ws-owner-user-id）',
  `ccy`         VARCHAR(16)   NOT NULL COMMENT '币种；TOTAL 为合成行，存全账户折合权益',
  `eq`          DECIMAL(28,8) NULL COMMENT '该币种权益（折合 USD）',
  `cash_bal`    DECIMAL(28,8) NULL COMMENT '现金余额',
  `avail_bal`   DECIMAL(28,8) NULL COMMENT '可用余额',
  `avail_eq`    DECIMAL(28,8) NULL COMMENT '可用保证金',
  `frozen_bal`  DECIMAL(28,8) NULL COMMENT '冻结（挂单占用）',
  `upl`         DECIMAL(28,8) NULL COMMENT '未实现盈亏',
  `iso_eq`      DECIMAL(28,8) NULL COMMENT '逐仓权益',
  `dis_eq`      DECIMAL(28,8) NULL COMMENT '全仓权益',
  `u_time`      BIGINT        NOT NULL COMMENT 'OKX 快照时间，毫秒 epoch',
  `snapshot_at` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '落库时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_crypto_account_user_ccy_utime` (`user_id`, `ccy`, `u_time`),
  KEY `idx_crypto_account_user_time` (`user_id`, `snapshot_at`),
  KEY `idx_crypto_account_ccy_time` (`ccy`, `snapshot_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='OKX 账户余额快照（WS account 频道）';
