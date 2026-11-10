# ADR-0008：OKX 加密行情接入与量化交易边界

- 状态：已接受（2026-11-05）
- 关联：ADR-0006（标的两段式）、ADR-0007（bar 统一存储与时间轴）
- 代码：`data-service/.../okx/`、`data-service/.../config/OkxConfig.java`

## 背景

阶段 0 把标的身份与行情存储换成了多市场底座，`market ∈ {SH, SZ, BJ, US, CRYPTO}` 已留好位，
但 CRYPTO 一直只有 `StockCodeUtil` 的符号校验，没有数据。本 ADR 定 OKX（V5 REST）作为首个加密数据源，
同时划清「行情」与「量化交易」的边界。

## 决策

### 1. 符号：OKX `instId` 原样 + `.CRYPTO`

```
BTC-USDT        →  BTC-USDT.CRYPTO
BTC-USDT-SWAP   →  BTC-USDT-SWAP.CRYPTO
```

ADR-0006 的示例写的是 `BTCUSDT.CRYPTO`（去横线），**本 ADR 取代该示例**，理由是：
去掉 `-` 后与 OKX 的映射不再可逆（`BTC-USDT` 与 `BTCUSDT` 无法区分，
`1INCH-USDT`、`USDT-USDC` 这类也会歧义），而符号是要长期存库的主键，宁可与源一致。
`StockCodeUtil` 的校验只禁止代码段里出现 `.`，所以带 `-` 的 instId 合法，无需改解析器。
`instrument.exchange` 记 `OKX`，用于区分同 market 下的其他交易所。

### 2. 周期：只映射 `bar.chk_bar_period` 允许的 9 个，其余拒绝

| 我们的 period | OKX bar | 说明 |
|---|---|---|
| `1m` | `1m` | |
| `5m` | `5m` | |
| `15m` | `15m` | |
| `30m` | `30m` | |
| `1h` | `1H` | **大写 H** |
| `4h` | `4H` | |
| `1d` | `1Dutc` | 见决策 3 |
| `1w` | `1Wutc` | |
| `1mo` | `1Mutc` | |

OKX 还有 `3m` / `2H` / `6H` / `12H` / `2D` / `3D` / `3M`，**不接**：`bar` 的 `CHECK` 只有 9 个取值，
加一个就要改表 + 改前端的周期枚举。需要时再走一次 ADR，不要偷偷放宽 CHECK。

大小写不是笔误：OKX 里 `1m` 是分钟、`1M` 是月；写成 `1h` 会被 OKX 拒（它认 `1H`）。

### 3. ts：一律用 `*utc` 变体，加密日线开在 00:00 UTC

OKX 的 `1D` 是「UTC+8 开盘」，`1Dutc` 是「UTC 开盘」。我们用 **`1Dutc`**，于是：

- 日线 `ts` = 当日 `00:00:00 UTC` 的 epoch 秒；
- 周线 = 周一 00:00 UTC；月线 = 当月 1 日 00:00 UTC。

这与 A 股的「交易日 01:30 UTC（09:30 CST）」不同，但**不违反 ADR-0007**：
ADR 要求的是「ts 是 UTC 秒、且同一周期内 ts 稳定」，具体落在当天哪个时点由市场交易时段决定。
A 股用 09:30 CST 是为了让「日期」在 Asia/Shanghai 下唯一；加密 24H 交易，用 00:00 UTC 才自然。

> **顺带修掉一个真 bug**：`MarketBarsService.dayStart/dayEndExclusive` 之前硬编码
> `Asia/Shanghai` 把 `from/to` 转成 ts。对 A 股正确，对加密会把窗口整体挪 8 小时
> （`2026-11-05` 变成 UTC 前一天 16:00 起）。现在改为按 `instrument.tz` 换算，
> A 股行为不变（其 tz 本来就是 `Asia/Shanghai`），加密用 `UTC`。

### 4. 单位：不做任何倍数换算

A 股的 `×100`（手→股）、`×1000`（千元→元）是 Tushare 口径特有的，**加密一律不乘**：

| 列 | 取值 | 含义 |
|---|---|---|
| `volume` | 第 5 列 `vol` | 基础币数量（BTC 的个数） |
| `amount` | 第 7 列 `volCcyQuote`（空则第 6 列 `volCcy`） | 计价币成交额（USDT） |

现货 `vol` 就是基础币，合约 `vol` 是张数——约定只接 `SPOT`（`instType=SPOT`），
合约口径不同，等真要做时单独定，不要混进同一列。

### 5. 只落「已完结」的 K 线

K 线第 9 列 `confirm`：`0` = 未完结（价格还在跳），`1` = 已完结。
**只写 `confirm=1`**，否则最后一根会被反复改写，且 `bar` 是事实表，
写进去的应该是「已经定格的 OHLC」，不是瞬时快照。代价是实时视图要靠前端/WS，这符合 ADR-0007
「bar 存事实、复权与实时是视图」的分工。

### 6. 拉取策略：REST 回补 + 定时增量，先不上 WS

两个端点分工（OKX 自己就是这么切的）：

| 端点 | 数据范围 | 单次上限 | 限频 |
|---|---|---|---|
| `GET /api/v5/market/candles` | 近 1440 根 | 300 | 40 次/2s（按 IP） |
| `GET /api/v5/market/history-candles` | 数年 | 100 | 20 次/2s（按 IP） |

分页语义反直觉，必须记住：**`after` 取更早、`before` 取更新**，两者不能同时传，
且返回是**时间倒序**。往回翻页时取本批最旧一条的 ts 减 1ms 作为下一次 `after`，
减 1ms 是为了不让边界那根重复出现。

WebSocket（`wss://ws.okx.com/ws/v5/business` 的 `candle*` 频道）留到做实时盘口/推送时再接，
现阶段定时轮询足够，且少一个长连接要维护。

> **2026-11-09 更正（ADR-0009）**：原文写的是 `wss://ws.okx.com:8443/...`。
> 8443 端口已宣布下线并迁至 443，正确地址不带端口；模拟盘 WebSocket 另用
> `wss://wspap.okx.com/ws/v5/{public,private,business}`，与实盘不同域名。
> 同时，本决策「先不上 WS」及决策 7 的交易边界已被 ADR-0009 取代，WS 全频道纳入范围。

### 7. 量化交易：独立开关、默认关闭、默认模拟盘

交易与行情在代码上分开（`OkxTradeService` vs `OkxMarketService`），且有三道闸：

1. `okx.trade.enabled` 默认 `false` —— 关着时下单接口直接拒绝，不是「静默失败」；
2. ~~`okx.trade.simulated` 默认 `true` —— 走 `x-simulated-trading: 1`，**生产要显式关掉它**~~
   —— **已被 ADR-0009 决策 4 取代**：模拟盘现为系统常量，不存在关闭开关，实盘凭证不进 Java 服务；
3. 只允许 `tdMode=cash` 的 SPOT 单 —— 杠杆/合约/借币不在本期范围。

理由：模拟盘与实盘是**同一个 host**，只差一个 header（`x-simulated-trading`）。
这是最容易出事的配置形态——header 一旦被代理或重试逻辑丢掉，请求就落到实盘。
所以默认值必须是「模拟 + 关闭」，让上线是一个需要人主动做的动作，而不是默认结果。

签名口径（OKX 特有，四个头，少一个都报 50113）：

```
sign = Base64( HMAC-SHA256( timestamp + method + requestPath + body, secretKey ) )
header: OK-ACCESS-KEY / OK-ACCESS-SIGN / OK-ACCESS-TIMESTAMP / OK-ACCESS-PASSPHRASE
```

`requestPath` 要带 query（`/api/v5/account/balance?ccy=BTC`），body 用紧凑 JSON，
`timestamp` 是 ISO-8601 毫秒 UTC。**passphrase 是第四个凭证**，不是 secret，也不能复用。

## 后果

- `bar` 里第一次出现 `ts % 86400 == 0` 的行（加密日线）。任何假定「所有 ts 都是 01:30 UTC」的
  校验/脚本都要改成按 market 判断——`V7__verify_adj_factor.sql` 的 A3 目前就是这种断言，
  它只扫 `adj_factor`（A 股），暂时不受影响，但后续若给加密加因子必须重写该断言。
- **验收脚本必须按市场分域**（实测 2026-11-07）：加密行进 `bar` 后，
  `V7__verify_bars.sql` 的 V1/V2/V3/V6/V7 与 `V7__verify_adj_factor.sql` 的 A4/A4b/A4c
  这类「全表聚合」全部误报（V1 多 897 行、V2/V3 混入 USDT 金额、V6 末根变 00:00 UTC、
  V7 的 NULL 从 32 变 929、A4c 把 897 根加密行算成缺因子）。已统一加谓词
  `instrument_code REGEXP '^[0-9]{6}\\.(SH|SZ|BJ)$'`。以后每接一个新市场，第一件事就是复查这些脚本。
- **符号列宽只剩 0 余量**：`instrument.code` / `bar.instrument_code` / `adj_factor.instrument_code`
  都是 `varchar(20)`，而 OKX 最长 SPOT instId 是 13 字符（BABYDOGE-USDT / XPOPMART-USDT），
  加 `.CRYPTO` 正好 20。`syncInstruments` 已加护栏：超长就跳过并 WARN，
  不去偷偷截断；真要放宽得重建 7.67M / 16.8M 行两张大表，属单独一次迁移。
- `instrument.lot_size` 对加密存 OKX 的 `lotSz`（如 `0.00000001`），语义是「下单数量精度」，
  不是「一手多少股」。前端若拿它算「手数」会得到荒谬结果，展示层要按 market 分支。
- 周期只有 9 个是硬约束，OKX 的 3m/6H/12H 等需求会以「新增周期」的形式回来，
  届时是改 `CHECK` + 前端枚举 + 缓存 key，属跨层改动，必须走 ADR 而不是顺手加。
