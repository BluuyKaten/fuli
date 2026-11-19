-- H2 测试表结构(对齐 trade_db)
CREATE TABLE IF NOT EXISTS trade_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    stock_code VARCHAR(16) NOT NULL,
    stock_name VARCHAR(64) NOT NULL,
    trade_type TINYINT NOT NULL,
    trade_price DECIMAL(12,4) NOT NULL,
    trade_quantity INT NOT NULL,
    trade_amount DECIMAL(16,4) NOT NULL,
    commission DECIMAL(12,4) NOT NULL DEFAULT 0,
    tax DECIMAL(12,4) NOT NULL DEFAULT 0,
    total_cost DECIMAL(16,4) NOT NULL,
    profit_loss DECIMAL(16,4) DEFAULT NULL,
    profit_loss_ratio DECIMAL(8,4) DEFAULT NULL,
    trade_date DATE NOT NULL,
    trade_time TIMESTAMP DEFAULT NULL,
    remark VARCHAR(512) DEFAULT NULL,
    request_id VARCHAR(64) DEFAULT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_user_request UNIQUE (user_id, request_id)
);

CREATE TABLE IF NOT EXISTS position_summary (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    stock_code VARCHAR(16) NOT NULL,
    stock_name VARCHAR(64) NOT NULL,
    total_quantity INT NOT NULL DEFAULT 0,
    avg_cost DECIMAL(12,4) NOT NULL DEFAULT 0,
    current_price DECIMAL(12,4) DEFAULT NULL,
    market_value DECIMAL(16,4) DEFAULT NULL,
    unrealized_pnl DECIMAL(16,4) DEFAULT NULL,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_user_stock UNIQUE (user_id, stock_code)
);

CREATE TABLE IF NOT EXISTS local_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    msg_id VARCHAR(64) NOT NULL,
    topic VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    status TINYINT NOT NULL DEFAULT 0,
    retry_count INT NOT NULL DEFAULT 0,
    max_retry INT NOT NULL DEFAULT 3,
    next_retry_time TIMESTAMP DEFAULT NULL,
    last_error VARCHAR(512) DEFAULT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (msg_id)
);

-- Same columns as sql/practice.sql; snapshot is inserted once and never updated.
CREATE TABLE IF NOT EXISTS practice_session (
    id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    stock_code VARCHAR(20),
    start_date VARCHAR(10),
    end_date VARCHAR(10),
    current_day VARCHAR(10),
    status VARCHAR(20),
    equity DECIMAL(18,2),
    initial_cash DECIMAL(18,2),
    version BIGINT NOT NULL DEFAULT 0,
    state_json LONGTEXT NOT NULL,
    snapshot_json LONGTEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_practice_owner_created ON practice_session (user_id, created_at);

CREATE TABLE IF NOT EXISTS trade_journal (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(128) NOT NULL,
    stock_code VARCHAR(32) DEFAULT NULL,
    stock_name VARCHAR(64) DEFAULT NULL,
    journal_date DATE NOT NULL,
    emotion_score TINYINT NOT NULL DEFAULT 3,
    market_sentiment VARCHAR(32) DEFAULT NULL,
    strategy_type VARCHAR(64) DEFAULT NULL,
    content TEXT NOT NULL,
    reflection TEXT DEFAULT NULL,
    tags VARCHAR(256) DEFAULT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS price_alert (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    stock_code VARCHAR(32) NOT NULL,
    stock_name VARCHAR(64) NOT NULL,
    alert_type TINYINT NOT NULL,
    target_value DECIMAL(12, 4) NOT NULL,
    notes VARCHAR(256) DEFAULT NULL,
    status TINYINT NOT NULL DEFAULT 0,
    triggered_time TIMESTAMP DEFAULT NULL,
    triggered_value DECIMAL(12, 4) DEFAULT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS quant_rank_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    trading_date DATE NOT NULL,
    source_type VARCHAR(32) NOT NULL DEFAULT 'dazhihui',
    source_name VARCHAR(255) DEFAULT NULL,
    raw_column_mapping_json CLOB DEFAULT NULL,
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
    rank INT NOT NULL,
    raw_fields_json CLOB DEFAULT NULL,
    CONSTRAINT uk_quant_item_snapshot_code UNIQUE (snapshot_id, stock_code),
    CONSTRAINT fk_quant_item_snapshot FOREIGN KEY (snapshot_id) REFERENCES quant_rank_snapshot(id)
);
CREATE INDEX IF NOT EXISTS idx_quant_item_user_date ON quant_rank_item (user_id, trading_date);

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
    message CLOB DEFAULT NULL,
    error VARCHAR(1024) DEFAULT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);


-- 量化排行自动化：配置与最近执行结果
CREATE TABLE IF NOT EXISTS quant_automation_config (
    user_id BIGINT NOT NULL PRIMARY KEY,
    enabled TINYINT NOT NULL DEFAULT 0,
    executable_path VARCHAR(1024) NOT NULL DEFAULT '',
    startup_catch_up TINYINT NOT NULL DEFAULT 1,
    last_attempt_at TIMESTAMP NULL,
    last_success_date DATE NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    last_run_date DATE NULL,
    last_status VARCHAR(64) NULL,
    last_imported_rows INT NOT NULL DEFAULT 0,
    last_message VARCHAR(2048) NULL
);
