# ADR-0019：美股数据源改用腾讯财经 —— 东财 K 线主机在部分网络不可达

- 状态：已接受（2026-11-11）
- 关联：ADR-0007（bar 统一存储）、ADR-0013（多市场 K 线）、ADR-0014（A 股回测规则）
- 落地：`data-service/.../market/TencentUsDataSource.java`、`.../UsSyncService.java`、`.../UsSyncController.java`、
  `trade-service/.../datasource/TencentDataSource.java`、`.../StockDataRouterService.java`

## 背景：不是「没有数据源」，是原来的源在这台机器上连不上

系统原本就有美股接入（`UsSyncService` + `EastMoneyUsDataSource`，`POST /data/us/sync`）。
实际排查发现它一直拿不到数据，原因不在代码：

| 探测 | 结果 |
| --- | --- |
| `searchapi.eastmoney.com`（东财 suggest） | 正常，能解析 `AAPL → 105.AAPL` |
| `push2his.eastmoney.com`（东财 K 线） | **TLS 直接被断**：curl `schannel: server closed abruptly`，Java HttpClient `EOFException`；A 股、美股一个样 |
| 腾讯 `qt.gtimg.cn` / `web.ifzq.gtimg.cn` | 正常，直连可用 |
| Yahoo chart API | 403（需要 cookie/crumb） |
| Stooq CSV | 返回 HTML（限流/反爬） |
| 新浪、腾讯实时行情 | 可用，但只有当日快照/分时，没有历史日线 |

「搜得到、拉不到」是最难归因的一种坏法：搜索成功让人以为配置没问题，日线那一步静默空数组。

## 决策

### 1. 美股默认源改为腾讯，东财保留为可选项

`us.market.source`：`tencent`（默认）/ `eastmoney`。两个源口径不同，**不做自动回退**——
自动回退会让同一张表里出现半段原始价、半段复权价，图上看着像断层，事后极难归因。
要换源就改配置再同步一次，同步本身是整段替换。

### 2. 美股 bar 存「前复权价」，不写 adj_factor

腾讯只提供前复权（`qfq`）：实测 `bfq` 与留空都返回空数组。所以：

- `bar` 里存的就是前复权收盘价，等价于把复权一次性做进了价格；
- **不产出 `adj_factor`**，`/market/bars?adjust=forward` 对美股不改变数值（因子缺失即原样返回）；
- 换源时先 `DELETE` 掉旧 bar 和旧因子再写入——留着东财时代的因子会把前复权价再缩放一次。

这与 ADR-0007「bar 存原始价 + 因子」在**美股**上是偏离，明确记录在此。A 股、加密不受影响。

### 3. 每次同步整段替换

腾讯单次最多 1200 根（传 2000 返回空数组，不报错），需要「上一页最早日期 - 1 天」往回翻页，
默认补 10 年。翻页 + 整段替换的好处是：不会留下「上次同步到一半」的残段。

### 4. 图表：美股周/月线走腾讯，分钟线维持不支持

`trade-service` 的看图链路原本只有东财一个源（`PRIMARY_SOURCE` 与 `SECONDARY_SOURCE` 都指向它，
备用分支是死代码）。现在按市场排序：

- 美股：`Tencent` → `EastMoney`
- A 股：只用 `EastMoney`（腾讯 A 股的复权口径未在此验证，接管会让老用户的图静默变样）

分钟线不接腾讯：`mkline` 对美股返回 `param error`，`usMinute` 只有当日分时——
硬接会给出「只有今天」的假分钟图，比空图更糟。

## 实测数据（2026-11-11）

| 项 | 结果 |
| --- | --- |
| `POST /data/us/sync-batch` 10 个标的 | 全部成功：AAPL/MSFT/NVDA/TSLA/GOOGL/AMZN/META/SPY/QQQ 各 3600 根，BABA 3055 根 |
| `bar` 区间 | AAPL.US：2012-07-18 ~ 2026-11-11（3 页翻页，`ts` = 交易日 09:30 美东） |
| `/market/bars?symbol=AAPL.US&period=1d` | 200，`meta.market=US`、`priceScale=2`、`tz=America/New_York` |
| `/data/stock/daily?stockCode=AAPL.US` | 200，`preClose`/`pctChg` 都有值（`preClose` 取上一根收盘） |
| `/stock/daily|weekly|monthly`（trade-service） | 日线读 bar；周/月线由腾讯返回（最早 2011 / 2007） |

## 后果与已知限制

- **美股分钟线仍然没有源**：东财断了、腾讯不提供历史分钟。要看分钟图得等有可用源。
- **A 股图仍只依赖东财**：东财 K 线主机不可达时，A 股日/周/月线会返回空（日线读 DB 的不受影响，
  但 `/stock/daily` 也走 Feign→data-service→bar，所以同步过的 A 股仍能看）。
- `adjust=none` 与 `adjust=forward` 对美股返回同一份数据（没有因子）。前端若显示「复权」开关，
  美股上它不改变数值。
- 腾讯是网页接口，字段位次（`日期,开,收,高,低,量`）与 1200 根上限都可能变；
  `TencentUsDataSourceTest` 用固定报文把位次与「高≥开收、低≤开收」钉住，改坏了会先炸测试。
