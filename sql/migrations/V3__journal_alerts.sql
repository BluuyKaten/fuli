-- ============================================
-- V3: 交易复盘笔记 (trade_journal) 与 风控价格预警 (price_alert)
-- ============================================
USE `trade_db`;

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
