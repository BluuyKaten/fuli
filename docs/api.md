# fuli 后端接口清单

面向「要对接这个后端的人」。按模块分节，每节先说明这个模块负责什么、网关前缀是什么，
再列出端点。

路径一律写**服务内路径**（网关 stripPrefix(1) 之后的形态）。对外调用时在前面拼上网关前缀，
例如 `POST /auth/login` → `POST http://localhost:8080/api/auth/login`。

端点来源：从 22 个 Controller 的 `@RequestMapping` + `@Get/Post/Put/DeleteMapping` 提取，
说明文字按代码实际行为补写。**没有引入 springdoc/swagger**：7 个模块加依赖 + 配置的改动面，
对个人项目来说收益不匹配。

---

## 0. 通用约定

### 0.1 服务与网关前缀

| 模块 | 端口 | 网关前缀 | 职责 |
| --- | --- | --- | --- |
| gateway-service | 8080 | — | 统一入口：JWT 校验、限流、traceId、剥离伪造身份头 |
| auth-service | 8081 | `/api/auth` | 登录注册、用户资料、自选股、服务令牌、现金账户 |
| trade-service | 8082 | `/api/trade`、`/api/stock` | 交易记录、持仓、自选、K 线看图、预警、复盘笔记、历史练习 |
| analysis-service | 8083 | `/api/analysis` | 盈亏统计、月度盈亏、资产曲线、绩效分析（不直连数据库） |
| data-service | 8084 | `/api/data`、`/api/market`、`/api/okx/mcp` | 行情同步（Tushare/东财/加密）、统一 bar 契约、OKX 接入 |
| ai-service | 8085 | `/api/ai` | 规则版 Agent 编排、只读 OKX 视图、下单建议 |
| common-api | — | — | 公共依赖，无独立进程 |

### 0.2 鉴权（本文件「鉴权」列的词表）

| 词 | 含义 |
| --- | --- |
| **无** | 网关白名单，直接放行。当前只有 `/auth/login`、`/auth/register`（以及不存在的 `/auth/captcha`） |
| **用户 JWT** | `Authorization: Bearer <token>`，令牌 24h，claims 里带 `userId` |
| **用户 JWT + 交易权限** | 还要求 `sys_user.okx_trade_enabled = 1` 且全局总闸 `okx.trade-enabled=true` |
| **服务令牌** | `client_credentials` 换来的令牌（`type=SERVICE`，`aud` 必须匹配本服务，TTL 15 分钟） |
| **内部密钥** | 请求头 `X-Internal-Key`，服务间调用专用；**网关会拒绝任何带 `internal` 路径段的请求** |

网关行为（`JwtGlobalFilter`）：

- 白名单之外一律验签；验签通过后**注入** `X-User-Id`/`X-Username` 并**剥离**客户端自带的同名头。
- 路径任一 segment 等于 `internal` → 直接 403。外部调用根本到不了内部接口。
- 限流：已认证按 userId（默认 20 令牌/秒，突发 60），登录 1/秒、注册 0.5/秒，超限 429 + `Retry-After`。

服务侧不信任转发头：auth / trade / analysis / ai 各自再验一遍签名（`BearerUserFilter` 及其子类），
data-service 的 `/data/okx/**` 与 MCP 路径由 `OkxAuthenticationFilter` 自己验签。
例外是 data-service 的行情与同步路径（`/data/stock/**`、`/data/tushare/**`、`/data/crypto/**`、
`/market/**`）：**它们只靠网关把关**——本表按「经网关调用」标注鉴权，内网直连 8084 这些路径不会再验签。

### 0.3 响应包装

除个别历史遗留端点，统一返回：

```json
{ "code": 200, "message": "success", "data": ... }
```

`code` 是业务码，通常与 HTTP 状态码一致。已知例外：**`/data/stock/**` 全部直接返回裸对象**
（`List` / `Map`，不包 `Result`），因为它是 Feign 契约；改成包装会同时打断 data-service 的调用方。

### 0.4 标的口径

- A 股：`600519.SH` / `000001.SZ` / `430047.BJ`。裸 6 位会按首位补后缀，但**建议始终带后缀**。
- 美股：`AAPL.US`、`BABA.US`（后缀必须带，裸 `AAPL` 会 400）。
- 加密：`BTC-USDT.CRYPTO` 或裸 `BTC-USDT`。
- 日期参数：`yyyy-MM-dd`（多数同步接口用 `yyyyMMdd`，各自标注）。
- K 线时间：**东财对美股也返回北京时间**，美股图需在 UI 上标注「时间：北京时间」，
  不要按 `America/New_York` 再转一次（会整体推后 13 小时）。

---

## 1. auth-service（8081，网关前缀 `/api/auth`）

负责身份与账户基础数据：登录签发 JWT、用户资料、自选股、客户端凭证换服务令牌，
以及供其它服务调用的现金账户内部接口（`sys_user` 归它所有，别的服务不直连 auth_db）。

### 1.1 认证 `AuthController`

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| POST | /auth/login | 无 | 登录，返回 JWT（24h）+ 用户信息 |
| POST | /auth/register | 无 | 注册，返回新用户 id |
| POST | /auth/logout | 用户 JWT | 把令牌 `jti` 写入 Redis 黑名单，立即失效（Redis 不可用时 fail-open） |
| GET | /auth/profile | 用户 JWT | 当前用户资料 |
| PUT | /auth/profile | 用户 JWT | 更新资料（昵称、头像等） |
| PUT | /auth/password | 用户 JWT | 改密：`{"oldPassword","newPassword"}`，新密码至少 6 位 |

> 非 `local` profile 启动时会拒绝「已知默认管理员密码」，所以初始化库里的 `admin` 必须先改密。

### 1.2 自选股 `WatchlistController`

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| GET | /auth/watchlist | 用户 JWT | 当前用户自选股列表 |
| POST | /auth/watchlist | 用户 JWT | 添加：`{"stockCode","stockName"}` |
| DELETE | /auth/watchlist/{stockCode} | 用户 JWT | 按代码删除 |

> userId 一律取自认证上下文，body 里带 userId 无效。

### 1.3 服务令牌 `ServiceTokenController`

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| POST | /auth/oauth/token | 无（`client_id`+`client_secret`） | `grant_type=client_credentials`，表单参数：`client_id`/`client_secret`/`scope`（可选）。返回 `access_token`/`token_type`/`expires_in`/`scope` |

- 申请的 `scope` 必须是该客户端已授权集合的子集，越权返回 `invalid_scope`（不回显允许哪些）。
- 令牌带 `aud`，data-service 会校验受众——**给别的服务签的令牌在这里不认**（禁止 token passthrough）。

### 1.4 内部接口（见第 8 节）

`/auth/internal/{deductCash,addCash,userCash,okxTradeEnabled,userInitialCash,resetCash}`，内部密钥。

---

## 2. trade-service 交易（8082，网关前缀 `/api/trade`）

负责交易记录、持仓、预警、复盘笔记和历史练习。它是 `trade_db` 的唯一属主，
analysis-service 的所有账目数据都经由它（走 `/trade/internal/**`）。

### 2.1 交易记录 `TradeRecordController`

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| POST | /trade | 用户 JWT | 新增交易。买入同步扣可用资金，不足直接拒单（不落库）；卖出按 T+1 校验可卖数量 |
| PUT | /trade/{id} | 用户 JWT | 更新（仅本人记录） |
| DELETE | /trade/{id} | 用户 JWT | 删除并回滚持仓/资金。**只允许删某股票的最后一笔**，否则 400 |
| GET | /trade/{id} | 用户 JWT | 单条详情（仅本人） |
| POST | /trade/list | 用户 JWT | 条件查询（body 为 `TradeQueryDTO`） |
| POST | /trade/page | 用户 JWT | 分页（`pageNum`/`pageSize`），按 `trade_date` 倒序 |
| POST | /trade/queryByCondition | 用户 JWT | 与 `/trade/list` 同实现，历史命名 |
| POST | /trade/statistics | 用户 JWT | 区间统计（买卖笔数、金额、盈亏、胜率），单条 SQL 聚合 |

`TradeQueryDTO` 常用字段：`stockCode`、`tradeType`（1 买 / 2 卖）、`startDate`、`endDate`。
**`/trade/list` 与 `/trade/queryByCondition` 不带 `ORDER BY`，不要依赖返回顺序**。

### 2.2 价格预警 `PriceAlertController`

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| POST | /trade/alert | 用户 JWT | 新建预警（价格止盈/止损/涨跌幅）。返回 id |
| PUT | /trade/alert/{id} | 用户 JWT | 更新阈值等 |
| DELETE | /trade/alert/{id} | 用户 JWT | 删除 |
| PUT | /trade/alert/{id}/status?status= | 用户 JWT | 启停（1 启用 / 0 停用） |
| GET | /trade/alert/list?status= | 用户 JWT | 列表，`status` 可空 |
| POST | /trade/alert/check | 用户 JWT | 立即执行一次判定，返回本次触发列表（前端仪表盘用） |

### 2.3 复盘笔记 `TradeJournalController`

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| POST | /trade/journal | 用户 JWT | 新建笔记（买卖逻辑、情绪 1-5、策略分类、反思） |
| PUT | /trade/journal/{id} | 用户 JWT | 更新 |
| DELETE | /trade/journal/{id} | 用户 JWT | 删除 |
| GET | /trade/journal/{id} | 用户 JWT | 详情 |
| POST | /trade/journal/page | 用户 JWT | 分页查询（`TradeJournalQueryDTO`） |
| GET | /trade/journal/calendar-summary?yearMonth= | 用户 JWT | 月度足迹（每天几篇、盈亏），`yearMonth` 形如 `2026-11` |

### 2.4 历史交易练习 `PracticeController`

逐根揭晓的盲练：创建时冻结整段区间的 K 线快照，之后每次操作只改决策状态。

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| POST | /trade/practice | 用户 JWT | 创建练习：`{"stockCode","startDate","endDate","initialCash"}`。起点前最多 120 根背景日线，区间不超过 5 年 |
| GET | /trade/practice | 用户 JWT | 我的练习列表（只读摘要列，不解析大字段） |
| GET | /trade/practice/{id} | 用户 JWT | 详情（仅本人，否则 404） |
| POST | /trade/practice/{id}/orders | 用户 JWT | 挂单（次日开盘撮合）：`{expectedVersion, side, quantity}`，100 股整数倍 |
| POST | /trade/practice/{id}/cancel-order | 用户 JWT | 撤销待成交单 |
| POST | /trade/practice/{id}/advance | 用户 JWT | 揭晓下一日并按开盘价撮合 |
| POST | /trade/practice/{id}/finish | 用户 JWT | 提前结束，转入复盘（剩余持仓只估值、不强制卖出） |
| PUT | /trade/practice/{id}/annotations | 用户 JWT | 保存画线/手工买卖点，不能含未来日期 |

> **`expectedVersion` 必填且必须等于当前版本**，否则 409。这是乐观锁，也是「重复点击不会揭晓两根 bar」的保证。
> 快照在创建后不可变，服务端按会话缓存它；若将来支持「续期/延长区间」，必须同事务清缓存。

---

## 3. trade-service 行情（8082，网关前缀 `/api/stock`）

两条链路要区分清楚：

- `/api/stock/{minute,weekly,monthly,periods,quote}` → trade-service 直连**东财实时接口**，服务「看图」，要新鲜。
- `/api/market/bars`（第 5.1 节）→ data-service 读**入库 bar**，服务回测与统一契约，要可复现。

### 3.1 基础与日线 `StockController`

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| GET | /stock/search?keyword= | 用户 JWT | 按代码或名称模糊搜索 |
| GET | /stock/info?stockCode= | 用户 JWT | 标的基础信息（含 `marketCode`：SH/SZ/BJ/US/CRYPTO） |
| GET | /stock/daily?stockCode=&startDate=&endDate=&limit= | 用户 JWT | 日线。**`limit` 为空表示整段区间**——资产曲线依赖这一点，截断会缺日 |
| GET | /stock/daily/sparkline?stockCode=&points=240 | 用户 JWT | 全历史收盘价缩略序列（LTTB 降采样），供 K 线底部全貌条 |
| GET | /stock/latest-price?stockCode= | 用户 JWT | 最新价 + 前收盘 |
| GET | /stock/latest-prices?stockCodes=a,b,c | 用户 JWT | 批量最新价，消除 N+1。返回 key 保留调用方传入的原值 |
| GET | /stock/infos?stockCodes=a,b,c | 用户 JWT | 批量基础信息 |

### 3.2 K 线与周期 `KlineController`

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| GET | /stock/minute?code=&period=5 | 用户 JWT | 分钟线，`period` ∈ 1/5/15/30/60 |
| GET | /stock/weekly?code= | 用户 JWT | 周线（东财 `klt=102`） |
| GET | /stock/monthly?code= | 用户 JWT | 月线（东财 `klt=103`） |
| GET | /stock/periods?code= | 用户 JWT | 该标的可用周期：`{market, defaultPeriod, timezone, periods[{value,label,kind}]}`。前端不要再用 `['1','5','15','60'].includes()` 猜端点，用 `kind` |
| GET | /stock/quote?code= | 用户 JWT | 行情快照（含 5 档）。优先实时快照表；表为空时退化为最新日线收盘价 |
| POST | /stock/drawing | 用户 JWT | 保存画线（`{code, period, payload}`） |
| GET | /stock/drawing?code=&period=1D | 用户 JWT | 读取画线 |

**`code` 必须是完整符号**（`600519.SH` / `AAPL.US`）。裸 `AAPL` 会 400——早先 controller 会先
`pureCode` 把 `.US` 削掉，数据源只能猜市场，结果美股 K 线恒为空。

`/stock/periods` 的 `timezone` 对 A 股和美股都是 `Asia/Shanghai`：东财统一返回北京时间。

### 3.3 持仓辅助 `StockController`

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| GET | /stock/holding?stockCode= | 用户 JWT | 当前用户该股持仓数量（K 线图卖出时显示） |
| GET | /stock/available-quantity?stockCode= | 用户 JWT | 可卖数量（A 股 T+1），含总持仓/冻结/市场规则 |
| GET | /stock/fix-holding | 用户 JWT | 按交易记录重算持仓（修历史数据不一致） |

> 这三个端点签名里有 `userId` 参数，但**会被服务端用令牌身份覆盖**，传别人的 id 无效。

---

## 4. analysis-service（8083，网关前缀 `/api/analysis`）

不直连任何数据库，全部通过 Feign 调 trade-service（服务令牌/内部密钥）。结果是短 TTL
Caffeine 缓存（不同时间区间是不同 key）。

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| GET | /analysis/statistics?stockCode=&startDate=&endDate= | 用户 JWT | 区间统计，直接转发 trade-service 的 SQL 聚合 |
| GET | /analysis/monthly-profit?startDate=&endDate= | 用户 JWT | 月度盈亏，转发 `POST /trade/internal/monthly-profit`（`GROUP BY 月份`，N 笔交易 → M 行） |
| GET | /analysis/asset-curve?startDate=&endDate= | 用户 JWT | 资产曲线 `{dates[], assets[]}`，逐日「现金 + 持仓市值」，需要每只票的日线 |
| GET | /analysis/dashboard | 用户 JWT | 仪表盘聚合（资金、持仓、当日盈亏、月度、曲线） |
| GET | /analysis/performance?startDate=&endDate= | 用户 JWT | 多维绩效：胜率、盈亏比、获利因子、连胜连亏、持仓周期、收益率分布、个股龙虎榜 |

口径提醒：

- 月度盈亏只统计 `profit_loss` 非空的交易（买入行不计入笔数与胜率分母）；胜率 = 盈利笔数/笔数，4 位小数 HALF_UP。
- 资产曲线的持仓估值优先用当日收盘价；当天没有 K 线（周末/节假日/停牌）时沿用**最近一个已知收盘价**，只有连一根历史收盘价都没有才退回平均成本。
- `/analysis/performance` 的买卖配对是先进先出，属于顺序依赖的状态机，**不要指望和 SQL 聚合结果对得上**。

---

## 5. data-service 行情与同步（8084）

`data_db` 的唯一属主：`instrument`（标的主数据）、`bar`（统一 K 线）、`adj_factor`、OKX 相关表。
上游有 Tushare（A 股）、腾讯财经（美股日线，默认）、东财（美股/A 股实时，部分网络不可达）、
Binance Vision + Gate（加密）、OKX（加密）。

### 5.1 统一 K 线契约 `MarketBarsController`（网关前缀 `/api/market`）

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| GET | /market/bars?symbol=&period=&from=&to=&limit=&adjust= | 用户 JWT | 从 `bar` 表读 K 线，返回 `{symbol, period, adjust, meta{name,market,currency,priceScale,lotSize,tickSize,tz}, bars[{time,open,high,low,close,volume,amount}]}` |

坑（这个端点的参数语义最容易理解错）：

- `period` 受控集合：`1m/5m/15m/30m/1h/4h/1d/1w/1mo`，其它值 400。
- `limit` 有两种含义：**不传区间时**是「最近 N 根」（默认 620，上限 5000）；
  **传了 `from`/`to` 时**是「区间内最近 N 根」（即从区间末端往前截）。
- `adjust`：`none`（默认）/ `forward`。复权只影响返回价格，不改库里的原始 bar。
- 未知标的返回 **404**（不是空数组），参数不合法返回 **400 + 中文原因**。

### 5.2 股票行情 `StockDataController`（网关前缀 `/api/data`）

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| GET | /data/stock/search?keyword= | 用户 JWT | A 股查 `stock_info`，美股/加密查 `instrument` |
| GET | /data/stock/daily?stockCode=&startDate=&endDate=&limit= | 用户 JWT | 日线（裸 6 位自动补后缀，非法符号 400） |
| GET | /data/stock/daily/sparkline?stockCode=&points=240 | 用户 JWT | 全历史收盘价降采样 |
| GET | /data/stock/info?stockCode= | 用户 JWT | 基础信息 |
| GET | /data/stock/latest-price?stockCode= | 用户 JWT | 最新价 |
| GET | /data/stock/latest-prices?stockCodes= | 用户 JWT | 批量最新价（一次 IN） |
| GET | /data/stock/infos?stockCodes= | 用户 JWT | 批量基础信息 |

> 这一组**返回裸对象**（不包 `Result`），是 trade-service / analysis-service 的 Feign 契约。

### 5.3 Tushare 同步 `TushareController`（网关前缀 `/api/data`）

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| POST | /data/tushare/sync/stock-basic | 用户 JWT | 拉股票基础信息 |
| POST | /data/tushare/sync/daily | 用户 JWT | 单只票日线：`{tsCode,startDate,endDate}` |
| POST | /data/tushare/sync/daily-by-date | 用户 JWT | 某个交易日全市场日线：`{tradeDate}` |
| POST | /data/tushare/sync/all-incremental | 用户 JWT | 增量同步（从库里最新日期往后补） |
| POST | /data/tushare/sync/daily-by-date-range | 用户 JWT | 自定义区间异步同步，返回 `taskId`，进度走 SSE |
| GET | /data/tushare/sync/progress/{taskId} | 用户 JWT | SSE 实时进度 |
| GET | /data/tushare/sync/progress/{taskId}/status | 用户 JWT | 轮询进度（不支持 SSE 的客户端用） |
| GET | /data/tushare/sync/last-failures | 用户 JWT | 上次同步失败的交易日及原因 |
| POST | /data/tushare/sync/retry-failures | 用户 JWT | 重试指定交易日：`{tradeDates:[...]}` |
| GET | /data/tushare/sync/status?tsCode= | 用户 JWT | 单只票的同步状态（已同步到哪一天） |

> 需要 `TUSHARE_TOKEN`。未配置不影响登录与已有数据查询，只是同步接口报错。

### 5.4 多市场同步（网关前缀 `/api/data`）

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| POST | /data/crypto/sync | 用户 JWT | 加密 K 线：`{symbol, interval(默认 1d), from, to}`。Binance Vision 主源 + Gate 备用 |
| POST | /data/us/sync | 用户 JWT | 美股日线：`{symbol:"AAPL.US"}`，返回落库根数。默认源腾讯（前复权，约 14 年），`us.market.source=eastmoney` 可切回东财（原始价 + 因子） |
| POST | /data/us/sync-batch | 用户 JWT | 批量同步：`{symbols:["AAPL.US","MSFT.US"]}` 或 `{symbols:"AAPL.US,MSFT.US"}`，一次最多 30 个。逐标的返回 `{symbol, success, count \| message}`，一只失败不影响其余 |
| GET | /data/us/source | 用户 JWT | 当前生效的美股数据源（`tencent` / `eastmoney`），排障先看这个 |

> **美股口径**：腾讯只有前复权（`qfq`），所以 `bar` 存的就是前复权价，不产出 `adj_factor`——
> `adjust=forward` 对美股不改变数值。每次同步都是**先删后插**（换源时两种口径不会混在一条序列里）。
> 分钟线没有美股源：东财 K 线主机在部分网络不可达，腾讯不提供历史分钟线。详见 `docs/adr/0019-us-market-data-source.md`。

---

## 6. data-service OKX（网关前缀 `/api/data`）

系统只接 **OKX 模拟盘**：`OkxConfig.isSimulated()` 是常量，私有请求恒定带 `x-simulated-trading: 1`，
提币永不开启。凭证按用户维度加密存 `okx_account`（AES-256-GCM），代码里没有全局 Key。

三档 scope（`OkxAuthz`）：

| scope | 要求 |
| --- | --- |
| `orders:read` | 有效令牌；服务账号需带 `orders:read` scope |
| `orders:write` | 用户令牌 + `okx_trade_enabled=1` + 全局总闸 + 已录凭证；**服务账号一律 403** |
| `orders:credential` | 用户令牌即可（先配 Key 才谈得上开权限，否则死锁）；服务账号 403 |

### 6.1 同步与状态 `OkxController`

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| POST | /data/okx/sync/instruments | 用户 JWT + 交易权限 | 同步 SPOT 标的基础信息（写 `instrument`，market=CRYPTO） |
| POST | /data/okx/sync/candles | 用户 JWT + 交易权限 | 拉某标的某周期最新一页：`{symbol, period}` |
| POST | /data/okx/sync/all | 用户 JWT + 交易权限 | 按配置（`okx.symbols × okx.bars`）全量刷一遍 |
| POST | /data/okx/backfill | 用户 JWT + 交易权限 | 往回补历史：`{symbol, period, days(30), maxPages(20)}` |
| GET | /data/okx/status | 用户 JWT / 服务令牌(orders:read) | 配置 + 四道闸 + **本人**凭证状态。部署后先看这个 |

> `status.credentialConfigured` 是「我这个账号配了 Key 没有」，不是「服务端有没有全局 Key」——后者已不存在。

### 6.2 账户与凭证 `OkxAccountController`

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| POST | /data/okx/account | 用户 JWT（credential scope） | 录入/更新自己的模拟盘 Key：`{label, apiKey, secretKey, passphrase}`。响应只回 `apiKeyMasked` |
| GET | /data/okx/account | 用户 JWT（credential scope） | 列出自己的凭证（已掩码） |

### 6.3 交易（默认关闭）`OkxController`

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| GET | /data/okx/trade/config | 用户 JWT / 服务令牌(orders:read) | OKX 账户配置 |
| GET | /data/okx/trade/balance?ccy= | 用户 JWT / 服务令牌(orders:read) | 交易账户余额 |
| GET | /data/okx/trade/positions?instType= | 用户 JWT / 服务令牌(orders:read) | 持仓 |
| GET | /data/okx/trade/orders-pending?instType= | 用户 JWT / 服务令牌(orders:read) | 未成交挂单 |
| POST | /data/okx/trade/order | 用户 JWT + 交易权限 | 下单：`{symbol, side, ordType(limit), sz, px, clOrdId}`。`clOrdId` 幂等，写前日志先落库再发网络 |
| POST | /data/okx/trade/cancel | 用户 JWT + 交易权限 | 撤单：`{symbol, ordId}` |

### 6.4 建议单 `OkxSuggestionController`

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| GET | /data/okx/suggestions?limit= | 用户 JWT | 最近的建议单（Agent 生成，等人处理） |
| POST | /data/okx/suggestions/{id}/execute | 用户 JWT + 交易权限 | 人工确认后转真实模拟盘下单；过四道风控。明确业务失败回到 `new` 可重试，结果未知保持 `executing` 待核实 |
| POST | /data/okx/suggestions/{id}/reject | 用户 JWT + 交易权限 | 人工否决，只把状态推进到 `rejected`，不产生任何订单 |

> 状态机：确认执行先把 `new` 抢占为 `executing`；成功为 `executed`，明确业务失败回到 `new`，
> 外部结果未知继续保持 `executing` 待核实；人工否决走 `new → rejected`。两个动作共用
> `requireOwnPending`，并发下不会出现「一个人确认、一个人否决」两个都成功。

### 6.5 WebSocket 与余额快照 `OkxWsController`

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| GET | /data/okx/ws/status | 用户 JWT | WS 连接状态、订阅、丢弃帧数、最近收帧时间（比翻日志可靠） |
| POST | /data/okx/ws/stop | 用户 JWT | kill switch：立即断开并停止重连（重启进程后按配置恢复） |
| GET | /data/okx/snapshots/latest | 用户 JWT | 最新余额（每币种一条 + TOTAL），来源是 WS `account` 频道 |
| GET | /data/okx/snapshots?ccy=&limit= | 用户 JWT | 历史余额快照，画权益曲线用 |

### 6.6 能力清单 `OkxCapabilityController`

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| GET | /data/okx/capability/modules | 用户 JWT | 当前开放的能力模块清单 |
| POST | /data/okx/capability/{name} | 用户 JWT（写能力另需交易权限） | 按能力名调用，body 原样透传（如 `market_ticker`）。受控白名单，不是万能代理 |

---

## 7. ai-service（8085，网关前缀 `/api/ai`）

规则版 Agent（不接 LLM）。**只读 + 只写建议单**：它没有下单能力，
`suggest-order` 只往 `okx_order_suggestion` 写一条待人工处理的建议。

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| GET | /ai/health | 无 | 健康检查（`mode=RULE_BASED_NO_LLM`, `readOnly=true`）；无密钥也能探活 |
| POST | /ai/ask | 用户 JWT | 问答：`{question}`，规则编排 + 公共行情，服务令牌可用时附带模拟盘挂单 |
| POST | /ai/suggest-order | 用户 JWT | 生成下单建议：`{instId(or symbol), side, ordType, sz, px, reason}`。**不调用下单接口** |
| GET | /ai/okx/pending-orders | 用户 JWT | 只读挂单视图（走「服务令牌 → data-service」链路）。`ok=false` 说明服务账号没配好 |

> ai-service **拒绝服务令牌**：`type=SERVICE` 的令牌在这里一律 401。
> 服务身份用于「ai-service 调 data-service」，不是用来调自己的用户端点——否则审计里会出现
> 「某个服务以用户身份提建议」这种说不清主体的记录。

---

## 8. 内部接口（不对外）

网关会拒绝任何带 `internal` 路径段的请求，外部调用根本到不了这里。服务间调用必须带
`X-Internal-Key`（`INTERNAL_KEY` 环境变量），缺失或错误一律 403（fail-closed）。

| 方法 | 路径 | 鉴权 | 调用方 | 说明 |
| --- | --- | --- | --- | --- |
| PUT | /auth/internal/deductCash?userId=&amount=&msgId= | 内部密钥 | trade-service | 扣减现金，`msgId` 幂等 |
| PUT | /auth/internal/addCash?userId=&amount=&msgId= | 内部密钥 | trade-service | 增加现金，`msgId` 幂等 |
| GET | /auth/internal/userCash?userId= | 内部密钥 | trade-service | 查询可用资金 |
| GET | /auth/internal/userInitialCash?userId= | 内部密钥 | analysis-service | 初始资金（资产曲线基准） |
| GET | /auth/internal/okxTradeEnabled?userId= | 内部密钥 | data-service | OKX 交易权限的唯一权威来源。用户不存在返回 `false` 而不是报错，由调用方 fail-closed |
| PUT | /auth/internal/resetCash?userId=&newCash= | 内部密钥 | 运维 | 重置现金（重置账户用） |
| POST | /trade/internal/queryByCondition | 内部密钥 | analysis-service | 全量交易列表（区间过滤） |
| POST | /trade/internal/statistics | 内部密钥 | analysis-service | 统计聚合 |
| POST | /trade/internal/monthly-profit | 内部密钥 | analysis-service | 月度盈亏聚合（`GROUP BY 月份`） |
| GET | /trade/internal/positions?userId= | 内部密钥 | analysis-service | 用户全部持仓（加权平均成本 + T+1 可卖） |
| DELETE | /trade/internal/clearAll?userId= | 内部密钥 | 运维/测试 | 清空某用户交易 |
| POST | /internal/okx/suggestion | 内部密钥（`okx.auth.internal-key`） | ai-service | 写入一条下单建议。**注意这条路径没有 `/data` 前缀** |

> 内部接口的共同约束：只接受正数 `userId`（防止「不传 userId 就查全表」），
> 且不返回任何凭证、密钥或跨用户数据。

---

## 9. 排障速查

| 现象 | 大概率原因 |
| --- | --- |
| 401 `未提供认证令牌` / `令牌无效或已过期` | 没带 Bearer，或令牌超过 24h，或已登出（黑名单） |
| 403 `内部接口需要有效凭据` | 请求路径带 `internal` 段却从网关进来（本来就不允许） |
| 403 `未被授予 OKX 交易权限` | `sys_user.okx_trade_enabled=0`，改库后等 30 秒缓存失效 |
| 400 `OKX 下单已关闭` | 全局总闸 `OKX_TRADE_ENABLED` 没开 |
| 400 `尚未配置可用的 OKX 模拟盘凭证` | 该用户没在「系统设置 → OKX 账户」录 Key |
| 503 `网关找不到可用实例` | 目标服务没起来（仪表盘同时依赖 trade 与 analysis） |
| 美股 K 线空 | 符号少了 `.US` 后缀（裸 `AAPL` 直接 400） |
| 分钟线时间看着像「晚 13 小时」 | 实际是北京时间，属正常；不要按美东再转 |
| 同步成功但图没数据 | 看的是 `/api/market/bars`（读 `bar` 表），要先同步；`/api/stock/daily` 走的是另一条链路 |
