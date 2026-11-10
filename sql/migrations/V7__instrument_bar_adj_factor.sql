-- ============================================
-- V7 (1/2)：多市场底座 DDL —— instrument / bar / adj_factor
-- 归属服务：data-service
-- 关联 ADR：docs/adr/0006-instrument-identity-and-symbol-scheme.md
--           docs/adr/0007-unified-bar-store-and-time-axis.md
-- 执行手册：docs/multi-market-stage0.md §6
--
-- 手工执行（项目未接入 Flyway）：
--   mysql -uroot -p data_db < sql/migrations/V7__instrument_bar_adj_factor.sql
--
-- 可重入：全部 CREATE TABLE IF NOT EXISTS，重复执行无副作用。
-- 本脚本只建表，不含历史数据；数据迁移见 V7__migrate_a_share_bars.sql。
--
-- 回滚：三张表均为新建，旧链路零依赖，直接 DROP 即可
--   （见 V7__instrument_bar_adj_factor_rollback.sql）
-- ============================================

USE `data_db`;

-- ---------- 标的主表 ----------
-- 符号只承载身份（<代码>.<市场>），交易所/币种/精度/交易单位/时区/时段全部外置到这里。
CREATE TABLE IF NOT EXISTS `instrument` (
  `code`        VARCHAR(20)   NOT NULL COMMENT '标的代码，如 600519.SH / AAPL.US / BTCUSDT.CRYPTO',
  `market`      VARCHAR(8)    NOT NULL COMMENT 'SH/SZ/BJ/US/CRYPTO',
  `name`        VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '名称',
  `currency`    VARCHAR(8)    NOT NULL COMMENT '计价币种：CNY/USD/USDT…',
  `price_scale` INT           NOT NULL COMMENT '小数位（东财 decimal 字段可直接填），前端显示精度',
  `lot_size`    DECIMAL(20,8) NOT NULL COMMENT '交易单位：A股100，美股1，加密小数（如 0.0001）',
  `tick_size`   DECIMAL(20,8) NOT NULL COMMENT '最小变动价位',
  `tz`          VARCHAR(32)   NOT NULL COMMENT 'IANA 时区，如 Asia/Shanghai / America/New_York / UTC',
  `session`     VARCHAR(16)   NOT NULL COMMENT 'DAY / 24H',
  `status`      TINYINT       NOT NULL DEFAULT 1 COMMENT '1 正常 0 停牌/摘牌',
  `exchange`    VARCHAR(16)   NULL COMMENT 'NASDAQ/NYSE/AMEX（US 必填）；A股/加密 可空',
  `create_time` DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`code`),
  KEY `idx_instrument_market` (`market`),
  CONSTRAINT `chk_instrument_market` CHECK (`market` IN ('SH','SZ','BJ','US','CRYPTO'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='标的主表';

-- ---------- 行情 K 线（原始价） ----------
-- 一张表覆盖全市场全周期，不按市场/周期分表（ADR-0007）。
-- 不复权落库：adjust 是查询参数，前复权在内存里按 adj_factor 算。
CREATE TABLE IF NOT EXISTS `bar` (
  `instrument_code` VARCHAR(20)   NOT NULL COMMENT '标的代码',
  `period`          VARCHAR(4)    NOT NULL COMMENT '1m/5m/15m/30m/1h/4h/1d/1w/1mo',
  `ts`              BIGINT        NOT NULL COMMENT 'bar 开盘时刻，UNIX 秒，UTC',
  `open_price`      DECIMAL(20,8) NULL,
  `high_price`      DECIMAL(20,8) NULL,
  `low_price`       DECIMAL(20,8) NULL,
  `close_price`     DECIMAL(20,8) NULL,
  `pre_close`       DECIMAL(20,8) NULL COMMENT '前收盘（除权口径，原样搬运，不换算）',
  `volume`          DECIMAL(28,8) NULL COMMENT '股/币',
  `amount`          DECIMAL(28,8) NULL COMMENT '计价货币元',
  PRIMARY KEY (`instrument_code`, `period`, `ts`),
  -- 周期代号大小写不敏感（utf8mb4_*_ci），1M/1m 会撞主键，故月线统一写 1mo。
  -- 受控集合在应用层已校验，这里加 CHECK 作为第二道防线：绕过应用也写不进非法周期。
  CONSTRAINT `chk_bar_period` CHECK (`period` IN ('1m','5m','15m','30m','1h','4h','1d','1w','1mo'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='行情 K 线';

-- ---------- 复权因子（每交易日一行，全量存储） ----------
-- 前复权：price × factor / factor_latest（factor_latest = 窗口末端最新一根 bar 的因子）
CREATE TABLE IF NOT EXISTS `adj_factor` (
  `instrument_code` VARCHAR(20)   NOT NULL,
  `ts`              BIGINT        NOT NULL COMMENT '与 1d bar 同口径：交易日 01:30 UTC（A股）/ 00:00 UTC（加密）',
  `factor`          DECIMAL(20,8) NOT NULL COMMENT 'Tushare 累积复权因子，恒 >0',
  PRIMARY KEY (`instrument_code`, `ts`),
  CONSTRAINT `chk_adj_factor_positive` CHECK (`factor` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='复权因子';
