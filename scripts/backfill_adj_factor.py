#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
A 股复权因子回填（阶段 0 · 0.4）。

数据源：Tushare `adj_factor`，按交易日拉全市场（与 docs/multi-market-stage0.md 一致）。
落库：data_db.adj_factor(instrument_code, ts, factor)，ts 与 1d bar 同口径
     —— 交易日 01:30 UTC（= 09:30 CST），由 UTC 直接算出，不经过本地时区。

可重入 / 断点续跑：
  每个交易日的期望行数 = 该日 stock_daily_data 的行数；
  已存在的行数 >= 期望值即视为完成并跳过，因此中断后重跑只会补齐缺口。

用法：
  python scripts/backfill_adj_factor.py            # 全量补齐
  python scripts/backfill_adj_factor.py --limit 30 # 只跑最早 30 个待补交易日（试跑）

环境变量：TUSHARE_TOKEN；DB_HOST/DB_PORT/DB_USER/DB_PASSWORD 可选。
"""

import argparse
import decimal
import json
import os
import sys
import time
import urllib.request

import pymysql

API_URL = "https://api.tushare.pro"
BATCH = 1000
# Tushare 基础积分 500 次/分钟，留余量
MAX_REQUESTS_PER_MINUTE = 380


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


def ts_of(trade_date):
    """yyyyMMdd -> 当日 01:30 UTC 的 epoch 秒（不依赖本地时区）。"""
    import datetime

    d = datetime.date(int(trade_date[0:4]), int(trade_date[4:6]), int(trade_date[6:8]))
    epoch = datetime.datetime(d.year, d.month, d.day, tzinfo=datetime.timezone.utc)
    return int(epoch.timestamp()) + 5400  # 01:30 UTC


def call_tushare(token, trade_date, retries=5):
    payload = json.dumps({
        "api_name": "adj_factor",
        "token": token,
        "params": {"trade_date": trade_date},
        "fields": "ts_code,trade_date,adj_factor",
    }).encode("utf-8")
    last = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(
                API_URL, data=payload, headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(req, timeout=30) as resp:
                body = json.loads(resp.read().decode("utf-8"),
                                  parse_float=decimal.Decimal)
            if body.get("code") == 0:
                data = body.get("data") or {}
                return data.get("items") or []
            msg = str(body.get("msg") or "")
            last = msg
            # 频率限制 / 网络抖动：退避后重试
            if "每分钟" in msg or "频率" in msg or "timeout" in msg.lower():
                time.sleep(5 * (attempt + 1))
                continue
            raise RuntimeError(f"Tushare 返回失败 {trade_date}: {msg}")
        except Exception as e:  # noqa: BLE001 - 网络异常统一退避
            last = str(e)
            time.sleep(2 * (attempt + 1))
    raise RuntimeError(f"Tushare 重试耗尽 {trade_date}: {last}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--limit", type=int, default=0, help="只处理前 N 个待补交易日")
    args = parser.parse_args()

    token = os.environ.get("TUSHARE_TOKEN", "").strip()
    if not token:
        sys.exit("缺少环境变量 TUSHARE_TOKEN")

    conn = connect()
    try:
        with conn.cursor() as cur:
            # 源表每个交易日的期望行数
            cur.execute("SELECT trade_date, COUNT(*) FROM stock_daily_data GROUP BY trade_date")
            expected = {str(d): c for d, c in cur.fetchall()}

            # 已入库的因子行数
            cur.execute("SELECT ts, COUNT(*) FROM adj_factor GROUP BY ts")
            have = {int(ts): c for ts, c in cur.fetchall()}

        todo = []
        for d in sorted(expected):
            ts = ts_of(d)
            if have.get(ts, 0) < expected[d]:
                todo.append(d)

        print(f"交易日总数 {len(expected)}，待补 {len(todo)}", flush=True)
        if args.limit:
            todo = todo[: args.limit]
            print(f"本次只处理前 {len(todo)} 个交易日", flush=True)

        insert_sql = (
            "INSERT INTO adj_factor (instrument_code, ts, factor) VALUES (%s,%s,%s) "
            "ON DUPLICATE KEY UPDATE factor = VALUES(factor)"
        )

        started = time.time()
        done = 0
        rows_total = 0
        for trade_date in todo:
            items = call_tushare(token, trade_date)
            ts = ts_of(trade_date)
            rows = []
            for item in items:
                code, _d, factor = item[0], item[1], item[2]
                if factor is None:
                    continue
                rows.append((code, ts, str(factor)))

            with conn.cursor() as cur:
                for i in range(0, len(rows), BATCH):
                    cur.executemany(insert_sql, rows[i:i + BATCH])
                conn.commit()

            done += 1
            rows_total += len(rows)
            elapsed = time.time() - started
            rate = done / elapsed * 60 if elapsed > 0 else 0
            print(f"[{done}/{len(todo)}] {trade_date} 写入 {len(rows)} 行，"
                  f"累计 {rows_total}，{rate:.0f} 日/分", flush=True)

            # 限速：保持每分钟不超过 MAX_REQUESTS_PER_MINUTE
            if rate > MAX_REQUESTS_PER_MINUTE:
                time.sleep(60 / MAX_REQUESTS_PER_MINUTE)

        print(f"完成：{done} 个交易日，写入 {rows_total} 行，耗时 {time.time() - started:.0f}s",
              flush=True)
    finally:
        conn.close()


if __name__ == "__main__":
    main()
