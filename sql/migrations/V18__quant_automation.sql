USE `trade_db`;
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
