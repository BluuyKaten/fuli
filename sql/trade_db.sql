-- ============================================
-- trade_db 数据库 - 交易数据 + 股票数据
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
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除 1-已删除',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_stock_code` (`stock_code`),
    KEY `idx_trade_date` (`trade_date`),
    KEY `idx_user_stock` (`user_id`, `stock_code`),
    KEY `idx_user_date` (`user_id`, `trade_date`)
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

-- 股票基础信息表
DROP TABLE IF EXISTS `stock_info`;
CREATE TABLE `stock_info` (
    `stock_code` VARCHAR(32) NOT NULL COMMENT '股票代码（如 000001.SZ）',
    `stock_name` VARCHAR(64) DEFAULT NULL COMMENT '股票名称',
    `area` VARCHAR(64) DEFAULT NULL COMMENT '地域',
    `industry` VARCHAR(64) DEFAULT NULL COMMENT '行业',
    `list_date` VARCHAR(32) DEFAULT NULL COMMENT '上市日期',
    `market` VARCHAR(16) DEFAULT NULL COMMENT '市场 SH/SZ',
    `status` TINYINT(1) DEFAULT '1' COMMENT '状态 1正常 0停牌',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`stock_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='股票基础信息表';

-- 股票日线数据表
DROP TABLE IF EXISTS `stock_daily_data`;
CREATE TABLE `stock_daily_data` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `stock_code` VARCHAR(32) NOT NULL COMMENT 'Stock code, for example 000001.SZ',
    `trade_date` VARCHAR(8) NOT NULL COMMENT 'Trade date, yyyyMMdd',
    `open_price` DECIMAL(12,4) DEFAULT NULL COMMENT 'Open price',
    `high_price` DECIMAL(12,4) DEFAULT NULL COMMENT 'High price',
    `low_price` DECIMAL(12,4) DEFAULT NULL COMMENT 'Low price',
    `close_price` DECIMAL(12,4) DEFAULT NULL COMMENT 'Close price',
    `pre_close` DECIMAL(12,4) DEFAULT NULL COMMENT 'Previous close',
    `change_amount` DECIMAL(12,4) DEFAULT NULL COMMENT 'Price change',
    `pct_chg` DECIMAL(12,4) DEFAULT NULL COMMENT 'Percent change',
    `vol` DECIMAL(20,4) DEFAULT NULL COMMENT 'Volume, lot',
    `amount` DECIMAL(20,4) DEFAULT NULL COMMENT 'Amount, thousand yuan',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code_trade_date` (`stock_code`,`trade_date`),
    KEY `idx_trade_date` (`trade_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Stock daily market data';
