#!/usr/bin/env python3
"""回测命令行入口。

用法：
  python -m backtest.run --symbol BTC-USDT.CRYPTO --period 1d \
      --from 2020-01-01 --to 2023-01-01 --base-url http://localhost:8084

从 data-service 的 /market/bars 取数，跑双均线策略，打印指标。
"""
from __future__ import annotations

import argparse

from backtest.data import fetch_bars
from backtest.engine import BacktestEngine
from backtest.matcher import Matcher, MatcherConfig
from backtest.examples.sma_cross import SmaCrossStrategy


def main() -> None:
    p = argparse.ArgumentParser(description="fuli 加密回测")
    p.add_argument("--symbol", default="BTC-USDT.CRYPTO")
    p.add_argument("--period", default="1d")
    p.add_argument("--from", dest="frm", default=None)
    p.add_argument("--to", default=None)
    p.add_argument("--limit", type=int, default=None)
    p.add_argument("--base-url", default="http://localhost:8084")
    p.add_argument("--initial-cash", type=float, default=10000.0)
    p.add_argument("--slippage-bps", type=float, default=0.0)
    p.add_argument("--taker-fee", type=float, default=0.001)
    p.add_argument("--fast", type=int, default=10)
    p.add_argument("--slow", type=int, default=30)
    args = p.parse_args()

    bars = fetch_bars(args.base_url, args.symbol, args.period,
                      frm=args.frm, to=args.to, limit=args.limit)
    if not bars:
        print("没有取到行情数据（请先在数据同步页同步该标的）")
        return

    engine = BacktestEngine(
        strategy=SmaCrossStrategy(args.fast, args.slow),
        matcher=Matcher(MatcherConfig(slippage_bps=args.slippage_bps, taker_fee=args.taker_fee)),
        initial_cash=args.initial_cash,
        lot_size=1e-8,
    )
    result = engine.run(bars)

    print(f"标的        {args.symbol} {args.period}")
    print(f"bar 数      {len(bars)}")
    print(f"初始资金    {result.initial_cash}")
    print(f"期末权益    {result.final_equity}")
    print(f"总收益      {result.total_return * 100:.2f}%")
    print(f"最大回撤    {result.max_drawdown * 100:.2f}%")
    print(f"胜率        {result.win_rate * 100:.2f}% ({result.winning_trips}/{result.num_round_trips})")
    pf = result.profit_factor
    print(f"获利因子    {'inf' if pf == float('inf') else f'{pf:.2f}'}")
    print(f"成交笔数    {len(result.fills)}")


if __name__ == "__main__":
    main()
