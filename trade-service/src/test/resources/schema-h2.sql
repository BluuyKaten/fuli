-- H2 测试表结构(对齐 trade_db)
CREATE TABLE IF NOT EXISTS stock_info (
    stock_code VARCHAR(32) NOT NULL,
    stock_name VARCHAR(64),
    area VARCHAR(64),
    industry VARCHAR(64),
    market VARCHAR(16),
    list_date VARCHAR(32),
    status TINYINT DEFAULT 1,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (stock_code)
);

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
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0
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

CREATE TABLE IF NOT EXISTS stock_daily_data (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_code VARCHAR(32) NOT NULL,
    trade_date VARCHAR(8) NOT NULL,
    open_price DECIMAL(12,4),
    high_price DECIMAL(12,4),
    low_price DECIMAL(12,4),
    close_price DECIMAL(12,4),
    pre_close DECIMAL(12,4),
    change_amount DECIMAL(12,4),
    pct_chg DECIMAL(12,4),
    vol DECIMAL(20,4),
    amount DECIMAL(20,4),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (stock_code, trade_date)
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
