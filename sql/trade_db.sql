-- ============================================
-- trade_db 数据库 - 交易数据
-- ============================================

CREATE DATABASE IF NOT EXISTS `trade_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE `trade_db`;

-- 交易记录表
DROP TABLE IF EXISTS `trade_record`;
CREATE TABLE `trade_record` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '交易ID',
    `user_id` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '用户ID',
    `stock_code` VARCHAR(16) NOT NULL COMMENT '股票代码',
    `stock_name` VARCHAR(64) NOT NULL COMMENT '股票名称',
    `trade_type` TINYINT NOT NULL COMMENT '交易类型：1-买入 2-卖出',
    `trade_price` DECIMAL(12, 4) NOT NULL COMMENT '成交价格',
    `trade_quantity` INT NOT NULL COMMENT '成交数量（股）',
    `trade_amount` DECIMAL(16, 4) NOT NULL COMMENT '成交金额',
    `commission` DECIMAL(12, 4) NOT NULL DEFAULT 0 COMMENT '手续费',
    `tax` DECIMAL(12, 4) NOT NULL DEFAULT 0 COMMENT '印花税',
    `total_cost` DECIMAL(16, 4) NOT NULL COMMENT '总成本（含手续费）',
    `profit_loss` DECIMAL(16, 4) DEFAULT NULL COMMENT '盈亏金额（卖出时计算）',
    `profit_loss_ratio` DECIMAL(8, 4) DEFAULT NULL COMMENT '盈亏比例（卖出时计算）',
    `trade_date` DATE NOT NULL COMMENT '交易日期',
    `trade_time` DATETIME DEFAULT NULL COMMENT '交易时间',
    `remark` VARCHAR(512) DEFAULT NULL COMMENT '备注',
    `request_id` VARCHAR(64) DEFAULT NULL COMMENT '客户端幂等键（重复提交去重，可为空）',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除 1-已删除',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_stock_code` (`stock_code`),
    KEY `idx_trade_date` (`trade_date`),
    KEY `idx_user_stock` (`user_id`, `stock_code`),
    KEY `idx_user_date` (`user_id`, `trade_date`),
    UNIQUE KEY `uk_user_request` (`user_id`, `request_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='交易记录表';

-- 持仓汇总表（可选，用于缓存持仓状态）
DROP TABLE IF EXISTS `position_summary`;
CREATE TABLE `position_summary` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id` BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
    `stock_code` VARCHAR(16) NOT NULL COMMENT '股票代码',
    `stock_name` VARCHAR(64) NOT NULL COMMENT '股票名称',
    `total_quantity` INT NOT NULL DEFAULT 0 COMMENT '持仓数量',
    `avg_cost` DECIMAL(12, 4) NOT NULL DEFAULT 0 COMMENT '平均成本',
    `current_price` DECIMAL(12, 4) DEFAULT NULL COMMENT '当前价格',
    `market_value` DECIMAL(16, 4) DEFAULT NULL COMMENT '市值',
    `unrealized_pnl` DECIMAL(16, 4) DEFAULT NULL COMMENT '浮动盈亏',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_stock` (`user_id`, `stock_code`),
    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='持仓汇总表';

-- 本地消息表（用于跨服务事务最终一致性）
DROP TABLE IF EXISTS `local_message`;
CREATE TABLE `local_message` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `msg_id` VARCHAR(64) NOT NULL COMMENT '消息唯一ID',
    `topic` VARCHAR(64) NOT NULL COMMENT '消息主题（如 TRADE_BUY / TRADE_SELL）',
    `payload` TEXT NOT NULL COMMENT '消息体（JSON）',
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '状态：0-PENDING 1-SUCCESS 2-FAILED 3-DEAD_LETTER',
    `retry_count` INT NOT NULL DEFAULT 0 COMMENT '已重试次数',
    `max_retry` INT NOT NULL DEFAULT 3 COMMENT '最大重试次数',
    `next_retry_time` DATETIME DEFAULT NULL COMMENT '下次重试时间',
    `last_error` VARCHAR(512) DEFAULT NULL COMMENT '最后一次错误信息',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_msg_id` (`msg_id`),
    KEY `idx_status_retry` (`status`, `next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='本地消息表（最终一致性）';

-- 历史盲测：同一事务锁定行并校验version；snapshot_json仅创建时写入。
CREATE TABLE IF NOT EXISTS `practice_session` (
    `id` VARCHAR(36) NOT NULL,
    `user_id` BIGINT NOT NULL,
    `stock_code` VARCHAR(20) DEFAULT NULL COMMENT '股票代码（摘要列）',
    `start_date` VARCHAR(10) DEFAULT NULL COMMENT '起始日（摘要列）',
    `end_date` VARCHAR(10) DEFAULT NULL COMMENT '结束日（摘要列）',
    `current_day` VARCHAR(10) DEFAULT NULL COMMENT '当前决策日（摘要列）',
    `status` VARCHAR(20) DEFAULT NULL COMMENT '状态（摘要列）',
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

-- 交易复盘笔记表
CREATE TABLE IF NOT EXISTS `trade_journal` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id` BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
    `title` VARCHAR(128) NOT NULL COMMENT '笔记标题',
    `stock_code` VARCHAR(32) DEFAULT NULL COMMENT '关联股票代码',
    `stock_name` VARCHAR(64) DEFAULT NULL COMMENT '关联股票名称',
    `journal_date` DATE NOT NULL COMMENT '复盘日期',
    `emotion_score` TINYINT NOT NULL DEFAULT 3 COMMENT '交易情绪分(1-5: 1恐慌/2谨慎/3理性/4乐观/5亢奋)',
    `market_sentiment` VARCHAR(32) DEFAULT NULL COMMENT '市场情绪评估',
    `strategy_type` VARCHAR(64) DEFAULT NULL COMMENT '交易策略分类',
    `content` TEXT NOT NULL COMMENT '复盘详情与逻辑推演',
    `reflection` TEXT DEFAULT NULL COMMENT '经验与教训反思',
    `tags` VARCHAR(256) DEFAULT NULL COMMENT '标签(逗号分隔)',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    KEY `idx_journal_user_date` (`user_id`, `journal_date`),
    KEY `idx_journal_user_stock` (`user_id`, `stock_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='交易复盘笔记';

-- 风控价格预警表
CREATE TABLE IF NOT EXISTS `price_alert` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id` BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
    `stock_code` VARCHAR(32) NOT NULL COMMENT '股票代码',
    `stock_name` VARCHAR(64) NOT NULL COMMENT '股票名称',
    `alert_type` TINYINT NOT NULL COMMENT '预警类型: 1-价格上限/止盈 2-价格下限/止损 3-日涨幅超过% 4-日跌幅超过%',
    `target_value` DECIMAL(12, 4) NOT NULL COMMENT '目标数值(价格或涨跌幅%)',
    `notes` VARCHAR(256) DEFAULT NULL COMMENT '风控备注',
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '状态: 0-监控中 1-已触发 2-已停用',
    `triggered_time` DATETIME DEFAULT NULL COMMENT '触发时间',
    `triggered_value` DECIMAL(12, 4) DEFAULT NULL COMMENT '触发时的行情值',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    KEY `idx_alert_user_status` (`user_id`, `status`),
    KEY `idx_alert_stock` (`stock_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='风控价格预警';

