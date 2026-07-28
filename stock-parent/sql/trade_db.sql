CREATE DATABASE IF NOT EXISTS trade_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE trade_db;

CREATE TABLE IF NOT EXISTS `trade_record` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL,
  `symbol` VARCHAR(32) NOT NULL,
  `side` VARCHAR(16) NOT NULL,
  `quantity` DECIMAL(18,4) NOT NULL,
  `price` DECIMAL(18,4) NOT NULL,
  `profit_loss` DECIMAL(18,4) DEFAULT 0,
  `trade_time` DATETIME NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_trade_user_time` (`user_id`, `trade_time`),
  KEY `idx_trade_symbol` (`symbol`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
