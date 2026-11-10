#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
A 股复权因子补洞（阶段 0 · 0.4 收尾）。

背景（实测发现的真实数据缺口，不是脚本 bug）：
  主回填脚本按**交易日**拉 `adj_factor?trade_date=`，Tushare 对历史日期返回的是
  **当时的代码**；而 `bar` 是从 `stock_daily_data` 搬来的，那份数据是按**标的**拉
  `daily?ts_code=`，返回的是**现行代码**下的全历史。
  两者在「北交所 920 号段改号」上分叉：
    2020–2022 年 11 个 920xxx.BJ 标的（如 920751.BJ）在 bar 里有 711 行，
    而按日期拉的因子表里同一天只有老代码（837751.BJ 之类），于是对不上。

本脚本按**标的**拉 `adj_factor?ts_code=`（返回现行代码下的全历史），
只处理「bar 里有行、但因子对不上」的标的，属于精准补洞，不做全市场重拉。

用法：
  python scripts/backfill_adj_factor_by_code.py            # 自动找出缺口标的并补齐
  python scripts/backfill_adj_factor_by_code.py --dry-run  # 只打印缺口，不写库

环境变量：TUSHARE_TOKEN；DB_HOST/DB_PORT/DB_USER/DB_PASSWORD 可选。
"""

import argparse
import datetime
import decimal
import json
import os
import sys
import time
import urllib.request

import pymysql

API_URL = "https://api.tushare.pro"
BATCH = 1000
MAX_REQUESTS_PER_MINUTE = 380

# bar 有行但因子缺失的标的（按交易日回填无法覆盖的那种）
GAP_SQL = """
SELECT b.instrument_code, COUNT(*) AS missing
FROM data_db.bar b
WHERE b.period = '1d'
  AND NOT EXISTS (
    SELECT 1 FROM data_db.adj_factor f
    WHERE f.instrument_code = b.instrument_code AND f.ts = b.ts
  )
GROUP BY b.instrument_code
ORDER BY missing DESC
"""


def connect():
    return pymysql.connect(
        host=os.environ.get("DB_HOST", "127.0.0.1"),
        port=int(os.environ.get("DB_PORT", "3306")),
        user=os.environ.get("DB_USER", "root"),
        password=os.environ.get("DB_PASSWORD", "123456"),
        database="data_db",
        charset="utf8mb4",
        autocommit=False,
    )


def ts_of(yyyymmdd):
    """yyyyMMdd -> 当日 01:30 UTC 的 epoch 秒（与 bar 同口径，不经过本地时区）。"""
    d = datetime.date(int(yyyymmdd[0:4]), int(yyyymmdd[4:6]), int(yyyymmdd[6:8]))
    epoch = datetime.datetime(d.year, d.month, d.day, tzinfo=datetime.timezone.utc)
    return int(epoch.timestamp()) + 5400


def call_tushare(token, ts_code, retries=5):
    payload = json.dumps({
        "api_name": "adj_factor",
        "token": token,
        "params": {"ts_code": ts_code},
        "fields": "ts_code,trade_date,adj_factor",
    }).encode("utf-8")
    last = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(
                API_URL, data=payload, headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(req, timeout=60) as resp:
                body = json.loads(resp.read().decode("utf-8"),
                                  parse_float=decimal.Decimal)
            if body.get("code") == 0:
                data = body.get("data") or {}
                return data.get("items") or []
            msg = str(body.get("msg") or "")
            last = msg
            if "每分钟" in msg or "频率" in msg or "timeout" in msg.lower():
                time.sleep(5 * (attempt + 1))
                continue
            raise RuntimeError(f"Tushare 返回失败 {ts_code}: {msg}")
        except Exception as e:  # noqa: BLE001
            last = str(e)
            time.sleep(2 * (attempt + 1))
    raise RuntimeError(f"Tushare 重试耗尽 {ts_code}: {last}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true", help="只打印缺口标的")
    args = parser.parse_args()

    token = os.environ.get("TUSHARE_TOKEN", "").strip()
    if not token:
        sys.exit("缺少环境变量 TUSHARE_TOKEN")

    conn = connect()
    try:
        with conn.cursor() as cur:
            cur.execute(GAP_SQL)
            gaps = cur.fetchall()

        print(f"缺口标的 {len(gaps)} 个，缺 {sum(n for _, n in gaps)} 行", flush=True)
        for code, n in gaps:
            print(f"  {code}\t{n}", flush=True)
        if args.dry_run or not gaps:
            return

        insert_sql = (
            "INSERT INTO adj_factor (instrument_code, ts, factor) VALUES (%s,%s,%s) "
            "ON DUPLICATE KEY UPDATE factor = VALUES(factor)"
        )

        started = time.time()
        total = 0
        for i, (code, _n) in enumerate(gaps, 1):
            items = call_tushare(token, code)
            rows = []
            for item in items:
                _code, trade_date, factor = item[0], item[1], item[2]
                if factor is None or not trade_date:
                    continue
                rows.append((code, ts_of(trade_date), str(factor)))

            with conn.cursor() as cur:
                for k in range(0, len(rows), BATCH):
                    cur.executemany(insert_sql, rows[k:k + BATCH])
                conn.commit()

            total += len(rows)
            print(f"[{i}/{len(gaps)}] {code} 拉取 {len(rows)} 行，累计 {total}", flush=True)
            # 单标的限速：Tushare 500 次/分钟，留余量
            time.sleep(60 / MAX_REQUESTS_PER_MINUTE)

        print(f"完成：{len(gaps)} 个标的，写入 {total} 行，"
              f"耗时 {time.time() - started:.0f}s", flush=True)
    finally:
        conn.close()


if __name__ == "__main__":
    main()
