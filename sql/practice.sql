-- Non-destructive upgrade for existing trade_db installations.
USE `trade_db`;

-- Full account state is private server-side JSON. snapshot_json is immutable after INSERT.
-- Mutations lock by (id,user_id) then conditionally update version and state_json together.
CREATE TABLE IF NOT EXISTS `practice_session` (
    `id` VARCHAR(36) NOT NULL,
    `user_id` BIGINT NOT NULL,
    `stock_code` VARCHAR(20) DEFAULT NULL COMMENT '股票代码（摘要列，list 免解析 state_json）',
    `start_date` VARCHAR(10) DEFAULT NULL COMMENT '起始日（摘要列）',
    `end_date` VARCHAR(10) DEFAULT NULL COMMENT '结束日（摘要列）',
    `current_day` VARCHAR(10) DEFAULT NULL COMMENT '当前决策日（摘要列）',
    `status` VARCHAR(20) DEFAULT NULL COMMENT '状态 ACTIVE/FINISHED（摘要列）',
    `equity` DECIMAL(18,2) DEFAULT NULL COMMENT '当前权益（摘要列）',
    `initial_cash` DECIMAL(18,2) DEFAULT NULL COMMENT '初始资金（摘要列）',
    `version` BIGINT NOT NULL DEFAULT 0,
    `state_json` LONGTEXT NOT NULL,
    `snapshot_json` LONGTEXT NOT NULL,
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_practice_owner_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='历史盲测独立账户与冻结行情';
