"""指标计算（ADR-0011 决策 3）。纯函数，输入权益序列与往返盈亏，输出指标。"""
from __future__ import annotations

from typing import List


def max_drawdown(equity_series: List[float]) -> float:
    """最大回撤：peak 从初始资金起步，随权益创新高更新。"""
    if not equity_series:
        return 0.0
    peak = equity_series[0]
    max_dd = 0.0
    for eq in equity_series:
        if eq > peak:
            peak = eq
        if peak > 0:
            dd = (peak - eq) / peak
            if dd > max_dd:
                max_dd = dd
    return max_dd


def total_return(equity_series: List[float], initial_cash: float) -> float:
    if not equity_series or initial_cash <= 0:
        return 0.0
    return (equity_series[-1] - initial_cash) / initial_cash


def win_rate(round_trip_pnls: List[float]) -> tuple:
    """返回 (win_rate, winning_trips, num_round_trips)。"""
    trips = [p for p in round_trip_pnls]
    if not trips:
        return 0.0, 0, 0
    winners = [p for p in trips if p > 0]
    return len(winners) / len(trips), len(winners), len(trips)


def profit_factor(round_trip_pnls: List[float]) -> float:
    """获利因子 = 盈利往返总盈利 / 亏损往返总亏损；无亏损往返记 inf。"""
    winners = [p for p in round_trip_pnls if p > 0]
    losers = [p for p in round_trip_pnls if p < 0]
    gross_loss = -sum(losers)
    if gross_loss <= 0:
        return float("inf") if winners else 0.0
    return sum(winners) / gross_loss
