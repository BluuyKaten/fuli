-- ============================================
-- V14：风控状态表 risk_state（阶段 3.4 四道风控）
-- 归属服务：data-service
-- 关联 ADR：docs/adr/0011（加密回测）外，见 docs/multi-market-rollout.md 阶段 3.4
--
-- 手工执行（项目未接入 Flyway）：
--   mysql -uroot -p data_db < sql/migrations/V14__risk_state.sql
--
-- 可重入：CREATE TABLE IF NOT EXISTS，重复执行无副作用。
-- 回滚：DROP TABLE risk_state（见 V14__risk_state_rollback.sql）
-- ============================================

USE `data_db`;

-- 每个用户一行：回撤峰值基准 + 当日累计下单金额（跨天自动归零）
CREATE TABLE IF NOT EXISTS `risk_state` (
  `user_id`        BIGINT        NOT NULL COMMENT '用户 ID',
  `peak_equity`    DECIMAL(28,8) NULL COMMENT '峰值权益（回撤基准），首次下单取当前权益',
  `daily_notional` DECIMAL(28,8) NOT NULL DEFAULT 0 COMMENT '当日累计下单金额（计价币）',
  `daily_date`     DATE          NULL COMMENT 'daily_notional 对应的交易日',
  `updated_at`     DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='风控状态（四道风控）';
