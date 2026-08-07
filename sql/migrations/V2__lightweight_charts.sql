-- ============================================
-- 迁移脚本 V2：Lightweight Charts 多周期 + 实时数据
-- 执行时间：阶段 1
-- ============================================

USE `trade_db`;

-- 1. 分钟 K 线（1/5/15/60 分钟）
DROP TABLE IF EXISTS `stock_minute_data`;
CREATE TABLE `stock_minute_data` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `stock_code` VARCHAR(20) NOT NULL COMMENT '股票代码（Tushare 格式 300750.SZ）',
    `trade_time` DATETIME NOT NULL COMMENT 'K 线时间（精确到分钟）',
    `period` TINYINT NOT NULL COMMENT '周期：1/5/15/60',
    `open_price` DECIMAL(10,3) DEFAULT NULL COMMENT '开盘价',
    `high_price` DECIMAL(10,3) DEFAULT NULL COMMENT '最高价',
    `low_price` DECIMAL(10,3) DEFAULT NULL COMMENT '最低价',
    `close_price` DECIMAL(10,3) DEFAULT NULL COMMENT '收盘价',
    `vol` BIGINT DEFAULT NULL COMMENT '成交量（手）',
    `amount` DECIMAL(16,3) DEFAULT NULL COMMENT '成交额（千元）',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code_time_period` (`stock_code`, `trade_time`, `period`),
    KEY `idx_code_period_time` (`stock_code`, `period`, `trade_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='分钟 K 线数据';

-- 2. 周 K 线
DROP TABLE IF EXISTS `stock_weekly_data`;
CREATE TABLE `stock_weekly_data` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `stock_code` VARCHAR(20) NOT NULL,
    `trade_date` DATE NOT NULL,
    `open_price` DECIMAL(10,3),
    `high_price` DECIMAL(10,3),
    `low_price` DECIMAL(10,3),
    `close_price` DECIMAL(10,3),
    `vol` BIGINT,
    `amount` DECIMAL(16,3),
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code_date` (`stock_code`, `trade_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='周 K 线';

-- 3. 月 K 线
DROP TABLE IF EXISTS `stock_monthly_data`;
CREATE TABLE `stock_monthly_data` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `stock_code` VARCHAR(20) NOT NULL,
    `trade_date` DATE NOT NULL,
    `open_price` DECIMAL(10,3),
    `high_price` DECIMAL(10,3),
    `low_price` DECIMAL(10,3),
    `close_price` DECIMAL(10,3),
    `vol` BIGINT,
    `amount` DECIMAL(16,3),
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code_date` (`stock_code`, `trade_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='月 K 线';

-- 4. 实时行情快照（5 档盘口）
DROP TABLE IF EXISTS `realtime_quote`;
CREATE TABLE `realtime_quote` (
    `stock_code` VARCHAR(20) NOT NULL COMMENT '股票代码',
    `trade_date` DATE DEFAULT NULL,
    `close_price` DECIMAL(10,3) DEFAULT NULL COMMENT '最新价',
    `pre_close` DECIMAL(10,3) DEFAULT NULL COMMENT '昨收',
    `open_price` DECIMAL(10,3) DEFAULT NULL COMMENT '今开',
    `high_price` DECIMAL(10,3) DEFAULT NULL,
    `low_price` DECIMAL(10,3) DEFAULT NULL,
    `vol` BIGINT DEFAULT NULL COMMENT '成交量',
    `amount` DECIMAL(16,3) DEFAULT NULL COMMENT '成交额',
    -- 买盘 5 档
    `bid1_price` DECIMAL(10,3) DEFAULT NULL, `bid1_vol` INT DEFAULT NULL,
    `bid2_price` DECIMAL(10,3) DEFAULT NULL, `bid2_vol` INT DEFAULT NULL,
    `bid3_price` DECIMAL(10,3) DEFAULT NULL, `bid3_vol` INT DEFAULT NULL,
    `bid4_price` DECIMAL(10,3) DEFAULT NULL, `bid4_vol` INT DEFAULT NULL,
    `bid5_price` DECIMAL(10,3) DEFAULT NULL, `bid5_vol` INT DEFAULT NULL,
    -- 卖盘 5 档
    `ask1_price` DECIMAL(10,3) DEFAULT NULL, `ask1_vol` INT DEFAULT NULL,
    `ask2_price` DECIMAL(10,3) DEFAULT NULL, `ask2_vol` INT DEFAULT NULL,
    `ask3_price` DECIMAL(10,3) DEFAULT NULL, `ask3_vol` INT DEFAULT NULL,
    `ask4_price` DECIMAL(10,3) DEFAULT NULL, `ask4_vol` INT DEFAULT NULL,
    `ask5_price` DECIMAL(10,3) DEFAULT NULL, `ask5_vol` INT DEFAULT NULL,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`stock_code`),
    KEY `idx_update_time` (`update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='实时行情快照（5 档）';

-- 5. 用户自选股（放到 auth_db）
USE `auth_db`;
DROP TABLE IF EXISTS `user_watchlist`;
CREATE TABLE `user_watchlist` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT UNSIGNED NOT NULL,
    `stock_code` VARCHAR(20) NOT NULL COMMENT '纯数字代码 300750',
    `stock_name` VARCHAR(64) DEFAULT NULL,
    `sort_order` INT DEFAULT 0,
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_stock` (`user_id`, `stock_code`),
    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户自选股';

-- 6. 图表画线持久化（放到 trade_db）
USE `trade_db`;
DROP TABLE IF EXISTS `chart_drawing`;
CREATE TABLE `chart_drawing` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT UNSIGNED NOT NULL,
    `stock_code` VARCHAR(20) NOT NULL,
    `period` VARCHAR(10) NOT NULL DEFAULT '1D',
    `drawing_data` JSON NOT NULL COMMENT 'TradingView/LightweightCharts 画线数据',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_stock_period` (`user_id`, `stock_code`, `period`),
    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='图表画线持久化';
