"""实时信号 runner 单测：去重、未完结 bar、台账、失败重试。

这几个点任一放松，建议单就会重复刷屏或偷看未来，所以逐条钉死。
"""
from __future__ import annotations

import json
import os
import tempfile
import unittest
from typing import List, Optional
from unittest import mock

import backtest.live_signal as live
from backtest.live_signal import (
    LiveSignalConfig, SignalState, build_suggestion_payload, drop_unclosed,
    load_state, run_once, save_state, to_inst_id,
)
from backtest.strategy import Strategy
from backtest.types import Bar, Context, Order

START = 1_700_000_000  # 2023-11-14，远早于测试运行时刻，天然是已完结 bar


def make_bars(n: int = 40, step: int = 3600, price: float = 100.0) -> List[Bar]:
    return [Bar(ts=START + i * step, open=price, high=price, low=price,
                close=price + i, volume=1.0) for i in range(n)]


class RecordingStrategy(Strategy):
    """返回固定订单，并记下每次看到的 Context 供断言。"""

    def __init__(self, order: Optional[Order] = None):
        self.order = order
        self.contexts: List[Context] = []

    def on_bar(self, ctx: Context) -> Optional[Order]:
        self.contexts.append(ctx)
        return self.order


class FakeSubmit:
    def __init__(self, error: Optional[Exception] = None):
        self.calls: List[dict] = []
        self.error = error
        self.response = {
            "code": 200,
            "data": {"submitted": True,
                     "result": json.dumps({"code": 200, "data": {"id": 42}})},
        }

    def __call__(self, ai_base_url, token, payload):
        self.calls.append(payload)
        if self.error is not None:
            raise self.error
        return self.response


class LiveSignalTest(unittest.TestCase):
    def setUp(self):
        self.tmpdir = tempfile.mkdtemp(prefix="fuli-signal-")
        self.addCleanup(self._cleanup)
        self.state_path = os.path.join(self.tmpdir, "state.json")

    def _cleanup(self):
        for name in os.listdir(self.tmpdir):
            os.remove(os.path.join(self.tmpdir, name))
        os.rmdir(self.tmpdir)

    def _config(self, **kwargs) -> LiveSignalConfig:
        base = dict(symbol="BTC-USDT.CRYPTO", period="1h", token="jwt-abc",
                    state_path=self.state_path, ai_base_url="http://localhost:8085")
        base.update(kwargs)
        return LiveSignalConfig(**base)

    # --------------------------------------------------------- payload

    def test_buy_signal_builds_market_payload(self):
        bars = make_bars()
        strategy = RecordingStrategy(Order("BUY", 0.0123))
        submit = FakeSubmit()

        outcome = run_once(strategy, self._config(), fetch=lambda *a, **k: bars, submit=submit)

        self.assertEqual(outcome.status, "submitted")
        self.assertEqual(len(submit.calls), 1)
        payload = submit.calls[0]
        self.assertEqual(payload["instId"], "BTC-USDT")      # 去掉 .CRYPTO
        self.assertEqual(payload["side"], "buy")             # BUY → buy
        self.assertEqual(payload["ordType"], "market")       # 策略没有 px，走市价
        self.assertEqual(payload["sz"], "0.0123")            # 十进制，不是 1.23e-02
        self.assertNotIn("px", payload)
        self.assertIn("RecordingStrategy", payload["reason"])

    def test_sell_uses_ledger_position(self):
        save_state(self.state_path, SignalState(last_bar_ts=START, last_side="buy",
                                                position=2.0, cash=800.0))
        bars = make_bars()
        strategy = RecordingStrategy(Order("SELL", 2.0))
        submit = FakeSubmit()

        run_once(strategy, self._config(), fetch=lambda *a, **k: bars, submit=submit)

        ctx = strategy.contexts[-1]
        self.assertEqual(ctx.position, 2.0, "策略要看到台账持仓，否则死叉时永远不卖")
        self.assertEqual(ctx.cash, 800.0)
        self.assertEqual(submit.calls[0]["side"], "sell")

        state = load_state(self.state_path)
        self.assertEqual(state.position, 0.0)
        self.assertEqual(state.last_side, "sell")
        self.assertEqual(state.last_suggestion_id, "42")

    def test_invalid_side_rejected(self):
        bars = make_bars()
        with self.assertRaises(ValueError):
            run_once(RecordingStrategy(Order("HOLD", 1.0)), self._config(),
                     fetch=lambda *a, **k: bars, submit=FakeSubmit())

    def test_limit_without_px_rejected(self):
        bars = make_bars()
        with self.assertRaises(ValueError):
            run_once(RecordingStrategy(Order("BUY", 1.0)),
                     self._config(ord_type="limit"),
                     fetch=lambda *a, **k: bars, submit=FakeSubmit())

    def test_payload_limit_uses_px_attribute(self):
        order = Order("BUY", 1.0)
        object.__setattr__(order, "px", 99.5)  # frozen dataclass，模拟未来扩展
        payload = build_suggestion_payload(order, self._config(ord_type="limit"),
                                           make_bars()[-1], "SMA(10,30) cross")
        self.assertEqual(payload["ordType"], "limit")
        self.assertEqual(payload["px"], "99.5")

    # --------------------------------------------------------- 去重

    def test_same_bar_not_reprocessed(self):
        bars = make_bars()
        strategy = RecordingStrategy(Order("BUY", 1.0))
        submit = FakeSubmit()
        config = self._config()

        first = run_once(strategy, config, fetch=lambda *a, **k: bars, submit=submit)
        second = run_once(strategy, config, fetch=lambda *a, **k: bars, submit=submit)

        self.assertEqual(first.status, "submitted")
        self.assertEqual(second.status, "duplicate_bar")
        self.assertEqual(len(submit.calls), 1, "同一根 bar 只能拉一次号")
        self.assertEqual(len(strategy.contexts), 1)

    def test_no_signal_still_advances_bar(self):
        bars = make_bars()
        strategy = RecordingStrategy(None)
        config = self._config()

        first = run_once(strategy, config, fetch=lambda *a, **k: bars, submit=FakeSubmit())
        second = run_once(strategy, config, fetch=lambda *a, **k: bars, submit=FakeSubmit())

        self.assertEqual(first.status, "no_signal")
        self.assertEqual(second.status, "duplicate_bar")
        self.assertEqual(len(strategy.contexts), 1, "无信号的 bar 也算处理过")

    def test_same_side_skipped(self):
        bars = make_bars()
        strategy = RecordingStrategy(Order("BUY", 1.0))
        submit = FakeSubmit()
        config = self._config()

        run_once(strategy, config, fetch=lambda *a, **k: bars, submit=submit)

        longer = make_bars(n=41)  # 多一根新 bar，但方向没变
        outcome = run_once(strategy, config, fetch=lambda *a, **k: longer, submit=submit)

        self.assertEqual(outcome.status, "duplicate_side")
        self.assertEqual(len(submit.calls), 1)
        self.assertEqual(outcome.bar.ts, longer[-1].ts)

    def test_allow_repeat_writes_again(self):
        bars = make_bars()
        strategy = RecordingStrategy(Order("BUY", 1.0))
        submit = FakeSubmit()
        config = self._config(allow_repeat=True)

        run_once(strategy, config, fetch=lambda *a, **k: bars, submit=submit)
        outcome = run_once(strategy, config, fetch=lambda *a, **k: make_bars(n=41), submit=submit)

        self.assertEqual(outcome.status, "submitted")
        self.assertEqual(len(submit.calls), 2)

    def test_ignore_state_keeps_ledger(self):
        """--ignore-state 只放弃去重，台账要留着，否则重跑一次持仓就归零。"""
        save_state(self.state_path, SignalState(last_bar_ts=START + 39 * 3600,
                                                last_side="buy", position=2.0, cash=800.0))
        strategy = RecordingStrategy(None)

        run_once(strategy, self._config(ignore_state=True),
                 fetch=lambda *a, **k: make_bars(), submit=FakeSubmit())

        self.assertEqual(len(strategy.contexts), 1, "--ignore-state 应忽略 bar 去重")
        self.assertEqual(strategy.contexts[-1].position, 2.0)
        self.assertEqual(strategy.contexts[-1].cash, 800.0)
        self.assertEqual(load_state(self.state_path).position, 2.0)

    # --------------------------------------------------------- 失败与演练

    def test_submit_failure_keeps_state(self):
        bars = make_bars()
        strategy = RecordingStrategy(Order("BUY", 1.0))
        config = self._config()

        with self.assertRaises(RuntimeError):
            run_once(strategy, config, fetch=lambda *a, **k: bars,
                     submit=FakeSubmit(error=RuntimeError("boom")))

        state = load_state(self.state_path)
        self.assertIsNone(state.last_bar_ts, "提交失败不能推进 bar，否则信号永久丢失")
        self.assertIsNone(state.last_side)

        # 重试同一根 bar 能成功（策略确定性 → 重算出同样的订单）
        submit = FakeSubmit()
        outcome = run_once(strategy, config, fetch=lambda *a, **k: bars, submit=submit)
        self.assertEqual(outcome.status, "submitted")

    def test_dry_run_touches_nothing(self):
        bars = make_bars()
        strategy = RecordingStrategy(Order("BUY", 1.0))
        submit = FakeSubmit()
        config = self._config(dry_run=True)

        outcome = run_once(strategy, config, fetch=lambda *a, **k: bars, submit=submit)
        self.assertEqual(outcome.status, "dry_run")
        self.assertEqual(submit.calls, [])
        self.assertEqual(outcome.payload["side"], "buy")
        self.assertFalse(os.path.exists(self.state_path), "演练模式不该写 state")

        again = run_once(strategy, config, fetch=lambda *a, **k: bars, submit=submit)
        self.assertEqual(again.status, "dry_run", "没写 state，第二次仍是演练而不是 duplicate_bar")

    # --------------------------------------------------------- bar 完结性

    def test_drop_unclosed_bar(self):
        now = START + 40 * 3600 - 1800  # 最后一根 bar 才走了一半
        bars = make_bars(n=40)
        kept = drop_unclosed(bars, "1h", now=now)
        self.assertEqual(len(kept), 39)
        self.assertEqual(kept[-1].ts, bars[-2].ts)

        closed = drop_unclosed(bars, "1h", now=START + 40 * 3600)
        self.assertEqual(len(closed), 40)

    def test_unknown_period_keeps_bars(self):
        bars = make_bars(n=5)
        self.assertEqual(len(drop_unclosed(bars, "1mo", now=START)), 5)

    def test_run_once_drops_unclosed_by_default(self):
        bars = make_bars()
        strategy = RecordingStrategy(Order("BUY", 1.0))
        # 把「现在」挪到最后一根 bar 开盘后 60 秒：它必然还没走完
        with mock.patch("backtest.live_signal.time.time", return_value=bars[-1].ts + 60):
            run_once(strategy, self._config(), fetch=lambda *a, **k: bars,
                     submit=FakeSubmit())
        self.assertLess(strategy.contexts[-1].bars[-1].ts, bars[-1].ts,
                        "决策依据不能是未完结的那根 bar")

    # --------------------------------------------------------- state 与工具

    def test_state_roundtrip(self):
        state = SignalState(last_bar_ts=123, last_side="sell", position=1.5,
                            cash=900.0, last_suggestion_id="7")
        save_state(self.state_path, state)
        self.assertEqual(load_state(self.state_path), state)

    def test_corrupt_state_raises(self):
        with open(self.state_path, "w", encoding="utf-8") as f:
            f.write("{not json")
        with self.assertRaises(ValueError):
            load_state(self.state_path)

    def test_to_inst_id(self):
        self.assertEqual(to_inst_id("BTC-USDT.CRYPTO"), "BTC-USDT")
        self.assertEqual(to_inst_id("BTC-USDT"), "BTC-USDT")
        with self.assertRaises(ValueError):
            to_inst_id("600519.SH")

    def test_fmt_size_avoids_scientific_notation(self):
        self.assertEqual(live._fmt_size(0.0123), "0.0123")
        self.assertEqual(live._fmt_size(1e-05), "0.00001")
        self.assertEqual(live._fmt_size(2.0), "2")


if __name__ == "__main__":
    unittest.main()
