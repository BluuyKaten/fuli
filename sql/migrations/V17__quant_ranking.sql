-- 大智慧量化排行：交易日快照、排行明细和企业微信通知
-- 手工执行：mysql -uroot -p trade_db < sql/migrations/V17__quant_ranking.sql
USE `trade_db`;
CREATE TABLE IF NOT EXISTS quant_rank_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    trading_date DATE NOT NULL,
    source_type VARCHAR(32) NOT NULL DEFAULT 'dazhihui',
    source_name VARCHAR(255) DEFAULT NULL,
    raw_column_mapping_json TEXT DEFAULT NULL,
    imported_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_quant_snapshot_user_date UNIQUE (user_id, trading_date)
);

CREATE TABLE IF NOT EXISTS quant_rank_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    snapshot_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    trading_date DATE NOT NULL,
    stock_code VARCHAR(32) NOT NULL,
    stock_name VARCHAR(128) NOT NULL,
    metric VARCHAR(128) DEFAULT NULL,
    metric_value DECIMAL(20, 8) NOT NULL,
    `rank` INT NOT NULL,
    raw_fields_json TEXT DEFAULT NULL,
    CONSTRAINT uk_quant_item_snapshot_code UNIQUE (snapshot_id, stock_code),
    CONSTRAINT fk_quant_item_snapshot FOREIGN KEY (snapshot_id) REFERENCES quant_rank_snapshot(id)
);

CREATE INDEX idx_quant_item_user_date ON quant_rank_item (user_id, trading_date);

CREATE TABLE IF NOT EXISTS quant_notification_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 0,
    webhook_url VARCHAR(1024) DEFAULT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_quant_notification_user UNIQUE (user_id)
);

CREATE TABLE IF NOT EXISTS quant_notification_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    trading_date DATE DEFAULT NULL,
    channel VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    message TEXT DEFAULT NULL,
    error VARCHAR(1024) DEFAULT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
