"""fuli 加密回测引擎（阶段 2）。

纯标准库、确定性、可单测。见 docs/adr/0011-crypto-backtest-strategy-and-matcher.md。
"""
from .engine import BacktestEngine
from .matcher import Matcher, MatcherConfig
from .strategy import Strategy
from .types import Bar, BacktestResult, Context, Fill, Order
from . import metrics, data

__all__ = [
    "BacktestEngine", "Matcher", "MatcherConfig", "Strategy",
    "Bar", "BacktestResult", "Context", "Fill", "Order",
    "metrics", "data",
]
