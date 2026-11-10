"""撮合器（ADR-0011 决策 2）。

口径：市场单在「次根 bar 开盘价」成交；买入加滑点、卖出减滑点；
费率区分 maker/taker（当前只有市场单 → taker）；不做部分成交——整单成交或整单拒绝。
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Optional

from .types import Order


@dataclass(frozen=True)
class MatcherConfig:
    """撮合参数。金额统一 round 到 round_digits（与 bar 的 DECIMAL(20,8) 一致）。"""

    slippage_bps: float = 0.0
    maker_fee: float = 0.0010
    taker_fee: float = 0.0010
    round_digits: int = 8

    def __post_init__(self):
        if self.slippage_bps < 0:
            raise ValueError("slippage_bps 必须 >= 0")
        if self.maker_fee < 0 or self.taker_fee < 0:
            raise ValueError("费率必须 >= 0")


@dataclass(frozen=True)
class FillOutcome:
    """撮合结果。rejected=True 表示整单拒绝，cash/position/fee 保持原样。"""

    rejected: bool
    price: float
    size: float
    fee: float
    cash: float
    position: float


class Matcher:
    """确定性撮合器。fill 不产生任何随机，同一输入必得同一输出。"""

    def __init__(self, config: MatcherConfig):
        self._config = config
        self._slippage = config.slippage_bps / 10_000.0

    def fill(self, order: Order, open_price: float, cash: float, position: float) -> FillOutcome:
        r = self._config.round_digits
        if order.size <= 0 or open_price <= 0:
            return FillOutcome(True, open_price, order.size, 0.0, cash, position)

        if order.side == "BUY":
            price = round(open_price * (1.0 + self._slippage), r)
            gross = round(order.size * price, r)
            fee = round(gross * self._config.taker_fee, r)
            total = round(gross + fee, r)
            if cash < total:
                return FillOutcome(True, price, order.size, 0.0, cash, position)
            return FillOutcome(False, price, order.size, fee,
                               round(cash - total, r), round(position + order.size, r))

        if order.side == "SELL":
            price = round(open_price * (1.0 - self._slippage), r)
            gross = round(order.size * price, r)
            fee = round(gross * self._config.taker_fee, r)
            if position + 1e-12 < order.size:
                return FillOutcome(True, price, order.size, 0.0, cash, position)
            return FillOutcome(False, price, order.size, fee,
                               round(cash + gross - fee, r), round(position - order.size, r))

        return FillOutcome(True, open_price, order.size, 0.0, cash, position)
