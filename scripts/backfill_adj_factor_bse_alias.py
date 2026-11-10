#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
A 股复权因子补洞 · 北交所新旧代码别名（阶段 0 · 0.4 收尾）。

问题（实测，不是脚本 bug）：
  主回填按**交易日**拉 `adj_factor?trade_date=`，Tushare 对历史日期返回**当时的代码**；
  而 `bar` 来自 `stock_daily_data`，那份数据是按**标的**拉 `daily?ts_code=`，
  返回的是**现行代码**下的全历史。两者在「北交所 920 号段改号」上分叉：
    920751.BJ（惠同新材）在 bar 里有 2020-07-02 起的 1207 行，
    但 adj_factor 里 920751.BJ 只有 2023-07-17 起（= 新代码 list_date）的 801 行，
    2020–2022 那段挂在旧代码 833751.BJ 下。
  结果：11 个标的、711 行 bar 查不到因子，`adjust=forward` 在这段退化为原价。

修法：
  取 Tushare `bse_mapping`（北交所新旧代码对照）得到 o_code → n_code，
  按 o_code 拉缺口的日期区间，**以 n_code 落库**（instrument_code 与 bar 对齐）。
  这样 bar 侧不用动、接口侧不用改，缺口就地闭合。

用法：
  python scripts/backfill_adj_factor_bse_alias.py --dry-run   # 只打印缺口与映射
  python scripts/backfill_adj_factor_bse_alias.py             # 执行补洞

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

# 缺口：bar 有行但同 (instrument_code, ts) 没有因子
GAP_SQL = """
SELECT b.instrument_code, MIN(b.ts) AS lo, MAX(b.ts) AS hi, COUNT(*) AS missing
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
    """yyyyMMdd -> 当日 01:30 UTC 的 epoch 秒（与 bar 同口径）。"""
    d = datetime.date(int(yyyymmdd[0:4]), int(yyyymmdd[4:6]), int(yyyymmdd[6:8]))
    epoch = datetime.datetime(d.year, d.month, d.day, tzinfo=datetime.timezone.utc)
    return int(epoch.timestamp()) + 5400


def date_of(ts):
    """epoch 秒 -> 当日 yyyyMMdd（先按 UTC 还原到 01:30，再取日期）。"""
    d = datetime.datetime.fromtimestamp(ts, tz=datetime.timezone.utc).date()
    return d.strftime("%Y%m%d")


def call_tushare(token, api_name, params, fields, retries=5):
    payload = json.dumps({
        "api_name": api_name, "token": token,
        "params": params, "fields": fields,
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
                cols = data.get("fields") or []
                return [dict(zip(cols, item)) for item in (data.get("items") or [])]
            msg = str(body.get("msg") or "")
            last = msg
            if "每分钟" in msg or "频率" in msg or "timeout" in msg.lower():
                time.sleep(5 * (attempt + 1))
                continue
            raise RuntimeError(f"Tushare 返回失败 {api_name}: {msg}")
        except Exception as e:  # noqa: BLE001
            last = str(e)
            time.sleep(2 * (attempt + 1))
    raise RuntimeError(f"Tushare 重试耗尽 {api_name}: {last}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true", help="只打印缺口与映射")
    args = parser.parse_args()

    token = os.environ.get("TUSHARE_TOKEN", "").strip()
    if not token:
        sys.exit("缺少环境变量 TUSHARE_TOKEN")

    conn = connect()
    try:
        with conn.cursor() as cur:
            cur.execute(GAP_SQL)
            gaps = cur.fetchall()
        if not gaps:
            print("无缺口，退出", flush=True)
            return

        print(f"缺口标的 {len(gaps)} 个，共 {sum(g[3] for g in gaps)} 行", flush=True)

        # 北交所新旧代码对照：o_code -> n_code
        mapping_rows = call_tushare(token, "bse_mapping", {}, "name,o_code,n_code,list_date")
        o2n = {r["o_code"]: r["n_code"] for r in mapping_rows}
        n2o = {r["n_code"]: r["o_code"] for r in mapping_rows}
        name_of = {r["n_code"]: r["name"] for r in mapping_rows}

        plan = []
        for code, lo, hi, missing in gaps:
            old = n2o.get(code)
            if old is None:
                print(f"  !! {code} 不在 bse_mapping 中，跳过（{missing} 行）", flush=True)
                continue
            plan.append((code, old, name_of.get(code, ""), date_of(lo), date_of(hi), missing))
            print(f"  {code}\t<- {old}\t{name_of.get(code, '')}\t"
                  f"{date_of(lo)}~{date_of(hi)}\t{missing} 行", flush=True)

        if args.dry_run:
            return

        insert_sql = (
            "INSERT INTO adj_factor (instrument_code, ts, factor) VALUES (%s,%s,%s) "
            "ON DUPLICATE KEY UPDATE factor = VALUES(factor)"
        )

        started = time.time()
        total = 0
        for i, (new_code, old_code, name, start, end, _missing) in enumerate(plan, 1):
            rows_api = call_tushare(
                token, "adj_factor",
                {"ts_code": old_code, "start_date": start, "end_date": end},
                "ts_code,trade_date,adj_factor")
            rows = [(new_code, ts_of(r["trade_date"]), str(r["adj_factor"]))
                    for r in rows_api if r.get("adj_factor") is not None]
            with conn.cursor() as cur:
                for k in range(0, len(rows), BATCH):
                    cur.executemany(insert_sql, rows[k:k + BATCH])
                conn.commit()
            total += len(rows)
            print(f"[{i}/{len(plan)}] {new_code} <- {old_code} {name} "
                  f"写入 {len(rows)} 行，累计 {total}", flush=True)
            time.sleep(0.3)

        print(f"完成：{len(plan)} 个标的，写入 {total} 行，"
              f"耗时 {time.time() - started:.0f}s", flush=True)
    finally:
        conn.close()


if __name__ == "__main__":
    main()
