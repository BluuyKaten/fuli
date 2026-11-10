"""策略接口（ADR-0011 决策 4）。

回测与实盘共用同一份策略代码：策略只读 Context、产出 Order，不感知撮合细节。
约束：纯函数、确定性、看不到未来（ctx.bars 只含 [0..index]）。
"""
from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Optional

from .types import Context, Order


class Strategy(ABC):
    """策略基类。

    每根 bar 收盘后调用一次 on_bar；返回 None 表示本根不动作，
    返回 Order 表示发出订单（由引擎在下一根开盘撮合）。
    """

    @abstractmethod
    def on_bar(self, ctx: Context) -> Optional[Order]:
        raise NotImplementedError
