"""示例策略：双均线金叉/死叉（long/flat）。

只做多：快线上穿慢线 → 全仓买入；快线下穿慢线 → 清仓。
演示 Strategy 接口的最小用法，也是测试引擎确定性的样例。
"""
from __future__ import annotations

from typing import Optional

from backtest.strategy import Strategy
from backtest.types import Bar, Context, Order


class SmaCrossStrategy(Strategy):
    def __init__(self, fast: int = 10, slow: int = 30):
        if fast >= slow:
            raise ValueError("fast 必须小于 slow")
        self.fast = fast
        self.slow = slow

    def describe(self) -> str:
        """给实时信号 runner 用：写进建议单 reason 的人话说明。"""
        return "SMA(%d,%d) cross" % (self.fast, self.slow)

    def on_bar(self, ctx: Context) -> Optional[Order]:
        closes = [b.close for b in ctx.bars]
        if len(closes) < self.slow + 1:
            return None

        fast_now = self._sma(closes, self.fast)
        slow_now = self._sma(closes, self.slow)
        fast_prev = self._sma(closes[:-1], self.fast)
        slow_prev = self._sma(closes[:-1], self.slow)

        # 金叉 → 全仓买入；死叉 → 清仓
        if fast_prev <= slow_prev and fast_now > slow_now and ctx.position <= 0:
            size = ctx.cash / ctx.bars[-1].close
            return Order("BUY", round(size, 8))
        if fast_prev >= slow_prev and fast_now < slow_now and ctx.position > 0:
            return Order("SELL", ctx.position)
        return None

    @staticmethod
    def _sma(closes, window):
        return sum(closes[-window:]) / window
