-- ============================================
-- V7 (2/2)：A 股历史数据迁移 —— instrument 回填 + bar 迁移
-- 归属服务：data-service
-- 关联 ADR：docs/adr/0006-instrument-identity-and-symbol-scheme.md
--           docs/adr/0007-unified-bar-store-and-time-axis.md
-- 执行手册：docs/multi-market-stage0.md §7 / §8
--
-- 前置：先执行 V7__instrument_bar_adj_factor.sql 建表。
-- 手工执行（项目未接入 Flyway）：
--   mysql -uroot -p data_db < sql/migrations/V7__migrate_a_share_bars.sql
--
-- 可重入：instrument 走 ON DUPLICATE KEY UPDATE；bar 冲突时以同源值覆盖（幂等）。
-- 迁移期间可安全重跑（18:00 定时任务先于迁移执行时，靠重跑补齐当天增量）。
--
-- ⚠️ 单位换算的唯一落点（另一处是 TushareSyncServiceImpl.parseDailyData 出口）：
--      Tushare amount 千元 → 元   ×1000
--      Tushare vol    手   → 股   ×100
--      pre_close 原样搬运，不换算、不丢弃（除权日前收盘无法从 OHLC 推算）
--    严禁把「÷1000」写回任何地方。
--
-- ⚠️ 时区：UNIX_TIMESTAMP() 按「会话时区」解释入参。本机默认 +08:00，
--    若不固定会话时区，算出的 ts 会整体早 8 小时，且用 FROM_UNIXTIME 在同一
--    时区回看仍显示 09:30（看起来正确）。下面显式设为 UTC，与 §9 校验一致。
-- ============================================

USE `data_db`;

SET SESSION time_zone = '+00:00';

-- ---------- §7 回填 instrument（A 股，来自 stock_info） ----------
-- stock_info.market 存的是 Tushare 板块（主板/创业板/科创板/北交所），不是交易所，
-- 因此 market 一律取自符号后缀，不读 market 列。
INSERT INTO `instrument` (`code`, `market`, `name`, `currency`, `price_scale`, `lot_size`,
                          `tick_size`, `tz`, `session`, `status`, `exchange`)
SELECT `stock_code`,
       SUBSTRING_INDEX(`stock_code`, '.', -1)          AS market,
       COALESCE(`stock_name`, '')                      AS name,
       'CNY'                                           AS currency,
       2                                               AS price_scale,
       100                                             AS lot_size,
       0.01                                            AS tick_size,
       'Asia/Shanghai'                                 AS tz,
       'DAY'                                           AS session,
       IFNULL(`status`, 1)                             AS status,
       NULL                                            AS exchange
FROM `stock_info`
WHERE `stock_code` REGEXP '^[0-9]{6}\\.(SH|SZ|BJ)$'
ON DUPLICATE KEY UPDATE
  `market`=VALUES(`market`), `name`=VALUES(`name`), `status`=VALUES(`status`),
  `update_time`=NOW();

-- ---------- §8 bar 迁移（1d，交易日 01:30 UTC） ----------
-- trade_date(INT yyyyMMdd) → 当日 09:30 CST = 01:30 UTC = 当日 00:00 UTC + 5400s
INSERT INTO `bar` (`instrument_code`, `period`, `ts`,
                   `open_price`, `high_price`, `low_price`, `close_price`,
                   `pre_close`, `volume`, `amount`)
SELECT `stock_code`, '1d',
       UNIX_TIMESTAMP(STR_TO_DATE(CONCAT(`trade_date`, ' 093000'), '%Y%m%d %H%i%s')) - 28800,
       `open_price`, `high_price`, `low_price`, `close_price`,
       `pre_close`,
       `vol` * 100,          -- 手 → 股
       `amount` * 1000       -- 千元 → 元
FROM `stock_daily_data`
ON DUPLICATE KEY UPDATE
  `open_price`=VALUES(`open_price`), `high_price`=VALUES(`high_price`),
  `low_price`=VALUES(`low_price`),   `close_price`=VALUES(`close_price`),
  `pre_close`=VALUES(`pre_close`),
  `volume`=VALUES(`volume`),         `amount`=VALUES(`amount`);

-- ---------- 迁移后自检（只读，便于当场发现问题） ----------
SELECT '--- instrument ---' AS step;
SELECT COUNT(*) AS instrument_rows FROM `instrument`;

SELECT '--- bar ---' AS step;
SELECT (SELECT COUNT(*) FROM `stock_daily_data`)                       AS src_rows,
       (SELECT COUNT(*) FROM `bar` WHERE `period`='1d')                AS dst_rows,
       (SELECT ROUND(SUM(`amount`),4)*1000 FROM `stock_daily_data`)    AS src_amount_x1000,
       (SELECT ROUND(SUM(`amount`),4) FROM `bar` WHERE `period`='1d')  AS dst_amount,
       (SELECT ROUND(SUM(`vol`),4)*100 FROM `stock_daily_data`)        AS src_vol_x100,
       (SELECT ROUND(SUM(`volume`),4) FROM `bar` WHERE `period`='1d')  AS dst_volume;

-- 时间轴自检：必须在 2005-07-13 01:30 UTC ~ 最新交易日 01:30 UTC
SELECT FROM_UNIXTIME(MIN(`ts`), '%Y-%m-%d %H:%i:%s') AS first_bar_utc,
       FROM_UNIXTIME(MAX(`ts`), '%Y-%m-%d %H:%i:%s') AS last_bar_utc
FROM `bar` WHERE `period`='1d';
