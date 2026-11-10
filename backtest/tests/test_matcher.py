"""撮合器单测。"""
from __future__ import annotations

import unittest

from backtest.matcher import Matcher, MatcherConfig
from backtest.types import Order


class MatcherTest(unittest.TestCase):
    def test_buy_applies_slippage_and_taker_fee(self):
        m = Matcher(MatcherConfig(slippage_bps=10.0, taker_fee=0.001, round_digits=8))
        out = m.fill(Order("BUY", 1.0), open_price=100.0, cash=1000.0, position=0.0)
        self.assertFalse(out.rejected)
        self.assertEqual(out.price, 100.1)  # 100 * (1 + 10/10000)
        self.assertEqual(out.fee, 0.1001)  # 100.1 * 0.001
        self.assertEqual(out.cash, 1000.0 - 100.1 - 0.1001)
        self.assertEqual(out.position, 1.0)

    def test_sell_applies_negative_slippage(self):
        m = Matcher(MatcherConfig(slippage_bps=10.0, taker_fee=0.0, round_digits=8))
        out = m.fill(Order("SELL", 1.0), open_price=100.0, cash=0.0, position=1.0)
        self.assertFalse(out.rejected)
        self.assertEqual(out.price, 99.9)  # 100 * (1 - 10/10000)
        self.assertEqual(out.cash, 99.9)

    def test_buy_rejected_when_cash_insufficient(self):
        m = Matcher(MatcherConfig(taker_fee=0.0, round_digits=8))
        out = m.fill(Order("BUY", 1.0), open_price=100.0, cash=99.9, position=0.0)
        self.assertTrue(out.rejected)
        self.assertEqual(out.cash, 99.9)
        self.assertEqual(out.position, 0.0)

    def test_sell_rejected_when_position_insufficient(self):
        m = Matcher(MatcherConfig(taker_fee=0.0, round_digits=8))
        out = m.fill(Order("SELL", 1.0), open_price=100.0, cash=0.0, position=0.5)
        self.assertTrue(out.rejected)
        self.assertEqual(out.position, 0.5)

    def test_rejects_invalid_order(self):
        m = Matcher(MatcherConfig())
        self.assertTrue(m.fill(Order("BUY", 0.0), 100.0, 1000.0, 0.0).rejected)
        self.assertTrue(m.fill(Order("BUY", -1.0), 100.0, 1000.0, 0.0).rejected)
        self.assertTrue(m.fill(Order("HOLD", 1.0), 100.0, 1000.0, 0.0).rejected)

    def test_rejects_invalid_config(self):
        with self.assertRaises(ValueError):
            MatcherConfig(slippage_bps=-1.0)
        with self.assertRaises(ValueError):
            MatcherConfig(taker_fee=-0.01)


if __name__ == "__main__":
    unittest.main()
