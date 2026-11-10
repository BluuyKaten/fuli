#!/usr/bin/env python3
"""实时信号 runner：把回测策略的信号桥接成建议单（ADR-0011 决策 4）。

回测与实盘共用同一份 `Strategy` 代码——同一份 `on_bar`，既能在历史 bars 上跑出净值，
也能在实时最新 bar 上产出信号。本模块只做搬运：信号 → `okx_order_suggestion`。
**不碰下单**：建议单写完后仍要人工在 `/data/okx/suggestions/{id}/execute` 确认，
「Agent 只读」这条边界不破（ADR-0010 决策 4）。

三个正确性要点，都不是可选项：

1. **只看已完结的 bar。** 未收盘那根的 close 随价格跳动，拿它决策等于偷看未来——
   同一时刻重跑会得到不同信号，回测结论也就不可复现。默认丢掉最后一根未完结 bar
   （`--keep-unclosed` 可关，但只应该在看盘中预览时用）。
2. **同一根 bar 只决策一次。** state 文件记 `last_bar_ts`，轮询重跑不会重复拉号。
3. **连续同向不重复写。** state 记 `last_side`，避免趋势策略每根 bar 刷一条建议单
   （`--allow-repeat` 可关掉这层抑制，用于分批建仓类策略）。

本地台账（`state.position` / `state.cash`）只服务策略上下文，**不是账户真实余额**：
策略需要 `ctx.position` 才知道「有没有仓可卖」。人工拒单或手动加减仓之后，
用一次 `--position` / `--cash` 把台账校准回真实值。

用法：
  python -m backtest.live_signal --strategy sma_cross --symbol BTC-USDT.CRYPTO \\
      --period 1h --limit 200 --token "$FULI_TOKEN"

  # 只看会发什么，不提交、不落 state
  python -m backtest.live_signal --dry-run
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable, Dict, List, Optional

from backtest.data import fetch_bars
from backtest.examples.sma_cross import SmaCrossStrategy
from backtest.strategy import Strategy
from backtest.types import Bar, Context, Order

_TIMEOUT_SECONDS = 30

_SUGGEST_PATH = "/ai/suggest-order"

# 与 data.py 同一口径：内网直连，不走系统代理（http_proxy 会把 127.0.0.1 也代理掉）
_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))

# 与 MarketBarsService.PERIODS 对齐；"1mo" 不列，因为月长不固定，无法判断是否已完结
_PERIOD_SECONDS = {
    "1m": 60, "5m": 300, "15m": 900, "30m": 1800,
    "1h": 3600, "4h": 14400, "1d": 86400, "1w": 604800,
}

_SIDES = ("buy", "sell")

_STATUS_TEXT = {
    "submitted": "已写入建议单",
    "dry_run": "演练模式：未提交",
    "no_signal": "无信号",
    "duplicate_bar": "该 bar 已处理过",
    "duplicate_side": "与上一条建议同向，跳过",
    "no_bars": "没有取到行情",
    "no_closed_bar": "窗口内没有已完结的 bar",
}


@dataclass
class SignalState:
    """跨进程的轻薄记忆。除 last_* 外都是策略视角的本地台账，不是账户余额。"""

    last_bar_ts: Optional[int] = None
    last_side: Optional[str] = None
    position: Optional[float] = None
    cash: Optional[float] = None
    last_suggestion_id: Optional[str] = None


@dataclass
class LiveSignalConfig:
    """一次信号检查的全部参数。与 CLI 一一对应，便于单测直接构造。"""

    symbol: str = "BTC-USDT.CRYPTO"
    period: str = "1h"
    data_base_url: str = "http://localhost:8084"
    ai_base_url: str = "http://localhost:8085"
    token: str = ""
    limit: int = 200
    adjust: str = "none"
    # None 表示「沿用 state 台账」，首次运行各自回落到 10000 / 0
    cash: Optional[float] = None
    position: Optional[float] = None
    ord_type: str = "market"
    state_path: Optional[str] = None
    dry_run: bool = False
    ignore_state: bool = False
    keep_unclosed: bool = False
    allow_repeat: bool = False


@dataclass
class SignalOutcome:
    """一次检查的结果。status 只用于日志与断言，不参与业务判断。"""

    status: str
    message: str = ""
    bar: Optional[Bar] = None
    order: Optional[Order] = None
    payload: Optional[Dict[str, object]] = None
    response: Optional[Dict[str, object]] = None


# ---------------------------------------------------------------- state 读写

def load_state(path: Optional[str]) -> SignalState:
    """读 state。文件不存在 = 首次运行；存在但损坏则抛错（静默返回空态会重复写建议单）。"""
    if not path:
        return SignalState()
    p = Path(path)
    if not p.exists():
        return SignalState()
    raw = json.loads(p.read_text(encoding="utf-8"))
    return SignalState(
        last_bar_ts=_opt_int(raw.get("last_bar_ts")),
        last_side=raw.get("last_side"),
        position=_opt_float(raw.get("position")),
        cash=_opt_float(raw.get("cash")),
        last_suggestion_id=raw.get("last_suggestion_id"),
    )


def save_state(path: Optional[str], state: SignalState) -> None:
    """原子写（临时文件 + replace），避免轮询进程读到半截 JSON。"""
    if not path:
        return
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    tmp = p.with_name(p.name + ".tmp")
    tmp.write_text(json.dumps(asdict(state), ensure_ascii=False, indent=2), encoding="utf-8")
    tmp.replace(p)


def default_state_path(strategy_name: str, symbol: str, period: str) -> str:
    safe = "%s-%s-%s.json" % (strategy_name, symbol, period)
    return str(Path(__file__).resolve().parent / ".state" / safe.replace("/", "_"))


# ---------------------------------------------------------------- 小工具

def to_inst_id(symbol: str) -> str:
    """`BTC-USDT.CRYPTO` → `BTC-USDT`；裸 `BTC-USDT` 原样返回（幂等）。

    与 OkxSymbols.toInstId 同一口径：不认识的market 后缀直接拒绝，
    免得把 A 股符号（600519.SH）误发成 OKX 标的。
    """
    s = (symbol or "").strip()
    if not s:
        raise ValueError("symbol 不能为空")
    if s.endswith(".CRYPTO"):
        s = s[: -len(".CRYPTO")]
    elif "." in s:
        raise ValueError("不是加密标的：%s（市场后缀应为 .CRYPTO）" % symbol)
    if "-" not in s:
        raise ValueError("不是 OKX instId：%s（应形如 BTC-USDT）" % s)
    return s


def period_seconds(period: str) -> Optional[int]:
    return _PERIOD_SECONDS.get((period or "").strip().lower())


def drop_unclosed(bars: List[Bar], period: str, now: Optional[float] = None) -> List[Bar]:
    """丢掉最后一根未完结的 bar。周期不认识时不动（宁可放过也不错杀）。"""
    step = period_seconds(period)
    if not bars or step is None:
        return list(bars)
    moment = time.time() if now is None else now
    if bars[-1].ts + step > moment:
        return list(bars[:-1])
    return list(bars)


def _fmt_size(size: float) -> str:
    """转成十进制字符串给 BigDecimal，避免 `1e-05` 这类科学计数法。

    固定 8 位小数：OKX 各标的 lotSz 不同（BTC 是 1e-8，部分币是 0.01 甚至 1），
    超出 lotSz 精度的 sz 会被 OKX 拒单。对齐 lotSz 需要 instruments 元数据，
    当前先按最细粒度格式化——建议单只是记录，真正的下单仍由人工确认。
    """
    text = format(float(size), ".12f").rstrip("0").rstrip(".")
    if text in ("", "0", "-0"):
        raise ValueError("下单数量过小：%r" % size)
    return text


def _fmt_price(price: float) -> str:
    return format(float(price), ".8f").rstrip("0").rstrip(".")


def _fmt_ts(ts: int) -> str:
    return datetime.fromtimestamp(int(ts), tz=timezone.utc).strftime("%Y-%m-%dT%H:%MZ")


def _opt_int(value) -> Optional[int]:
    return None if value is None else int(value)


def _opt_float(value) -> Optional[float]:
    return None if value is None else float(value)


def strategy_label(strategy: Strategy) -> str:
    """策略自述优先（`describe()`），否则退回类名。"""
    describe = getattr(strategy, "describe", None)
    if callable(describe):
        return str(describe())
    return type(strategy).__name__


# ---------------------------------------------------------------- 建议单

def build_suggestion_payload(order: Order, config: LiveSignalConfig,
                             bar: Bar, label: str) -> Dict[str, object]:
    """把 `Order(side, size)` 翻译成 `/ai/suggest-order` 的 body。

    策略只给 side + size，没有价格，所以默认市价；策略若额外带了 `px` 且
    `ord_type=limit`，才切限价（`Order` 目前是 frozen 两字段，扩展时向后兼容）。
    """
    side = (order.side or "").strip().lower()
    if side not in _SIDES:
        raise ValueError("策略给出的 side 非法：%r（只接受 BUY/SELL）" % order.side)
    if order.size is None or order.size <= 0:
        raise ValueError("策略给出的 size 必须 > 0：%r" % order.size)

    ord_type = (config.ord_type or "market").strip().lower()
    if ord_type not in ("limit", "market"):
        raise ValueError("ordType 只能是 limit/market：%r" % config.ord_type)

    px = getattr(order, "px", None)
    if ord_type == "limit" and px is None:
        raise ValueError("limit 建议单需要价格，但策略没给 px")

    payload: Dict[str, object] = {
        "instId": to_inst_id(config.symbol),
        "side": side,
        "ordType": ord_type,
        "sz": _fmt_size(order.size),
        "reason": "%s | %s %s @ %s %s close=%s" % (
            label, side.upper(), _fmt_size(order.size),
            config.period, _fmt_ts(bar.ts), _fmt_price(bar.close)),
    }
    if ord_type == "limit":
        payload["px"] = _fmt_price(px)
    return payload


def submit_suggestion(ai_base_url: str, token: str, payload: Dict[str, object],
                      timeout: int = _TIMEOUT_SECONDS) -> Dict[str, object]:
    """POST /ai/suggest-order（进程持用户 JWT）。

    服务端把非 200 的 Result 也放在 HTTP 200 里返回，所以两边都要查：
    HTTP 层失败看状态码，业务层失败看 `code`。
    """
    if not token:
        raise ValueError("缺少用户令牌：用 --token 或环境变量 FULI_TOKEN 提供")

    url = ai_base_url.rstrip("/") + _SUGGEST_PATH
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(url, data=body, method="POST", headers={
        "Content-Type": "application/json;charset=UTF-8",
        "Accept": "application/json",
        "Authorization": "Bearer " + token,
    })
    try:
        with _OPENER.open(request, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        raise RuntimeError("建议单提交失败（HTTP %d）：%s" % (e.code, _http_error_message(e))) from e
    except urllib.error.URLError as e:
        raise RuntimeError("连不上 ai-service（%s）：%s" % (url, e.reason)) from e

    data = json.loads(raw)
    if data.get("code") != 200:
        raise RuntimeError("建议单被拒（code=%s）：%s" % (data.get("code"), data.get("message")))
    return data


def _http_error_message(error: urllib.error.HTTPError) -> str:
    try:
        body = error.read().decode("utf-8")
        return str(json.loads(body).get("message") or body)[:200]
    except Exception:  # 错误体不是 JSON 时只要状态文本就够了
        return error.reason or "unknown"


def _extract_suggestion_id(response: Dict[str, object]) -> Optional[str]:
    """`data.result` 是 data-service 原始响应的 JSON 字符串，id 在两层里面。"""
    try:
        inner = response["data"]["result"]  # type: ignore[index]
        if isinstance(inner, str):
            inner = json.loads(inner)
        return str(inner["data"]["id"])  # type: ignore[index]
    except (KeyError, TypeError, ValueError):
        return None


def _apply_fill(position: float, cash: float, side: str, size: float, price: float) -> tuple:
    """按信号 bar 收盘价估算台账变动。只服务下一次决策，不追求与实际成交一致。

    size 用的是**实际提交的 sz**（已格式化到 8 位），不是策略原始 size：
    两者差一点尾数，按原始 size 记账会让 cash 慢慢漂成负数。
    """
    notional = size * price
    if str(side).strip().upper() == "BUY":
        return round(position + size, 12), max(0.0, round(cash - notional, 8))
    return max(0.0, round(position - size, 12)), round(cash + notional, 8)


# ---------------------------------------------------------------- 主流程

def run_once(strategy: Strategy, config: LiveSignalConfig, *,
             fetch: Optional[Callable[..., List[Bar]]] = None,
             submit: Optional[Callable[..., Dict[str, object]]] = None) -> SignalOutcome:
    """拉最新 bars → 策略决策 → 去重 → 写建议单。返回结果，不打印。"""
    fetch = fetch or fetch_bars
    submit = submit or submit_suggestion

    bars = fetch(config.data_base_url, config.symbol, config.period,
                 limit=config.limit, adjust=config.adjust)
    if not bars:
        return SignalOutcome(status="no_bars", message=_STATUS_TEXT["no_bars"])

    if not config.keep_unclosed:
        bars = drop_unclosed(bars, config.period)
        if not bars:
            return SignalOutcome(status="no_closed_bar", message=_STATUS_TEXT["no_closed_bar"])

    last = bars[-1]
    # state 总是读：--ignore-state 只放弃「去重」，不放弃台账（否则重跑一次就丢持仓）
    state = load_state(config.state_path)

    if not config.ignore_state and state.last_bar_ts is not None and last.ts <= state.last_bar_ts:
        return SignalOutcome(status="duplicate_bar", bar=last,
                             message="%s：%s（不重复拉号）"
                                     % (_STATUS_TEXT["duplicate_bar"], _fmt_ts(last.ts)))

    cash = config.cash if config.cash is not None else (
        state.cash if state.cash is not None else 10000.0)
    position = config.position if config.position is not None else (
        state.position if state.position is not None else 0.0)

    context = Context(index=len(bars) - 1, bars=tuple(bars), cash=cash,
                      position=position, equity=round(cash + position * last.close, 8))
    order = strategy.on_bar(context)

    # 无论有没有信号，这根 bar 都算处理过——否则轮询会反复用同一根 bar 决策
    if order is None:
        _advance_bar(state, last.ts, config)
        return SignalOutcome(status="no_signal", bar=last, message=_STATUS_TEXT["no_signal"])

    side = (order.side or "").strip().lower()
    if (not config.allow_repeat and not config.ignore_state
            and state.last_side is not None and state.last_side == side):
        _advance_bar(state, last.ts, config)
        return SignalOutcome(status="duplicate_side", bar=last, order=order,
                             message="%s（上一条 %s）"
                                     % (_STATUS_TEXT["duplicate_side"], state.last_side))

    payload = build_suggestion_payload(order, config, last, strategy_label(strategy))

    if config.dry_run:
        return SignalOutcome(status="dry_run", bar=last, order=order, payload=payload,
                             message=_STATUS_TEXT["dry_run"])

    # 提交失败时不推进 state：同一根 bar 下次重试（策略确定性，重算结果一致）
    response = submit(config.ai_base_url, config.token, payload)

    state.last_bar_ts = last.ts
    state.last_side = side
    state.last_suggestion_id = _extract_suggestion_id(response)
    state.position, state.cash = _apply_fill(position, cash, side, float(payload["sz"]), last.close)
    save_state(config.state_path, state)

    return SignalOutcome(status="submitted", bar=last, order=order,
                         payload=payload, response=response,
                         message="%s id=%s" % (_STATUS_TEXT["submitted"],
                                               state.last_suggestion_id or "?"))


def _advance_bar(state: SignalState, bar_ts: int, config: LiveSignalConfig) -> None:
    state.last_bar_ts = bar_ts
    if not config.dry_run:
        save_state(config.state_path, state)


# ---------------------------------------------------------------- CLI

def build_strategy(name: str, args) -> Strategy:
    if name == "sma_cross":
        return SmaCrossStrategy(args.fast, args.slow)
    raise SystemExit("未知策略：%s（当前可用：sma_cross）" % name)


def _parse_args(argv: Optional[List[str]] = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(description="fuli 实时信号 → 建议单")
    p.add_argument("--strategy", default="sma_cross")
    p.add_argument("--symbol", default="BTC-USDT.CRYPTO")
    p.add_argument("--period", default="1h")
    p.add_argument("--limit", type=int, default=200)
    p.add_argument("--adjust", default="none", choices=["none", "forward"])
    p.add_argument("--data-base-url", default="http://localhost:8084")
    p.add_argument("--ai-base-url", default="http://localhost:8085")
    p.add_argument("--token", default=os.environ.get("FULI_TOKEN", ""),
                   help="用户 JWT；建议用环境变量 FULI_TOKEN，避免留在命令历史里")
    p.add_argument("--cash", type=float, default=None, help="策略视角的可用现金（不给则沿用台账）")
    p.add_argument("--position", type=float, default=None, help="策略视角的持仓数量（不给则沿用台账）")
    p.add_argument("--ord-type", default="market", choices=["market", "limit"])
    p.add_argument("--state-file", default=None)
    p.add_argument("--interval", type=int, default=None,
                   help="轮询秒数；不给则只跑一轮就退出")
    p.add_argument("--dry-run", action="store_true", help="只打印 payload，不提交、不写 state")
    p.add_argument("--ignore-state", action="store_true", help="忽略 state（强制重新决策）")
    p.add_argument("--keep-unclosed", action="store_true",
                   help="保留未完结的最后一根 bar（仅在盯盘预览时用）")
    p.add_argument("--allow-repeat", action="store_true", help="允许连续同向重复写建议单")
    p.add_argument("--fast", type=int, default=10)
    p.add_argument("--slow", type=int, default=30)
    return p.parse_args(argv)


def _config_from_args(args) -> LiveSignalConfig:
    state_path = args.state_file or default_state_path(args.strategy, args.symbol, args.period)
    return LiveSignalConfig(
        symbol=args.symbol, period=args.period,
        data_base_url=args.data_base_url, ai_base_url=args.ai_base_url,
        token=args.token, limit=args.limit, adjust=args.adjust,
        cash=args.cash, position=args.position, ord_type=args.ord_type,
        state_path=state_path, dry_run=args.dry_run, ignore_state=args.ignore_state,
        keep_unclosed=args.keep_unclosed, allow_repeat=args.allow_repeat,
    )


def _print_outcome(outcome: SignalOutcome, config: LiveSignalConfig) -> None:
    stamp = datetime.now(timezone.utc).strftime("%H:%M:%SZ")
    bar_text = _fmt_ts(outcome.bar.ts) if outcome.bar else "-"
    print("[%s] %-14s bar=%s %s" % (stamp, outcome.status, bar_text, outcome.message))
    if outcome.payload:
        print("           payload %s" % json.dumps(outcome.payload, ensure_ascii=False))
    if outcome.status == "submitted":
        print("           待人工确认：POST %s/data/okx/suggestions/{id}/execute（clOrdId=sugg-{id}）"
              % config.data_base_url.rstrip("/"))


def main(argv: Optional[List[str]] = None) -> int:
    args = _parse_args(argv)
    strategy = build_strategy(args.strategy, args)
    config = _config_from_args(args)

    if not config.dry_run and not config.token:
        print("缺少用户令牌：设置 FULI_TOKEN 或用 --token 传入（--dry-run 不需要）",
              file=sys.stderr)
        return 2

    while True:
        try:
            outcome = run_once(strategy, config)
            _print_outcome(outcome, config)
        except Exception as e:  # 单轮失败不终止轮询，但要如实打印
            print("[ERROR] %s" % e, file=sys.stderr)
            if args.interval is None:
                return 1
        if args.interval is None:
            return 0
        try:
            time.sleep(args.interval)
        except KeyboardInterrupt:
            print("已停止")
            return 0


if __name__ == "__main__":
    sys.exit(main())
