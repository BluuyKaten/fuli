#!/usr/bin/env python3
"""回测命令行入口。

用法：
  # 加密（默认）
  python -m backtest.run --symbol BTC-USDT.CRYPTO --period 1d \
      --from 2020-01-01 --to 2023-01-01 --base-url http://localhost:8084

  # A 股（一手 100 股、最低佣金 5 元、卖出印花税、涨跌停不可成交）
  python -m backtest.run --market ashare --symbol 600519.SH --period 1d \
      --from 2020-01-01 --to 2024-12-31 --adjust forward

从 data-service 的 /market/bars 取数，跑双均线策略，打印指标。
"""
from __future__ import annotations

import argparse

from backtest.data import fetch_bars
from backtest.engine import BacktestEngine
from backtest.matcher import Matcher, MatcherConfig, ashare_config
from backtest.examples.sma_cross import SmaCrossStrategy


def main() -> None:
    p = argparse.ArgumentParser(description="fuli 回测")
    p.add_argument("--market", default="crypto", choices=["crypto", "ashare"],
                   help="交易规则：crypto（默认，纯费率）或 ashare（一手/最低佣金/印花税/涨跌停）")
    p.add_argument("--symbol", default="BTC-USDT.CRYPTO")
    p.add_argument("--period", default="1d")
    p.add_argument("--from", dest="frm", default=None)
    p.add_argument("--to", default=None)
    p.add_argument("--limit", type=int, default=None)
    p.add_argument("--base-url", default="http://localhost:8084")
    p.add_argument("--adjust", default="none", choices=["none", "forward"],
                   help="复权方式；A 股长区间建议 forward，否则除权会造出假跌")
    p.add_argument("--initial-cash", type=float, default=10000.0)
    p.add_argument("--slippage-bps", type=float, default=0.0)
    p.add_argument("--taker-fee", type=float, default=0.0010)
    p.add_argument("--fast", type=int, default=10)
    p.add_argument("--slow", type=int, default=30)
    p.add_argument("--price-limit", type=float, default=0.10,
                   help="A 股涨跌停幅度：主板 0.10、创业板/科创板 0.20、ST 0.05")
    args = p.parse_args()

    bars = fetch_bars(args.base_url, args.symbol, args.period,
                      frm=args.frm, to=args.to, limit=args.limit, adjust=args.adjust)
    if not bars:
        print("没有取到行情数据（请先在数据同步页同步该标的）")
        return

    if args.market == "ashare":
        # 一手 100 股是硬约束：不设 lot_size 会算出「买 123.45 股」这种不存在的成交
        lot_size = 100.0
        matcher = Matcher(ashare_config(price_limit_pct=args.price_limit,
                                        slippage_bps=args.slippage_bps))
    else:
        lot_size = 1e-8
        matcher = Matcher(MatcherConfig(slippage_bps=args.slippage_bps,
                                        taker_fee=args.taker_fee))

    engine = BacktestEngine(
        strategy=SmaCrossStrategy(args.fast, args.slow),
        matcher=matcher,
        initial_cash=args.initial_cash,
        lot_size=lot_size,
    )
    result = engine.run(bars)

    print(f"标的        {args.symbol} {args.period}（{args.market} 规则）")
    print(f"bar 数      {len(bars)}")
    print(f"初始资金    {result.initial_cash}")
    print(f"期末权益    {result.final_equity}")
    print(f"总收益      {result.total_return * 100:.2f}%")
    print(f"最大回撤    {result.max_drawdown * 100:.2f}%")
    print(f"胜率        {result.win_rate * 100:.2f}% ({result.winning_trips}/{result.num_round_trips})")
    pf = result.profit_factor
    print(f"获利因子    {'inf' if pf == float('inf') else f'{pf:.2f}'}")
    print(f"成交笔数    {len(result.fills)}")
    if result.fills:
        print(f"累计费用    {sum(f.fee for f in result.fills):.2f}")
    if result.limit_rejections:
        # 一字板买不进 / 跌停卖不掉，策略无法规避，单列出来免得被当成「策略没信号」
        print(f"涨跌停拒单  {result.limit_rejections} 次")


if __name__ == "__main__":
    main()
