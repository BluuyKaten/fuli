"""回测引擎单测：确定性、撮合时点、往返盈亏、不可见未来。"""
from __future__ import annotations

import unittest
from typing import Optional

from backtest.engine import BacktestEngine
from backtest.matcher import Matcher, MatcherConfig
from backtest.strategy import Strategy
from backtest.types import Bar, Context, Order


def bar(ts, o, h, l, c, v=1.0):
    return Bar(ts=ts, open=o, high=h, low=l, close=c, volume=v)


def zero_fee_matcher():
    return Matcher(MatcherConfig(slippage_bps=0.0, taker_fee=0.0, round_digits=8))


class AlwaysBuyFirstBar(Strategy):
    """首根收盘后买 1 手、之后一直持有。"""

    def on_bar(self, ctx: Context) -> Optional[Order]:
        if ctx.index == 0:
            return Order("BUY", 1.0)
        return None


class BuyHoldThenSellLast(Strategy):
    def on_bar(self, ctx: Context) -> Optional[Order]:
        if ctx.index == 0:
            return Order("BUY", 1.0)
        if ctx.index == 2:
            return Order("SELL", 1.0)
        return None


class PeekGuard(Strategy):
    """记录策略每步看到的 bar 数，断言没有泄露未来。"""

    def __init__(self):
        self.seen_lengths = []

    def on_bar(self, ctx: Context) -> Optional[Order]:
        self.seen_lengths.append(len(ctx.bars))
        return None


class EngineTest(unittest.TestCase):
    def test_buy_hold_sell_round_trip(self):
        bars = [
            bar(0, 100, 100, 100, 100),
            bar(1, 100, 110, 100, 110),
            bar(2, 110, 120, 110, 120),
            bar(3, 120, 120, 100, 100),
        ]
        engine = BacktestEngine(BuyHoldThenSellLast(), zero_fee_matcher(),
                                initial_cash=1000.0, lot_size=1.0)
        r = engine.run(bars)

        # 买在 bar1 开盘 100，卖在 bar3 开盘 120 → 往返盈利 20
        self.assertEqual(r.num_round_trips, 1)
        self.assertEqual(r.round_trip_pnls, [20.0])
        self.assertEqual(r.final_equity, 1020.0)
        self.assertAlmostEqual(r.total_return, 0.02, places=12)
        self.assertEqual(r.win_rate, 1.0)
        self.assertEqual(r.profit_factor, float("inf"))

    def test_deterministic_nav(self):
        bars = [bar(i, 100 + i, 100 + i + 5, 100 + i, 100 + i + 2) for i in range(50)]
        engine = BacktestEngine(AlwaysBuyFirstBar(), zero_fee_matcher(),
                                initial_cash=10000.0, lot_size=1.0)
        r1 = engine.run(bars)
        r2 = engine.run(bars)
        self.assertEqual(r1.nav_series, r2.nav_series, "nav 序列必须逐位相同")
        self.assertEqual(r1.equity_series, r2.equity_series)

    def test_strategy_never_sees_future(self):
        bars = [bar(i, 100, 100, 100, 100) for i in range(10)]
        guard = PeekGuard()
        BacktestEngine(guard, zero_fee_matcher(), initial_cash=1000.0).run(bars)
        # 每步看到的 bar 数 = index + 1（不含未来），最后一根不决策
        self.assertEqual(guard.seen_lengths, list(range(1, len(bars))))

    def test_lot_size_rounds_down(self):
        bars = [
            bar(0, 100, 100, 100, 100),
            bar(1, 100, 100, 100, 100),
        ]

        class BuyFractional(Strategy):
            def on_bar(self, ctx: Context) -> Optional[Order]:
                if ctx.index == 0:
                    return Order("BUY", 2.7)  # lot_size=2 → 向下取整到 2
                return None

        engine = BacktestEngine(BuyFractional(), zero_fee_matcher(),
                                initial_cash=1000.0, lot_size=2.0)
        r = engine.run(bars)
        # 买 2 手 × 100 = 200
        self.assertEqual(r.fills[0].size, 2.0)
        self.assertEqual(r.fills[0].cash_after, 800.0)

    def test_rejects_initial_cash_invalid(self):
        with self.assertRaises(ValueError):
            BacktestEngine(AlwaysBuyFirstBar(), zero_fee_matcher(), initial_cash=0.0)


if __name__ == "__main__":
    unittest.main()
