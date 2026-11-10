-- ============================================
-- data_db 数据库 - 行情数据（股票基础信息 + 日线数据）
-- 归属服务：data-service
--
-- 【阶段 0 之后的权威定义】
--   行情三张表以 `sql/migrations/V7__instrument_bar_adj_factor.sql` 为准：
--     instrument（标的属性）、bar（全市场全周期）、adj_factor（复权因子）
--   本文件保留 stock_info / stock_daily_data 的建表，是因为阶段 0 过渡期内
--   双写仍在跑（bar 是正向写，stock_daily_data 是回滚靶子）；
--   双写观察满 2 周后按 0.9 停双写并 DROP stock_daily_data，届时再删本段。
--   注意：本文件的 stock_daily_data 是**空表结构**，历史数据的唯一真相是
--   迁移脚本 + Tushare 同步，不要用这里的 DROP/CREATE 去重建生产表。
-- ============================================

CREATE DATABASE IF NOT EXISTS `data_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE `data_db`;

-- 股票基础信息表
DROP TABLE IF EXISTS `stock_info`;
CREATE TABLE `stock_info` (
    `stock_code` VARCHAR(32) NOT NULL COMMENT '股票代码（如 000001.SZ）',
    `stock_name` VARCHAR(64) DEFAULT NULL COMMENT '股票名称',
    `area` VARCHAR(64) DEFAULT NULL COMMENT '地域',
    `industry` VARCHAR(64) DEFAULT NULL COMMENT '行业',
    `list_date` VARCHAR(32) DEFAULT NULL COMMENT '上市日期',
    `market` VARCHAR(16) DEFAULT NULL COMMENT '市场 SH/SZ/BJ',
    `status` TINYINT(1) DEFAULT '1' COMMENT '状态 1正常 0停牌',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`stock_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='股票基础信息表';

-- 股票日线数据表
-- 列类型经 V6 迁移瘦身：stock_code VARCHAR(32)->VARCHAR(9)，trade_date VARCHAR(8)->INT
-- 原因见 docs/adr/0003-daily-table-column-slimming.md（key_len 164->42，索引 583MB->150MB）
DROP TABLE IF EXISTS `stock_daily_data`;
CREATE TABLE `stock_daily_data` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `stock_code` VARCHAR(9) NOT NULL COMMENT '股票代码，如 000001.SZ',
    `trade_date` INT NOT NULL COMMENT '交易日期，如 20231103',
    `open_price` DECIMAL(12,4) DEFAULT NULL COMMENT '开盘价',
    `high_price` DECIMAL(12,4) DEFAULT NULL COMMENT '最高价',
    `low_price` DECIMAL(12,4) DEFAULT NULL COMMENT '最低价',
    `close_price` DECIMAL(12,4) DEFAULT NULL COMMENT '收盘价',
    `pre_close` DECIMAL(12,4) DEFAULT NULL COMMENT '昨收价',
    `change_amount` DECIMAL(12,4) DEFAULT NULL COMMENT '涨跌额',
    `pct_chg` DECIMAL(12,4) DEFAULT NULL COMMENT '涨跌幅',
    `vol` DECIMAL(20,4) DEFAULT NULL COMMENT '成交量(手)',
    `amount` DECIMAL(20,4) DEFAULT NULL COMMENT '成交额(千元)',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code_trade_date` (`stock_code`,`trade_date`),
    KEY `idx_trade_date` (`trade_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='股票日线行情数据';
