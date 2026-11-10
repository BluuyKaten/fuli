"""指标单测。"""
from __future__ import annotations

import unittest

from backtest import metrics


class MetricsTest(unittest.TestCase):
    def test_max_drawdown(self):
        eq = [100.0, 120.0, 90.0, 110.0, 60.0]
        # peak 从 100 起步：100→120(peak)→90(dd 25%)→110→60(dd 50%)
        self.assertAlmostEqual(metrics.max_drawdown(eq), 0.5, places=12)

    def test_max_drawdown_no_decline(self):
        self.assertEqual(metrics.max_drawdown([100.0, 110.0, 120.0]), 0.0)

    def test_max_drawdown_empty(self):
        self.assertEqual(metrics.max_drawdown([]), 0.0)

    def test_total_return(self):
        self.assertAlmostEqual(metrics.total_return([100.0, 110.0], 100.0), 0.1, places=12)

    def test_win_rate(self):
        wr, winners, trips = metrics.win_rate([10.0, -5.0, 3.0])
        self.assertAlmostEqual(wr, 2 / 3, places=12)
        self.assertEqual(winners, 2)
        self.assertEqual(trips, 3)

    def test_win_rate_empty(self):
        wr, winners, trips = metrics.win_rate([])
        self.assertEqual(wr, 0.0)
        self.assertEqual(trips, 0)

    def test_profit_factor(self):
        self.assertAlmostEqual(metrics.profit_factor([10.0, -5.0, 3.0]), 13.0 / 5.0, places=12)

    def test_profit_factor_no_losses(self):
        self.assertEqual(metrics.profit_factor([10.0, 3.0]), float("inf"))

    def test_profit_factor_no_trades(self):
        self.assertEqual(metrics.profit_factor([]), 0.0)


if __name__ == "__main__":
    unittest.main()
