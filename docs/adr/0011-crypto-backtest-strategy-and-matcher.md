# ADR-0011：加密回测引擎 —— 策略接口与撮合口径

- 状态：已接受（2026-11-10）
- 关联：ADR-0006（标的两段式）、ADR-0007（bar 统一存储与时间轴）、ADR-0008（OKX 加密数据源）
- 依据：`docs/multi-market-rollout.md` 阶段 2（加密回测），尤其 2.3/2.4/2.5 与「已知风险」第 2 条
- 落地：`backtest/`（Python，纯标准库）、`data-service` 加密行情接入（`CryptoDataSource` 族）

## 背景

阶段 2 的目标是「给一段历史，出一张净值曲线」，不碰真钱、不常驻。`multi-market-rollout.md:116` 明确要求：**撮合口径（次根开盘 / 滑点 / 费率）必须在看任何回测结果之前定死，否则会不自觉地调参数拟合历史**。本 ADR 在动手写引擎前把「策略接口」「撮合」「指标」「确定性」四件事一次定死，作为回测结论可信度的基线。

## 决策

### 1. 数据源与落库：Binance Vision 主 + Gate 备选，落 `bar` 表

- 主源 **Binance Vision**（`data.binance.vision`，公共历史数据镜像，月线 zip），备选 **Gate**（`api.gateio.ws` 现货 K 线）。两者都是公开 GET 端点，不需要任何凭证。
- 落 `bar` 表（`data_db`），周期 `1d` 与 `4h`；`ts` = bar 开盘时刻，**00:00 UTC**（加密 24H 交易，日线自然开在 UTC 零点）。
- 符号用 `BTC-USDT.CRYPTO`（ADR-0006/0008 决策 1 的两段式 + 连字符）。
- `adj_factor` 恒为 1：加密无复权，**不写行**（`applyForward` 对无因子标的原样返回，天然兼容）。
- 只同步**清单内**标的（不拉全市场），清单手工维护，与 `multi-market-rollout.md` 阶段 1.2 的口径一致。

### 2. 撮合口径：次根开盘、滑点 bps、费率 maker/taker、不做部分成交

- **成交时点**：策略在 bar `i` 收盘后产生信号，订单在 **bar `i+1` 开盘价**成交（次根开盘）。信号当根不成交——这是回测与图上所见一致的关键：决策只看已走完的 K 线。
- **成交价**：市场单。买入 `open[i+1] × (1 + slippage)`，卖出 `open[i+1] × (1 − slippage)`，`slippage` 以 bps 参数化（默认 0）。
- **费率**：`maker_rate` / `taker_rate` 两个独立参数，按订单类型取用（当前只有市场单 → taker）。费用 = 成交金额 × 费率，从 quote 现金扣。
- **不做部分成交**：订单要么整单成交、要么整单拒绝。买入现金不足、卖出持仓不足 → 整单拒绝，不产生任何持仓/现金变化，也不记为「部分成交」。阶段 2.4 明确「不做部分成交」，与阶段 3/实盘的分段撮合不同。
- **基础币/计价币**：`size` 是 base 数量（如 BTC），现金是 quote（USDT）。最小下单步长由标的 `lot_size` 约束（引擎传入 `lot_size`，买入数量向下取整到步长）。

### 3. 指标定义

| 指标 | 定义 |
| --- | --- |
| 权益 | `equity[t] = cash + position × close[t]`（每根 bar 收盘后计一条） |
| 净值 | `nav[t] = equity[t] / initial_cash` |
| 最大回撤 | `max over t of (1 − equity[t] / peak[t])`，`peak` 从 `initial_cash` 起步、随权益创新高更新 |
| 总收益 | `(equity[end] − initial_cash) / initial_cash` |
| 胜率 | 盈利的**平仓往返**次数 / 总平仓往返次数（一次往返 = 建仓到清仓；期末未平仓不计入） |
| 获利因子 | 盈利往返的总盈利 / 亏损往返的总亏损（无亏损往返时记 `∞`，调用方自行展示） |

### 4. 策略接口：纯函数、确定性、回测与实盘共用

```python
@dataclass(frozen=True)
class Bar: ts: int; open: float; high: float; low: float; close: float; volume: float

@dataclass(frozen=True)
class Order: side: str; size: float  # side ∈ {BUY, SELL}, size = base 数量(>0)

@dataclass
class Context:
    index: int          # 当前 bar 下标（可见历史为 bars[0..index]）
    bars: tuple         # 冻结的可见历史（不含未来）
    cash: float
    position: float     # base 持仓数量
    equity: float

class Strategy(ABC):
    def on_bar(self, ctx: Context) -> Optional[Order]: ...
```

约束：

1. **策略看不到未来**：`ctx.bars` 只含 `[0..index]`。
2. **纯函数**：不读系统时钟、不读网络、不写文件、不依赖 dict/set 迭代顺序（历史用 tuple 保序）。同一输入必须产出同一信号。
3. **确定性**：`nav` 序列对同一份 `bars` + 同一参数**逐位相同**（见决策 5）。

### 5. 确定性保证：逐位相同

- 引擎只依赖标准库（无 pandas/numpy 的浮点差异来源），资金运算用 `float`，但**每笔成交后金额 `round(8)`**（与 `bar` 的 `DECIMAL(20,8)` 一致），消除累加漂移。
- 不引入随机、不读墙钟、不在任何地方用 `id()`/对象地址做比较或排序。
- 验收：同一份策略、同一段历史，跑两次得到**逐位相同**的 `nav` 序列（`multi-market-rollout.md:61` 的完成标志）。

### 6. 范围边界（本阶段不做）

- 不做部分成交、不做限价单、不做挂单队列、不做资金费率/杠杆/合约。
- 不做滑点模型拟合（滑点是一个固定 bps 参数，不是随机分布）。
- 不做跨币种资产合并（¥ 与 USDT 分开展示，`multi-market-rollout.md`「明确不在范围内」）。

## 后果

- 回测结论口径固定，后续任何「优化」若改变撮合口径，必须先改本 ADR，否则回测前后不可比。
- 阶段 3 半自动/实盘共用同一份 `Strategy` 代码时，撮合器会换成交易所真实成交，但策略信号接口不变——这是 2.5「回测和实盘共用同一份策略代码」的兑现路径。
