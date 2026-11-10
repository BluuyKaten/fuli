"""回测核心类型定义（纯标准库，无第三方依赖）。

确定性约束（ADR-0011 决策 5）：这些类型全部可冻结（frozen）或只包含普通数值，
不依赖 dict/set 迭代顺序；历史行情用 tuple 保序。
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Optional, Tuple


@dataclass(frozen=True)
class Bar:
    """一根已完结 K 线。ts 为 bar 开盘时刻（Unix 秒，UTC），仅作标识与排序。"""

    ts: int
    open: float
    high: float
    low: float
    close: float
    volume: float


@dataclass(frozen=True)
class Order:
    """策略发出的订单。side ∈ {BUY, SELL}，size 为 base 数量（> 0）。"""

    side: str
    size: float


@dataclass
class Context:
    """策略在每根 bar 收盘后看到的只读上下文。

    bars 只含 [0..index]（不含未来），已冻结为 tuple。
    """

    index: int
    bars: Tuple[Bar, ...]
    cash: float
    position: float
    equity: float


@dataclass(frozen=True)
class Fill:
    """一笔成交。price 为含滑点的成交价，fee 为费用（quote）。"""

    ts: int
    side: str
    price: float
    size: float
    fee: float
    cash_after: float
    position_after: float


@dataclass
class BacktestResult:
    """一次回测的完整结果。"""

    initial_cash: float
    final_equity: float
    total_return: float
    max_drawdown: float
    win_rate: float
    profit_factor: float
    num_round_trips: int
    winning_trips: int
    equity_series: list = field(default_factory=list)
    nav_series: list = field(default_factory=list)
    fills: list = field(default_factory=list)
    round_trip_pnls: list = field(default_factory=list)
