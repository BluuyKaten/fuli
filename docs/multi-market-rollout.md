# 多市场 K 线落地清单

前置阅读：`docs/adr/0006-instrument-identity-and-symbol-scheme.md`、`docs/adr/0007-unified-bar-store-and-time-axis.md`。

四个阶段，每个阶段都是可独立上线、可独立回滚的。**阶段 0 是唯一不可逆的一步**，它之前的所有代码都可以推倒重来，它之后不行。

---

## 阶段 0 · 地基

目标：不改变任何用户可见功能，把「标的身份」和「行情存储」换成多市场底座。A 股功能必须保持全绿。

**逐步执行手册见 `docs/multi-market-stage0.md`**（备份 / 副本库演练 / DDL / 回填 / 代码改动清单 / 校验 SQL / 回滚预案）。本表只列事项与触及范围。

| # | 事项 | 主要触及 |
|---|---|---|
| 0.1 | 建 `instrument` 表（含 `exchange`、`lot_size DECIMAL(20,8)`、`tick_size`、`tz`、`session`、`status`），回填全部 A 股（market 取自符号后缀，currency=CNY / price_scale=2 / lot_size=100 / tz=Asia/Shanghai） | `sql/` |
| 0.2 | `StockCodeUtil` 收敛为唯一解析器，覆盖 SH/SZ/BJ/US/CRYPTO（裸代码仅兼容 A 股 6 位；美股/加密必须带后缀）；**前端 `toTushareCode` / `toPureCode` 删除**，调用点全部改用完整符号（清单见 runbook §10.5）；`user_watchlist` 存量 7 条裸码补后缀迁移（§10.6） | `common-api`、`frontend/src/utils/stockCode.ts`、`auth_db.user_watchlist` |
| 0.3 | 建 `bar` 表（含 `pre_close`、`CHECK(period IN (...))`），A 股 7,663,187 行迁入（`period='1d'`，`ts`=交易日 01:30 UTC，**`amount`×1000（千元→元）、`vol`×100（手→股）**，`pre_close` 原样） | `sql/` |
| 0.4 | 建 `adj_factor` 表（全量存储、每交易日一行），回填 A 股复权因子（Tushare `adj_factor`） | `sql/`、`data-service` |
| 0.5 | `KlineQueryRepository` / `KlineQueryService` 改查 `bar`；**sparkline（`selectCloseSeries`）、`latest-prices` 批量查询、`StockDataInitializer` 种子数据一并切换**；旧 Feign 响应契约 keys 不变（`tradeDate`=yyyyMMdd 字符串、`preClose`…），`changeAmount`/`pctChg` 由 close−preClose 计算 | `data-service` |
| 0.6 | 新增 `GET /market/bars?symbol&period&from&to&limit&adjust`（落在 data-service，返回 `Result`，响应带 `meta`：priceScale/currency/lotSize/market/name）；gateway 新增 `/api/market/**` 路由；旧 `/stock/daily\|minute\|weekly\|monthly` 及 `/stock/daily/sparkline` 改为薄适配转调；**Python 回测进程内网直连 data-service 8084 取 bar**（data-service 无认证、仅限内网，与现有 Feign 一致） | `data-service`、`gateway-service`、`trade-service` |
| 0.7 | 缓存 key 加 `period` / `adjust` 维度并加版本前缀（`kline:v1:{period}:{symbol}:{adjust}`）；`evict()` 接到同步任务后（Tushare 日线 upsert 完成后按 period 失效，none/forward 两个 adjust 全删） | `DailyKlineCache` |
| 0.8 | 删除 `trade_db` 三张死表（`stock_minute_data` / `stock_weekly_data` / `stock_monthly_data`，均 0 行）**以及 `trade_db.stock_daily_data`（约 676 万行陈旧副本）与 `trade_db.stock_info`（5667 行）**；实体、Mapper 一并删除；`stock_daily_data` 保留两周（双写回滚靶子），观察期满再 `DROP` | `trade_db`、`data-service` |
| 0.9 | 过渡期（≥2 周）新日线同步**双写** `bar` 与 `stock_daily_data`（后者单位需反向换算），期满停止双写并删除兼容代码 | `data-service` |

**进展（2026-11-05）**：0.1–0.8 已完成并验收（`instrument` 5,596 / `bar` 7,674,330 / `adj_factor` 16,823,991 行，V0–V7 + A1–A6 全 PASS，后端 126 + 前端 43 测试全绿）。0.9 未执行——它的触发器是「部署后双写满 2 周」，而部署尚未进行，此时停双写会毁掉回滚靶子；操作清单见 `sql/migrations/V9__retire_stock_daily_data.md`。

**验收口径**：同一只股票、同一时间窗，迁移前后 OHLC 逐根相等（`adjust=none` 时）；`bar` 行数 = `stock_daily_data` 行数；**`SUM(amount)` 与 `SUM(vol)` 全表对账**（注意成交额要按「反算回千元 4 位」比，不能写 `src×1000`，原因见 runbook §9 第 4 条：bar 存 Tushare 原始精度，旧表把千元截到 4 位）；sparkline LTTB 结果一致；盲测练习的既存 session 仍能正常推进；观察两周无异常后方可 `DROP stock_daily_data`。

---

## 阶段 1 · 美股看图

目标：能看图、画线、标买卖点。不记账、不下单。

| # | 事项 |
|---|---|
| 1.1 | `EastMoneyAdapter` 支持美股：`secid=105.<code>`（NASDAQ）/ `106.<code>`（NYSE），解析响应里的 `decimal` 字段直接填 `instrument.price_scale` |
| 1.2 | 自选清单表：手工录入 `AAPL.US`，用东财搜索接口校验并补全名称，不做全市场同步 |
| 1.3 | 美股日线同步任务：只同步清单内标的，落 `bar` 表（`period='1d'`，`ts`=09:30 ET 转 UTC） |
| 1.4 | 复权因子：分别取 `fqt=0` 与 `fqt=1` 两条序列求比值得到相对因子，在比例跳变点（拆股日）按整数比校准。**这是派生数据，随时可重建，不作为事实源**；低价股因报价小数位少会噪声偏大，属可接受误差 |
| 1.5 | 前端：周期列表改为服务端下发，`parseTime` 去掉硬编码 `+08:00`，`LightweightChart` 的 `stockCode` prop 改为完整符号（`LightweightChart` 接收的本就是完整符号，见 §0.2）；**同窗口迁移 `chart_drawing.period` 存量值**（`1D→1d`/`1W→1w`/`1M→1mo`/`'1'→'1m'`/`'5'→'5m'`/`'15'→'15m'`/`'60'→'1h'`，存量仅 2 行），避免画线记录失联 |

**完成标志**：`AAPL.US` 的日线图与东财网页端逐根对齐；切换到 2020-08-31 那天，图上**没有** −75% 的假阴线。

---

## 阶段 2 · 加密回测

目标：给一段历史，出一张净值曲线。不碰真钱、不常驻。

| # | 事项 |
|---|---|
| 2.1 | `BinanceVisionAdapter`（`data-api.binance.vision`，实测可达）+ `GateAdapter` 备选；Gate 的 candlesticks 字段序是 `[ts, 计价量, close, high, low, open, 基础量, 是否收线]`，必须在适配器里归一化 |
| 2.2 | 加密日线与 4h 线落 `bar`（`ts`=00:00 UTC，`adj_factor` 恒为 1，不写行） |
| 2.3 | Python 回测进程：`GET /market/bars` 取数 → 撮合器 → 净值 / 最大回撤 / 胜率 |
| 2.4 | 自建撮合器：次根 bar 开盘价成交、滑点按 bps 参数化、费率区分 maker/taker、不做部分成交 |
| 2.5 | `Strategy` 接口定稿，回测和实盘后续共用同一份策略代码 |

**完成标志**：同一份策略代码、同一段历史，跑两次得到**逐位相同**的净值序列。

---

## 阶段 3 · 半自动

目标：策略出信号，人工确认后下单。

| # | 事项 |
|---|---|
| 3.1 | `ExchangeAdapter` 真实实现，第一顺位 Gate（本机私有接口实测可达），OKX 待验证后补 |
| 3.2 | 订单状态机 + `clientOrderId` 对账；**所有订单先落库再发网络**（写前日志） |
| 3.3 | 信号推送 → 人工确认 → 下发 |
| 3.4 | 四道风控：单笔上限、日累计上限、最大回撤熔断、全局 kill switch |

**完成标志**：断网 1 分钟后恢复，订单状态与交易所一致，无重复下单、无孤儿单。

---

## 阶段 4 · 全自动 —— **已废弃（2026-11-09，ADR-0009 决策 4）**

> **状态**：本节是 2026-11-05 之前的早期规划，已被后出的 ADR-0009 取代，**不再作为目标**。
> 冲突点：4.1「去掉 approval gate」与 4.2「密钥 AES-GCM 加密存储」隐含「系统持有实盘凭证并自动下单」，
> 与「实盘凭证不进 Java 服务、模拟盘是代码常量、需要实盘成交由人在 OKX 侧执行」直接矛盾。
> 按「后出且已接受的 ADR 覆盖早期规划」处理：**改本节，不改 ADR-0009**。
> 4.3 的守护/重连由 `docs/okx-实施计划.md` P3 承接，成交对账改由 WS `orders` 频道 + `tradeId`（P4）承接。

~~目标：去掉 approval gate，7×24 无人值守。~~

| # | 事项 | 处置 |
|---|---|---|
| 4.1 | ~~去掉人工确认环节，与阶段 3 共用同一条订单管道~~ | ❌ 废弃：系统不做实盘自动下单 |
| 4.2 | ~~密钥 AES-GCM 加密存储，KEK 走环境变量~~ | ❌ 废弃：实盘凭证根本不进 Java 服务 |
| 4.3 | 常驻守护、断连重连、异常告警 | ✅ 保留，见实施计划 P3 |
| 4.4 | ~~`POST /strategy/fills` 回写成交~~ | ♻️ 改为 WS 订单频道 + 独立订单表对账，见 P4 |

~~**完成标志**：连续运行 72 小时，成交回报与交易所账单逐笔核对一致。~~
替代验收：P4 沿用本文阶段 3 的完成标志——「断网 1 分钟后恢复，订单状态与交易所一致，无重复下单、无孤儿单」。

---

## 明确不在范围内

- 港股、期货、外汇
- 交易日历（A 股与美股仍按自然日推算）
- 跨币种总资产合并（¥ 与 USDT 分开展示）
- 加密分钟线全量落库（单对 5 年 1m ≈ 260 万行，十对就是 2600 万行）
- UI 上配置策略规则（策略一律写代码）

## 已知风险

- **阶段 0 不可逆**。`bar` 表结构和符号格式一旦有数据写入，回退成本极高。迁移前必须完整备份（`mysqldump --single-transaction`），并先在副本库（如 `data_db_verify`）跑一遍全量校验：行数、OHLC 抽样比价、**成交额/成交量 SUM 对账**、LTTB 一致性。
- **单位换算是最大静默故障点**。A 股 `amount` 千元→元 ×1000、`vol` 手→股 ×100；迁移脚本与日常同步（`TushareSyncServiceImpl`）必须走同一个换算落点，否则历史数据与新数据差 1e3/1e2 倍，均线/指标全部失真且不报错。对账以 `SUM` 精确比率为准，不是「量级看着差不多」。
- **代码切换与建表回填必须同一窗口完成**。18:00 定时任务会写日线；若 `bar` 落库后旧代码仍在跑，会出现「新表缺当天行」。过渡期双写 + 窗口内部署 + 部署后手动触发一次增量同步补齐。
- **OKX 可达性未验证**。本机 DNS 解析到 `169.254.0.2` 黑洞地址，用户称曾成功连接，待确认验证环境。浏览器能开网页不等于 JVM 能连，也不等于容器能连。Gate 是目前唯一实测可用的私有接口通道；阶段 3 前必须在**部署目标环境**复测 Gate / OKX 连通性（JVM + 容器 + Python 三侧），并写进部署检查清单。
- **回测结论的可信度取决于撮合口径**。口径（次根开盘 / 滑点 / 费率）必须在看任何回测结果之前定死，否则会不自觉地调参数去拟合历史。
- **阶段 2 与阶段 3 之间隔着一次架构验证**：策略进程能取到数据、能下单，但它读到的 `bar` 和主站图表看到的必须是同一份、同一个 `adjust` 口径。这一点靠缓存 key 的一致性保证，不靠约定。
