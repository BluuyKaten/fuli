"""撮合器（ADR-0011 决策 2 / ADR-0014 A 股扩展）。

口径：市场单在「次根 bar 开盘价」成交；买入加滑点、卖出减滑点；
费率区分 maker/taker（当前只有市场单 → taker）；不做部分成交——整单成交或整单拒绝。

A 股规则（涨跌停、最低佣金、印花税、过户费）全部是**默认关闭**的可选参数：
加密回测不传这些参数时，`max(佣金, 0)`、`pct=0` 都退化成空操作，逐位结果与 ADR-0011 一致。
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

    # ---- A 股成本项（默认 0 = 不启用，加密口径不受影响）----
    min_commission: float = 0.0
    """每笔最低佣金。A 股通常 5 元：小额单的实际费率远高于万分之几，不建模会低估成本。"""

    sell_tax_rate: float = 0.0
    """印花税，**只在卖出**收。A 股 0.05%（2023-08 减半后）。"""

    transfer_fee_rate: float = 0.0
    """过户费，买卖双向。沪深现均 0.001%。"""

    price_limit_pct: float = 0.0
    """涨跌停幅度；0 表示不限制。主板 0.10、创业板/科创板 0.20、ST 0.05。"""

    def __post_init__(self):
        if self.slippage_bps < 0:
            raise ValueError("slippage_bps 必须 >= 0")
        if self.maker_fee < 0 or self.taker_fee < 0:
            raise ValueError("费率必须 >= 0")
        if self.min_commission < 0:
            raise ValueError("min_commission 必须 >= 0")
        if self.sell_tax_rate < 0 or self.transfer_fee_rate < 0:
            raise ValueError("税率/过户费率必须 >= 0")
        if not 0.0 <= self.price_limit_pct < 1.0:
            raise ValueError("price_limit_pct 必须落在 [0, 1)")


def ashare_config(price_limit_pct: float = 0.10, **overrides) -> MatcherConfig:
    """A 股日线回测的默认成本口径（按 2026 年现行费率）。

    - 佣金万分之 2.5、每笔最低 5 元
    - 印花税 0.05%（仅卖出）
    - 过户费 0.001%（双向）
    - 涨跌停 10%（创业板/科创板传 0.20，ST 传 0.05）

    ``round_digits=2``：A 股价格与金额都以分为最小单位。

    ⚠️ 涨跌停只建模「一字板不可成交」：开盘价已触及涨停则买单拒绝、跌停则卖单拒绝。
    真实市场里涨停也可能成交（排队/撬板），这里取保守侧——**宁可低估收益，不要高估**。
    """
    base = dict(
        slippage_bps=0.0,
        maker_fee=0.00025,
        taker_fee=0.00025,
        round_digits=2,
        min_commission=5.0,
        sell_tax_rate=0.0005,
        transfer_fee_rate=0.00001,
        price_limit_pct=price_limit_pct,
    )
    base.update(overrides)
    return MatcherConfig(**base)


@dataclass(frozen=True)
class FillOutcome:
    """撮合结果。rejected=True 表示整单拒绝，cash/position/fee 保持原样。

    ``reason`` 说明拒绝原因（``insufficient_cash`` / ``insufficient_position`` /
    ``limit_up`` / ``limit_down`` / ``invalid_order``）。回测报告靠它区分
    「钱不够」和「一字板买不进」——后者是 A 股特有的、策略无法规避的成交损失。
    """

    rejected: bool
    price: float
    size: float
    fee: float
    cash: float
    position: float
    reason: str = ""


class Matcher:
    """确定性撮合器。fill 不产生任何随机，同一输入必得同一输出。"""

    def __init__(self, config: MatcherConfig):
        self._config = config
        self._slippage = config.slippage_bps / 10_000.0

    def fill(self, order: Order, open_price: float, cash: float, position: float,
             pre_close: Optional[float] = None) -> FillOutcome:
        """撮合一笔订单。

        :param pre_close: 上一根 bar 的收盘价，仅用于涨跌停判断；不传则不限制。
        """
        r = self._config.round_digits
        if order.size <= 0 or open_price <= 0:
            return FillOutcome(True, open_price, order.size, 0.0, cash, position, "invalid_order")

        limit_reason = self._limit_reason(order.side, open_price, pre_close)
        if limit_reason:
            return FillOutcome(True, open_price, order.size, 0.0, cash, position, limit_reason)

        if order.side == "BUY":
            price = round(open_price * (1.0 + self._slippage), r)
            gross = round(order.size * price, r)
            fee = self._fee("BUY", gross)
            total = round(gross + fee, r)
            if cash < total:
                return FillOutcome(True, price, order.size, 0.0, cash, position, "insufficient_cash")
            return FillOutcome(False, price, order.size, fee,
                               round(cash - total, r), round(position + order.size, r))

        if order.side == "SELL":
            price = round(open_price * (1.0 - self._slippage), r)
            gross = round(order.size * price, r)
            fee = self._fee("SELL", gross)
            if position + 1e-12 < order.size:
                return FillOutcome(True, price, order.size, 0.0, cash, position, "insufficient_position")
            return FillOutcome(False, price, order.size, fee,
                               round(cash + gross - fee, r), round(position - order.size, r))

        return FillOutcome(True, open_price, order.size, 0.0, cash, position, "invalid_order")

    def _fee(self, side: str, gross: float) -> float:
        """佣金（含最低）/ 印花税（仅卖出）/ 过户费（双向）。默认全 0 时退化为纯费率。"""
        r = self._config.round_digits
        commission = max(gross * self._config.taker_fee, self._config.min_commission)
        extra_rate = self._config.transfer_fee_rate
        if side == "SELL":
            extra_rate += self._config.sell_tax_rate
        return round(commission + gross * extra_rate, r)

    def _limit_reason(self, side: str, open_price: float, pre_close: Optional[float]) -> str:
        """涨跌停可成交性。

        开盘价已经封在涨停（买不到）或跌停（卖不掉）时整单拒绝。
        限价按「前收盘 × (1±pct) 四舍五入到价格精度」算，与交易所的涨跌停价口径一致。

        :return ``"limit_up"`` / ``"limit_down"``；可成交返回空串
        """
        pct = self._config.price_limit_pct
        if pct <= 0 or pre_close is None or pre_close <= 0:
            return ""
        r = self._config.round_digits
        upper = round(pre_close * (1.0 + pct), r)
        lower = round(pre_close * (1.0 - pct), r)
        if side == "BUY" and open_price >= upper:
            return "limit_up"
        if side == "SELL" and open_price <= lower:
            return "limit_down"
        return ""
