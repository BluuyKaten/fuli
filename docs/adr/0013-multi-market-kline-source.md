# ADR-0013：多市场 K 线取数口径 —— 周期映射、美股 secid、周期下发

- 状态：已接受（2026-11-11）
- 关联：ADR-0006（标的两段式）、ADR-0007（bar 统一存储与时间轴）、ADR-0008（OKX 市场）
- 依据：`docs/multi-market-rollout.md` 阶段 1（美股看图）
- 落地：`trade-service/.../datasource/EastMoneyDataSource.java`、`.../market/KlinePeriods.java`、`.../controller/KlineController.java`、前端 `LightweightChart.vue`

## 背景

K 线页走的是「trade-service → 东财实时接口」这条链路（不是 `bar` 表），它与 `/market/bars`
是两套口径：前者服务看图、要新鲜；后者服务回测与前端统一契约、要可复现。这条链路有两个
**已经存在但没被发现**的缺陷：

1. **周线/月线的 `klt` 整体错一档。** 东财 `klt` 是 `101=日、102=周、103=月`，
   而代码里周线传 `101`、月线传 `102`。用户点「周线」看到的是日线，点「月线」看到的是周线。
   这个错极难被发现：都有数据、形态也像，不看成交量周期根本认不出来。
2. **美股 K 线恒为空。** controller 先 `StockCodeUtil.pureCode(code)` 把 `AAPL.US` 削成 `AAPL`，
   数据源再拿 `marketOf("AAPL")` 猜市场——裸 `AAPL` 不是 6 位数字，直接判非法。
   即使不削，前缀也写死 `105`（NASDAQ），NYSE/AMEX 的票（BABA、SPY）会拿到 `data:null`。

实测证据（2026-11-11，直连东财）：

| 请求 | 结果 |
| --- | --- |
| `secid=1.600519&klt=101` | 逐日（11-09 / 11-10 / 11-11）→ 日线 |
| `secid=1.600519&klt=102` | 11-06 / 11-11 周五与当周 → 周线 |
| `secid=1.600519&klt=103` | 09-30 / 10-30 / 11-11 月末 → 月线 |
| `secid=105.AAPL&klt=101` | 正常返回「苹果」 |
| `secid=106.AAPL&klt=101` | `data:null`（**不报错**，rc=100） |
| `secid=106.BABA&klt=101` | 正常返回「阿里巴巴」 |
| `secid=107.SPY&klt=101` | 正常返回「标普500ETF-SPDR」 |
| `secid=105.AAPL&klt=1/5/15/30/60` | 均返回分钟线，最后一根 `05:00` |

## 决策

### 1. 数据源入参是完整符号，市场判断只走 `StockCodeUtil`

controller 不再 `pureCode`，改为 `StockCodeUtil.resolveOrNull` 校验后把完整符号透传；
数据源内部自己 `marketOf` + `pureCode`。

早先后缀在两处被各自处理（controller 削、datasource 猜），两边对「什么算合法」的理解不一致，
就出现了「A 股好的、美股全空」。**市场判断只能有一个答案来源**——这条在 ADR-0006 已经定过，
这里是它在数据源层的落实。

### 2. `klt` 用常量表达，单测钉住

`KLT_DAILY=101 / KLT_WEEKLY=102 / KLT_MONTHLY=103` 三个包级常量，`klineUrl()` 抽成可测方法，
`EastMoneyDataSourceTest` 断言 `klineUrl(..., KLT_WEEKLY, ...)` 含 `klt=102`。

为什么值得专门写测试：这个 bug 不会抛异常、不会空数据，只是「周线看起来像日线」。
靠人工回归发现不了，只能靠断言把数字和语义绑死。

### 3. 美股 secid 用 suggest 接口解析，不用 `instrument.exchange`

`https://searchapi.eastmoney.com/api/suggest/get?input=AAPL&type=14` 返回
`Classify`（UsStock）/ `Code` / `JYS` / `QuoteID`（`105.AAPL`），取 `QuoteID` 即可。

**为什么不查 `instrument.exchange`**（它是权威的交易所字段）：

- trade-service 访问不到 `data_db`，查它要加一次 Feign 往返；
- K 线是从东财实时拉的、不依赖 `bar` 表。用 `instrument` 会把「这只票没同步过」
  变成「这只票看不了图」——用户搜一个新代码想看走势，却被要求先同步数据，体验是断的。

解析结果按代码缓存 24 小时（交易所归属基本不变），上限 500 条，避免每根 K 线都打一次搜索。

两个过滤条件都不能省：`Classify=UsStock`（同一关键词会命中港股/基金/债券）、
`Code` 精确匹配（搜 `AAPL` 会带回 `AAPL.WS`、`AAPLL`，取首条会拿到错的交易所前缀）。
`pickUsQuoteId` 抽成静态纯函数就是为了把这两条钉在测试里。

### 4. 时间口径一律北京时间，不按标的时区解析

实测美股分钟线最后一根是 `05:00`，对应美东 16:00 收盘（冬令时 UTC-5）——**东财返回的是北京时间**，
不分市场。所以 `parseToTimestamp` 用 `Asia/Shanghai` 是正确口径，**不要**「顺手修正」成
`America/New_York`，那会把美股时间整体推后 13 小时。

代价是美股图上 22:30 开盘，与「09:30 开盘」的直觉不符。处理方式是**如实标注**而不是改数据：
周期下发接口带 `timezone` 字段，前端在周期按钮旁显示「时间：北京时间」（仅美股）。
把口径改掉会让「图上看到的时刻」与「东财网页/其他数据源」对不上，是更糟的选择。

### 5. 周期由服务端下发，并带 `kind`

`GET /stock/periods?code=AAPL.US` 返回：

```json
{ "market": "US", "defaultPeriod": "1D", "timezone": "Asia/Shanghai",
  "periods": [ {"value":"1","label":"1分","kind":"minute"}, ...,
               {"value":"1D","label":"日线","kind":"day"} ] }
```

前端原本用 `['1','5','15','60'].includes(period)` 猜「这是不是分钟线」，
后端加个 30 分周期前端就「点了没反应」。`kind` 把「该调哪个端点」变成数据而不是判断逻辑。

周期仍然由前端兜底一份：`/stock/periods` 挂了不能让整张图不可用，至少日线要能出。

## 后果

- 美股日/周/月/分钟全部可用（前提是东财覆盖该标的）；NYSE/AMEX 的票不再恒空。
- A 股周线/月线修正为真实周期，历史图上「周线的成交量为什么和日线一样」这类疑问消失。
- 每个新美股代码首次访问会多一次 suggest 往返（约 100–300 ms），之后命中缓存。
- 周期列表的真相在服务端，前端加周期不用改代码；但前端兜底列表仍在，
  两处不一致时以服务端为准（兜底只在接口失败时生效）。

## 已知限制

1. **`fqt=1`（前复权）对所有市场写死**。美股的复权由 data-service 的 `adj_factor` 承担，
   这条实时链路直接用东财的前复权，两者口径是否一致未做交叉验证。
2. **加密不走这条链路**（`/market/bars` 专属），`/stock/periods` 对 `CRYPTO` 返回空列表。
3. **分钟线的 5000 根上限**对 1 分钟线只覆盖约 13 个美股交易日，再往前翻不了。
4. 未做「按交易所区分交易时段」：美股盘前/盘后是否包含取决于东财，当前直接透传。
