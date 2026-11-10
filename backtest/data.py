"""行情取数：从 data-service 的 GET /market/bars 拉取并解析为 Bar 列表。

纯标准库（urllib）。回测进程内网直连 data-service（无认证，仅限内网），
与前端图表走同一份数据、同一个 adjust 口径（ADR-0007 / multi-market-rollout 0.6）。
"""
from __future__ import annotations

import json
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from typing import List, Optional

from .types import Bar

_TIMEOUT_SECONDS = 30


def fetch_bars(base_url: str, symbol: str, period: str,
               frm: Optional[str] = None, to: Optional[str] = None,
               limit: Optional[int] = None, adjust: str = "none") -> List[Bar]:
    """GET /market/bars 并把返回的 bars 解析成 Bar 列表（升序）。"""
    params = {"symbol": symbol, "period": period, "adjust": adjust}
    if frm:
        params["from"] = frm
    if to:
        params["to"] = to
    if limit:
        params["limit"] = str(limit)

    url = base_url.rstrip("/") + "/market/bars?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=_TIMEOUT_SECONDS) as resp:
        payload = json.loads(resp.read().decode("utf-8"))

    if payload.get("code") != 200:
        raise RuntimeError("行情接口返回错误: %s" % payload.get("message"))

    items = payload["data"]["bars"]
    bars = [_to_bar(it) for it in items]
    return bars


def _to_bar(item: dict) -> Bar:
    """把 /market/bars 的 bar 项转成 Bar。time 为日期字符串（日线）或 Unix 秒（分钟级）。"""
    ts = _to_ts(item["time"])
    return Bar(ts=ts, open=float(item["open"]), high=float(item["high"]),
               low=float(item["low"]), close=float(item["close"]),
               volume=float(item.get("volume") or 0.0))


def _to_ts(time) -> int:
    if isinstance(time, int):
        return time
    s = str(time)
    # "yyyy-MM-dd" → UTC 零点 epoch 秒
    dt = datetime.strptime(s, "%Y-%m-%d").replace(tzinfo=timezone.utc)
    return int(dt.timestamp())
