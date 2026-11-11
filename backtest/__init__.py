"""fuli 加密回测引擎（阶段 2）。

纯标准库、确定性、可单测。见 docs/adr/0011-crypto-backtest-strategy-and-matcher.md。
A 股成本口径见 docs/adr/0014-ashare-backtest-rules.md。
"""
from .engine import BacktestEngine
from .matcher import Matcher, MatcherConfig, ashare_config
from .strategy import Strategy
from .types import Bar, BacktestResult, Context, Fill, Order
from . import metrics, data

__all__ = [
    "BacktestEngine", "Matcher", "MatcherConfig", "ashare_config", "Strategy",
    "Bar", "BacktestResult", "Context", "Fill", "Order",
    "metrics", "data",
]
