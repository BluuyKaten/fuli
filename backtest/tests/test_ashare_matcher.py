"""A 股撮合口径（ADR-0014）。

三件容易算错、算错了会系统性高估收益的事：
最低佣金（小单真实成本高一个量级）、印花税只在卖出收、涨跌停不可成交。
"""
from __future__ import annotations

import unittest

from backtest.engine import BacktestEngine
from backtest.matcher import Matcher, MatcherConfig, ashare_config
from backtest.strategy import Strategy
from backtest.types import Bar, Context, Order
from typing import Optional


class AShareCostTest(unittest.TestCase):
    def setUp(self):
        self.matcher = Matcher(ashare_config())

    def test_buy_min_commission_applies_to_small_order(self):
        # 100 股 × 10 元 = 1000 元；万分之 2.5 = 0.25 元，低于最低 5 元 → 收 5 元
        outcome = self.matcher.fill(Order("BUY", 100), open_price=10.0, cash=10_000.0, position=0)
        self.assertFalse(outcome.rejected)
        expected_fee = round(5.0 + 1000.0 * 0.00001, 2)  # 佣金 5 + 过户费 0.01
        self.assertAlmostEqual(outcome.fee, expected_fee, places=2)
        self.assertAlmostEqual(outcome.cash, round(10_000.0 - 1000.0 - expected_fee, 2), places=2)

    def test_sell_pays_stamp_tax_but_buy_does_not(self):
        buy = self.matcher.fill(Order("BUY", 100), open_price=10.0, cash=10_000.0, position=0)
        sell = self.matcher.fill(Order("SELL", 100), open_price=10.0, cash=0.0, position=100)
        # 卖出多出印花税 1000 × 0.05% = 0.5
        self.assertAlmostEqual(sell.fee - buy.fee, 0.5, places=2)

    def test_large_order_uses_rate_not_minimum(self):
        # 10000 股 × 100 元 = 100 万元；万分之 2.5 = 250 元 > 最低 5 元
        outcome = self.matcher.fill(Order("BUY", 10_000), open_price=100.0,
                                    cash=2_000_000.0, position=0)
        self.assertAlmostEqual(outcome.fee, round(250.0 + 1_000_000 * 0.00001, 2), places=2)

    def test_rejects_buy_when_cash_short_after_fees(self):
        # 刚好够买、但不够付手续费 → 整单拒绝（不做部分成交）
        outcome = self.matcher.fill(Order("BUY", 100), open_price=10.0, cash=1000.0, position=0)
        self.assertTrue(outcome.rejected)
        self.assertEqual(outcome.cash, 1000.0)


class ASharePriceLimitTest(unittest.TestCase):
    def setUp(self):
        self.matcher = Matcher(ashare_config(price_limit_pct=0.10))

    def test_buy_rejected_at_limit_up_open(self):
        # 前收 10 元 → 涨停 11.00；一字板开盘买不到
        outcome = self.matcher.fill(Order("BUY", 100), open_price=11.00,
                                    cash=10_000.0, position=0, pre_close=10.0)
        self.assertTrue(outcome.rejected)
        self.assertEqual(outcome.cash, 10_000.0)

    def test_buy_allowed_just_below_limit_up(self):
        outcome = self.matcher.fill(Order("BUY", 100), open_price=10.99,
                                    cash=10_000.0, position=0, pre_close=10.0)
        self.assertFalse(outcome.rejected)

    def test_sell_rejected_at_limit_down_open(self):
        outcome = self.matcher.fill(Order("SELL", 100), open_price=9.00,
                                    cash=0.0, position=100, pre_close=10.0)
        self.assertTrue(outcome.rejected)
        self.assertEqual(outcome.position, 100)

    def test_sell_allowed_just_above_limit_down(self):
        outcome = self.matcher.fill(Order("SELL", 100), open_price=9.01,
                                    cash=0.0, position=100, pre_close=10.0)
        self.assertFalse(outcome.rejected)

    def test_no_pre_close_means_no_limit(self):
        # 不传前收（例如首根 bar）时不做限制，避免误杀
        outcome = self.matcher.fill(Order("BUY", 100), open_price=11.00,
                                    cash=10_000.0, position=0)
        self.assertFalse(outcome.rejected)

    def test_growth_board_uses_20_percent(self):
        gem = Matcher(ashare_config(price_limit_pct=0.20))
        allowed = gem.fill(Order("BUY", 100), open_price=11.50,
                           cash=10_000.0, position=0, pre_close=10.0)
        rejected = gem.fill(Order("BUY", 100), open_price=12.00,
                            cash=10_000.0, position=0, pre_close=10.0)
        self.assertFalse(allowed.rejected)
        self.assertTrue(rejected.rejected)


class CryptoCompatibilityTest(unittest.TestCase):
    """默认参数必须与 ADR-0011 完全一致，否则加密回测的历史结论会悄悄变掉。"""

    def test_default_config_has_no_ashare_rules(self):
        cfg = MatcherConfig()
        self.assertEqual(cfg.min_commission, 0.0)
        self.assertEqual(cfg.sell_tax_rate, 0.0)
        self.assertEqual(cfg.transfer_fee_rate, 0.0)
        self.assertEqual(cfg.price_limit_pct, 0.0)

    def test_crypto_fee_is_pure_rate(self):
        matcher = Matcher(MatcherConfig(taker_fee=0.001, round_digits=8))
        outcome = matcher.fill(Order("BUY", 1.0), open_price=100.0, cash=1000.0, position=0)
        self.assertAlmostEqual(outcome.fee, 0.1, places=8)

    def test_crypto_ignores_price_limit(self):
        matcher = Matcher(MatcherConfig(taker_fee=0.001, round_digits=8))
        # 前收翻倍也照常成交——加密没有涨跌停
        outcome = matcher.fill(Order("BUY", 1.0), open_price=200.0,
                              cash=1000.0, position=0, pre_close=100.0)
        self.assertFalse(outcome.rejected)


class ConfigValidationTest(unittest.TestCase):
    def test_rejects_negative_min_commission(self):
        with self.assertRaises(ValueError):
            MatcherConfig(min_commission=-1)

    def test_rejects_price_limit_out_of_range(self):
        with self.assertRaises(ValueError):
            MatcherConfig(price_limit_pct=1.0)
        with self.assertRaises(ValueError):
            MatcherConfig(price_limit_pct=-0.1)

    def test_overrides_are_applied(self):
        cfg = ashare_config(price_limit_pct=0.20, slippage_bps=5)
        self.assertEqual(cfg.price_limit_pct, 0.20)
        self.assertEqual(cfg.slippage_bps, 5)


class AlwaysBuyThenSell(Strategy):
    def on_bar(self, ctx: Context) -> Optional[Order]:
        if ctx.index == 0:
            return Order("BUY", 100)
        return None


def bar(ts, close, open_=None):
    price = close if open_ is None else open_
    return Bar(ts=ts, open=price, high=price, low=price, close=close, volume=1.0)


class LimitRejectionCountingTest(unittest.TestCase):
    """涨跌停拒单要能被统计出来——否则用户看到 0 笔成交会以为策略没信号。"""

    def test_limit_up_rejection_is_counted(self):
        bars = [
            bar(0, 10.0),
            bar(1, 11.0),  # 前收 10 → 涨停 11，一字板开盘买不进
        ]
        engine = BacktestEngine(AlwaysBuyThenSell(), Matcher(ashare_config()),
                                initial_cash=100_000.0, lot_size=100.0)
        result = engine.run(bars)
        self.assertEqual(result.limit_rejections, 1)
        self.assertEqual(len(result.fills), 0)

    def test_normal_fill_does_not_count(self):
        bars = [bar(0, 10.0), bar(1, 10.5)]
        engine = BacktestEngine(AlwaysBuyThenSell(), Matcher(ashare_config()),
                                initial_cash=100_000.0, lot_size=100.0)
        result = engine.run(bars)
        self.assertEqual(result.limit_rejections, 0)
        self.assertEqual(len(result.fills), 1)

    def test_crypto_never_counts_limit_rejections(self):
        bars = [bar(0, 10.0), bar(1, 100.0)]  # 10 倍跳空也不算涨跌停
        engine = BacktestEngine(AlwaysBuyThenSell(),
                                Matcher(MatcherConfig(taker_fee=0.0, round_digits=8)),
                                initial_cash=100_000.0, lot_size=1e-8)
        result = engine.run(bars)
        self.assertEqual(result.limit_rejections, 0)
        self.assertEqual(len(result.fills), 1)


if __name__ == "__main__":
    unittest.main()
