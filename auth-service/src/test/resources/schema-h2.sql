-- auth-service H2 测试表结构
CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL,
    password VARCHAR(255) NOT NULL,
    nickname VARCHAR(50),
    email VARCHAR(100),
    phone VARCHAR(20),
    avatar VARCHAR(255),
    cash DECIMAL(18,2) DEFAULT 0,
    status INT DEFAULT 1,
    last_login_time TIMESTAMP,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted INT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS idempotent_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    msg_id VARCHAR(100) NOT NULL,
    user_id BIGINT NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    cash_direction INT NOT NULL,
    status INT DEFAULT 0,
    error_msg VARCHAR(500),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_msg_id (msg_id)
);

CREATE TABLE IF NOT EXISTS user_watchlist (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    stock_code VARCHAR(20) NOT NULL,
    stock_name VARCHAR(50),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
