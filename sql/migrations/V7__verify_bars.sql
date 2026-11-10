-- ============================================
-- V7 校验：bar 迁移正确性 V1–V8
-- 归属服务：data-service
-- 执行手册：docs/multi-market-stage0.md §9
--
-- 手工执行：
--   mysql -uroot -p data_db < sql/migrations/V7__verify_bars.sql
--   （副本库演练：把 USE 目标换成 data_db_verify）
--
-- 全部为只读查询，可反复执行（观察期两周后的全量复核也用这一份）。
-- 每条末尾带 PASS/FAIL：V1–V7 必须 PASS，V8 供人工核对。
--
-- 写法说明：每个指标只计算一次（外层 SELECT 复用派生表的值）。
-- 早期版本把同一个聚合在「显示列」和「PASS/FAIL 列」各算一遍，
-- 7.67M 行的 NOT EXISTS 因此跑了两趟，单次校验要 3 分钟以上。
--
-- 【多市场作用域 · 实测 2026-11-07】
--   OKX 接入后 bar 里同时有 A 股与加密行，加密的 ts 落在 00:00 UTC、
--   amount 是 USDT、pre_close 恒为 NULL，且源表 stock_daily_data 里没有对应行。
--   所以 V1/V2/V3/V6/V7 这些「全表聚合」必须限定为 A 股，否则会集体误报
--   （实测 V1 差 897 行、V7 从 32 变 929）。统一谓词：
--     instrument_code REGEXP '^[0-9]{6}\\.(SH|SZ|BJ)$'
--   V0 是「都有 instrument」的正向覆盖，V2b/V4/V5 本身就 JOIN 源表，
--   V8 指定了具体标的，这四条天然不受影响，保持全表/原样即可。
-- ============================================

USE `data_db`;

-- 客户端字符集可能是 gbk（Windows 控制台默认），而列是 utf8mb4，
-- V8 里含中文常量，不统一会报 Illegal mix of collations。
SET NAMES utf8mb4;

-- 与迁移脚本同一口径：让 FROM_UNIXTIME / UNIX_TIMESTAMP 按 UTC 解释
SET SESSION time_zone = '+00:00';

-- ---------- [V0] 标的覆盖：bar 里每个标的都必须在 instrument 里（附赠，非手册项） ----------
SELECT 'V0' AS chk, 'bar 标的全在 instrument' AS item, bad,
       IF(bad = 0, 'PASS', 'FAIL') AS result
FROM (
  SELECT COUNT(DISTINCT instrument_code) AS bad
  FROM bar WHERE instrument_code NOT IN (SELECT code FROM instrument)
) t;

-- ---------- [V1] 行数一致 ----------
SELECT 'V1' AS chk, '行数与源表一致' AS item, src, dst,
       IF(src = dst, 'PASS', 'FAIL') AS result
FROM (
  SELECT (SELECT COUNT(*) FROM stock_daily_data) AS src,
         (SELECT COUNT(*) FROM bar WHERE period='1d'
           AND instrument_code REGEXP '^[0-9]{6}\\.(SH|SZ|BJ)$') AS dst
) t;

-- ---------- [V2] 成交额全表对账：反算回千元 4 位后必须逐行相等 ----------
-- 【口径修正 · 实测 2026-11-05】
--   不能写 `SUM(bar.amount) = SUM(src.amount) × 1000`：
--   `bar` 存的是 Tushare 原始精度（千元 5 位小数 ×1000，单位元），
--   而旧表 `amount` 是 DECIMAL(20,4)，把千元截到 4 位（颗粒度 0.1 元）。
--   迁移行是 SQL 直接 ×1000，能精确相等；但**每日同步行**走 Java，
--   保留了更高精度，于是 SUM 会差几十元（实测 11-05 差 10.47 元 / 5,571 行）。
--   这是 bar 更精确，不是换算错了；为凑等式去砍精度是本末倒置。
--   正确的不变量：bar.amount 反算回千元并保留 4 位，必须与旧表逐行相等。
SELECT 'V2' AS chk, '成交额对账：SUM(ROUND(bar/1000,4)) == SUM(src)' AS item,
       src_qianyuan, bar_back_qianyuan,
       IF(src_qianyuan = bar_back_qianyuan, 'PASS', 'FAIL') AS result
FROM (
  SELECT (SELECT ROUND(SUM(amount),4)                    FROM stock_daily_data) AS src_qianyuan,
         (SELECT ROUND(SUM(ROUND(amount / 1000, 4)),4) FROM bar WHERE period='1d'
           AND instrument_code REGEXP '^[0-9]{6}\\.(SH|SZ|BJ)$') AS bar_back_qianyuan
) t;

-- ---------- [V2b] 逐行抽检：20 只全历史的 amount / volume 反算必须逐行相等 ----------
-- 聚合相等可能掩盖「一半偏大一半偏小」，这条用逐行比对兜住。
SELECT 'V2b' AS chk, '成交额+成交量逐行抽检(20只全历史)' AS item, mismatch_rows,
       IF(mismatch_rows = 0, 'PASS', 'FAIL') AS result
FROM (
  SELECT COUNT(*) AS mismatch_rows
  FROM bar b
  JOIN stock_daily_data s
    ON s.stock_code = b.instrument_code
   AND b.ts = UNIX_TIMESTAMP(STR_TO_DATE(CONCAT(s.trade_date, ' 093000'), '%Y%m%d %H%i%s')) - 28800
  JOIN (SELECT stock_code FROM stock_info
        ORDER BY MD5(CONCAT(stock_code, 'fuli-v7-sample')) LIMIT 20) sm
    ON sm.stock_code = s.stock_code
  WHERE b.period = '1d'
    AND (ROUND(b.amount / 1000, 4) <> s.amount
      OR b.volume <> s.vol * 100)
) t;

-- ---------- [V3] 成交量全表对账：SUM(volume) == SUM(src.vol) × 100 ----------
SELECT 'V3' AS chk, '成交量 SUM 精确 ×100' AS item, src_x100, dst,
       IF(src_x100 = dst, 'PASS', 'FAIL') AS result
FROM (
  SELECT (SELECT ROUND(SUM(vol),4)*100 FROM stock_daily_data) AS src_x100,
         (SELECT ROUND(SUM(volume),4) FROM bar WHERE period='1d'
           AND instrument_code REGEXP '^[0-9]{6}\\.(SH|SZ|BJ)$') AS dst
) t;

-- ---------- [V4] OHLC + pre_close 逐行比对（确定性抽取 20 只，全历史） ----------
-- 用 MD5(代码+固定盐) 排序取 20 只：可复现、不依赖手工维护名单。
-- 注意：MySQL 8.4 不支持 IN (子查询带 LIMIT)，必须写成派生表 JOIN。
SELECT 'V4' AS chk, 'OHLC+pre_close 逐行比对(20只全历史)' AS item, mismatch,
       IF(mismatch = 0, 'PASS', 'FAIL') AS result
FROM (
  SELECT COUNT(*) AS mismatch
  FROM (
    SELECT s.stock_code AS c,
           UNIX_TIMESTAMP(STR_TO_DATE(CONCAT(s.trade_date,' 093000'), '%Y%m%d %H%i%s')) - 28800 AS ts,
           s.open_price AS o, s.high_price AS h, s.low_price AS l,
           s.close_price AS cl, s.pre_close AS pc
    FROM stock_daily_data s
    JOIN (SELECT stock_code FROM stock_info
          ORDER BY MD5(CONCAT(stock_code,'fuli-v7-sample')) LIMIT 20) sm
      ON sm.stock_code = s.stock_code
  ) src
  JOIN bar b ON b.instrument_code = src.c AND b.period='1d' AND b.ts = src.ts
  WHERE NOT (b.open_price  <=> src.o  AND b.high_price <=> src.h
         AND b.low_price   <=> src.l  AND b.close_price <=> src.cl
         AND b.pre_close   <=> src.pc)
) t;

-- ---------- [V5] 缺行检测：源表每行都要在 bar 里找到同 ts 的对端 ----------
-- 必须 FORCE INDEX(uk_code_trade_date)：让源表按 (stock_code, trade_date) 顺序驱动，
-- 对 bar 的主键探测就变成「同一标的内连续」，整表约 40 秒。
-- 若让优化器按 idx_trade_date 驱动（按日期取全市场），730 万次探测会在 5,596 个标的之间
-- 来回跳，实测 7 分钟仍未结束 —— 结果一样，只是慢到无法在切换窗口内接受。
SELECT 'V5' AS chk, '源表每行都有对应 bar' AS item, missing,
       IF(missing = 0, 'PASS', 'FAIL') AS result
FROM (
  SELECT COUNT(*) AS missing
  FROM stock_daily_data s FORCE INDEX (uk_code_trade_date)
  LEFT JOIN bar b
    ON b.instrument_code = s.stock_code AND b.period='1d'
   AND b.ts = UNIX_TIMESTAMP(STR_TO_DATE(CONCAT(s.trade_date,' 093000'), '%Y%m%d %H%i%s')) - 28800
  WHERE b.instrument_code IS NULL
) t;

-- ---------- [V6] 时间范围：首根 2005-07-13 01:30 UTC，末根 = 最新交易日 01:30 UTC ----------
SELECT 'V6' AS chk, '时间轴范围' AS item, first_bar_utc, last_bar_utc,
       IF(first_bar_utc = '2005-07-13 01:30' AND last_bar_utc LIKE '% 01:30', 'PASS', 'FAIL') AS result
FROM (
  SELECT FROM_UNIXTIME(MIN(ts), '%Y-%m-%d %H:%i') AS first_bar_utc,
         FROM_UNIXTIME(MAX(ts), '%Y-%m-%d %H:%i') AS last_bar_utc
  FROM bar WHERE period='1d'
    AND instrument_code REGEXP '^[0-9]{6}\\.(SH|SZ|BJ)$'
) t;

-- ---------- [V7] NULL 率一致：pre_close 源 32 行 NULL，迁移后同样 32 行 ----------
SELECT 'V7' AS chk, 'pre_close NULL 行数一致' AS item, src_null, dst_null,
       IF(src_null = dst_null, 'PASS', 'FAIL') AS result
FROM (
  SELECT (SELECT COUNT(*) FROM stock_daily_data WHERE pre_close IS NULL) AS src_null,
         (SELECT COUNT(*) FROM bar WHERE period='1d' AND pre_close IS NULL
           AND instrument_code REGEXP '^[0-9]{6}\\.(SH|SZ|BJ)$') AS dst_null
) t;

-- ---------- [V8] 抽样单根比价（人工核对） ----------
SELECT 'V8' AS chk, CONCAT('抽样 ', instrument_code, ' 最近 5 根') AS item,
       FROM_UNIXTIME(ts, '%Y-%m-%d') AS d,
       CONCAT(open_price, ' / ', close_price, ' / pc=', IFNULL(pre_close,'NULL')) AS ohlc,
       'MANUAL' AS result
FROM bar
WHERE period='1d' AND instrument_code IN ('600519.SH','000001.SZ','920000.BJ')
ORDER BY instrument_code, ts DESC
LIMIT 5;
