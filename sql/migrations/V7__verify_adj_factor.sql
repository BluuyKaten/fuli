-- =====================================================================
-- V7 附：adj_factor 回填验收（阶段 0 · 0.4）
--
-- 与 V7__verify_bars.sql 同风格：每项给 PASS/FAIL，不通过即停。
-- 执行：mysql -uroot -p --table < sql/migrations/V7__verify_adj_factor.sql
--
-- 【口径修正 · 实测两处】
-- 1) V7__backfill_adj_factor.md 原文第 2 条「每个交易日的 ts 都必须与当日 bar
--    对齐（必须 0 行）」，若实现为「每条因子行都要有同名 bar 行」会恒为 FAIL：
--    Tushare `adj_factor?trade_date=` 返回**当日全部在市标的**，比本地
--    stock_daily_data 多出退市/停牌/未纳入同步的标的。因子是 bar 的**超集**。
--    故拆成：
--      A3 = 时间轴不变量：所有 ts 必须落在 01:30 UTC（每日 5400 秒偏移）→ 必须 0 行
--      A4 = 覆盖方向反过来看：bar 缺因子行数 → 必须 0 行（这才是影响复权的方向）
--      A5 = 因子未被 bar 覆盖的行数 → 仅统计，不做门禁
-- 3) 【多市场作用域 · 实测 2026-11-07】OKX 接入后 bar 里有了加密行，
--    它们本来就没有（也不需要）A 股复权因子。A4/A4b/A4c 必须限定 A 股符号，
--    否则 897 根加密 bar 会被算成「未解释的缺因子」，门禁假红。
-- 2) 因子可以「领先」bar 一个交易日：按标的补洞时 Tushare 已给出当日因子，
--    而 bar 要等下一次同步。A2 因此改为 fac_days >= src_days 且领先不超过 1 天。
-- =====================================================================

SET SESSION time_zone = '+00:00';

SELECT '=== A1 总量 ===' AS step;
SELECT COUNT(*)                 AS factor_rows,
       COUNT(DISTINCT ts)       AS days,
       COUNT(DISTINCT instrument_code) AS codes,
       MIN(factor)              AS min_factor,
       MAX(factor)              AS max_factor
FROM data_db.adj_factor;

SELECT '=== A2 交易日覆盖面：fac_days >= src_days 且领先 <= 1 天 ===' AS step;
SELECT src_days, fac_days, fac_days - src_days AS lead_days,
       CASE WHEN fac_days >= src_days AND fac_days - src_days <= 1
            THEN 'PASS' ELSE 'FAIL' END AS result
FROM (SELECT (SELECT COUNT(DISTINCT trade_date) FROM data_db.stock_daily_data) AS src_days,
             (SELECT COUNT(DISTINCT ts)         FROM data_db.adj_factor)      AS fac_days) t;

SELECT '=== A3 时间轴不变量：ts 必须是当日 01:30 UTC（必须 0 行）===' AS step;
SELECT COUNT(*) AS bad_offset_rows,
       CASE WHEN COUNT(*) = 0 THEN 'PASS' ELSE 'FAIL' END AS result
FROM data_db.adj_factor
WHERE ts % 86400 <> 5400;

SELECT '=== A4 bar 缺因子行数（全量；这是影响前复权的方向）===' AS step;
SELECT COUNT(*) AS bar_rows_without_factor
FROM data_db.bar b
WHERE b.period = '1d'
  AND b.instrument_code REGEXP '^[0-9]{6}\\.(SH|SZ|BJ)$'
  AND NOT EXISTS (
    SELECT 1 FROM data_db.adj_factor f
    WHERE f.instrument_code = b.instrument_code AND f.ts = b.ts
  );

-- 已知例外（源端无数据，非迁移缺陷）：
--   北交所 920 号段标的在「新代码 list_date」之前属于新三板历史，
--   Tushare 的 adj_factor 对这段既不按新代码、也不按旧代码供数
--   （实测 833751.BJ 按代码与按日期均返回 0 行），因此这 711 行永远补不上。
--   影响仅限这 11 只票 2020–2022 的日线：adjust=forward 在该段退化为原价。
SELECT '=== A4b 已知例外：北交所 920 号段 2023 年前（源端无因子，仅统计）===' AS step;
SELECT COUNT(*) AS known_bj_pre_2023,
       COUNT(DISTINCT b.instrument_code) AS codes
FROM data_db.bar b
WHERE b.period = '1d'
  AND b.instrument_code REGEXP '^[0-9]{6}\\.(SH|SZ|BJ)$'
  AND b.instrument_code LIKE '920%.BJ'
  AND b.ts < UNIX_TIMESTAMP('2023-01-01 00:00:00')
  AND NOT EXISTS (
    SELECT 1 FROM data_db.adj_factor f
    WHERE f.instrument_code = b.instrument_code AND f.ts = b.ts
  );

SELECT '=== A4c 门禁：排除已知例外后的缺因子行数（必须 0 行）===' AS step;
SELECT COUNT(*) AS unexplained_missing,
       CASE WHEN COUNT(*) = 0 THEN 'PASS' ELSE 'FAIL' END AS result
FROM data_db.bar b
WHERE b.period = '1d'
  AND b.instrument_code REGEXP '^[0-9]{6}\\.(SH|SZ|BJ)$'
  AND NOT EXISTS (
    SELECT 1 FROM data_db.adj_factor f
    WHERE f.instrument_code = b.instrument_code AND f.ts = b.ts
  )
  AND NOT (b.instrument_code LIKE '920%.BJ'
           AND b.ts < UNIX_TIMESTAMP('2023-01-01 00:00:00'));

SELECT '=== A5 因子未被 bar 覆盖（仅统计，不门禁：因子本就是 bar 的超集）===' AS step;
SELECT COUNT(*) AS factor_rows_without_bar
FROM data_db.adj_factor f
WHERE NOT EXISTS (
  SELECT 1 FROM data_db.bar b
  WHERE b.period = '1d' AND b.instrument_code = f.instrument_code AND b.ts = f.ts
);

SELECT '=== A6 因子恒正（CHECK 已兜底，确认存量）===' AS step;
SELECT COUNT(*) AS non_positive,
       CASE WHEN COUNT(*) = 0 THEN 'PASS' ELSE 'FAIL' END AS result
FROM data_db.adj_factor WHERE factor <= 0;

SELECT '=== A7 抽样：茅台最近 5 个交易日（无除权时因子应相同）===' AS step;
SELECT instrument_code,
       FROM_UNIXTIME(ts, '%Y-%m-%d %H:%i') AS d_utc,
       factor
FROM data_db.adj_factor
WHERE instrument_code = '600519.SH'
ORDER BY ts DESC
LIMIT 5;
