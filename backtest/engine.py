"""回测引擎（ADR-0011 决策 2/3/4/5）。

确定性主循环：策略在 bar i 收盘后决策，订单在 bar i+1 开盘撮合；
每根 bar 收盘记录一条权益/净值。同一份 bars + 同一参数 → 逐位相同的 nav。
"""
from __future__ import annotations

import math
from typing import List, Optional

from .matcher import Matcher
from .metrics import max_drawdown, profit_factor, total_return, win_rate
from .strategy import Strategy
from .types import BacktestResult, Bar, Context, Fill, Order

_EPS = 1e-12


class BacktestEngine:
    """把策略跑在一段历史 bars 上，产出净值曲线与指标。"""

    def __init__(self, strategy: Strategy, matcher: Matcher,
                 initial_cash: float, lot_size: float = 1e-8):
        if initial_cash <= 0:
            raise ValueError("initial_cash 必须 > 0")
        if lot_size <= 0:
            raise ValueError("lot_size 必须 > 0")
        self._strategy = strategy
        self._matcher = matcher
        self._initial_cash = initial_cash
        self._lot_size = lot_size

    def run(self, bars: List[Bar]) -> BacktestResult:
        bars = list(bars)
        n = len(bars)
        result = BacktestResult(initial_cash=self._initial_cash, final_equity=self._initial_cash,
                                total_return=0.0, max_drawdown=0.0, win_rate=0.0, profit_factor=0.0,
                                num_round_trips=0, winning_trips=0)

        cash = self._initial_cash
        position = 0.0
        open_cost = 0.0
        open_proceeds = 0.0
        round_trips: List[float] = []
        fills: List[Fill] = []
        pending: Optional[Order] = None

        for i in range(n):
            bar = bars[i]

            # 1) 撮合上一根收盘时挂起的订单（在 bar i 开盘成交）
            if pending is not None and i >= 1:
                outcome = self._matcher.fill(pending, bar.open, cash, position)
                if not outcome.rejected:
                    cash = outcome.cash
                    position = outcome.position
                    fee = outcome.fee
                    if pending.side == "BUY":
                        open_cost += round(outcome.size * outcome.price + fee, 8)
                    else:  # SELL
                        open_proceeds += round(outcome.size * outcome.price - fee, 8)
                    fills.append(Fill(ts=bar.ts, side=pending.side, price=outcome.price,
                                      size=outcome.size, fee=fee, cash_after=cash,
                                      position_after=position))
                    # 平仓判定：position 归零即一个完整往返
                    if position <= _EPS:
                        round_trips.append(round(open_proceeds - open_cost, 8))
                        position = 0.0
                        open_cost = 0.0
                        open_proceeds = 0.0
                # 无论成交与否，订单只尝试一次（不做部分成交、不挂单过夜）
            pending = None

            # 2) 记录 bar i 收盘权益/净值
            equity = round(cash + position * bar.close, 8)
            result.equity_series.append(equity)
            result.nav_series.append(round(equity / self._initial_cash, 8))

            # 3) 策略在 bar i 收盘后决策（最后一根不决策：没有下一根开盘可成交）
            if i < n - 1:
                ctx = Context(index=i, bars=tuple(bars[:i + 1]), cash=cash,
                              position=position, equity=equity)
                order = self._strategy.on_bar(ctx)
                if order is not None:
                    size = self._floor_to_lot(order.size)
                    if size >= self._lot_size:
                        pending = Order(order.side, size)

        result.fills = fills
        result.round_trip_pnls = round_trips
        result.final_equity = result.equity_series[-1] if result.equity_series else self._initial_cash
        result.total_return = total_return(result.equity_series, self._initial_cash)
        result.max_drawdown = max_drawdown(result.equity_series)
        wr, winners, trips = win_rate(round_trips)
        result.win_rate = wr
        result.winning_trips = winners
        result.num_round_trips = trips
        result.profit_factor = profit_factor(round_trips)
        return result

    def _floor_to_lot(self, size: float) -> float:
        """买入/卖出数量向下取整到 lot_size 的整数倍。"""
        lots = math.floor(size / self._lot_size + _EPS)
        return round(lots * self._lot_size, 12)
