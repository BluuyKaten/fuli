# 交接提示词：为「复利股票复盘」加入美股与加密货币 K 线

项目根目录 `H:/allCode/fuli`。技术栈 Spring Cloud Alibaba（gateway / auth-service / data-service / trade-service / analysis-service / common-api）+ Vue3 + lightweight-charts v5。行情表在 `data_db`，交易与练习表在 `trade_db`。

设计已经拍板，**不要再重新论证方向**，直接按下列约束实施。理由见 `docs/adr/0006-instrument-identity-and-symbol-scheme.md`、`docs/adr/0007-unified-bar-store-and-time-axis.md`；阶段划分见 `docs/multi-market-rollout.md`；术语见 `GLOSSARY.md`。

## 一、现状（已核实，无需重新调研）

- `data_db.stock_daily_data`：`stock_code VARCHAR(9)`、`trade_date INT`（`20231103`）、OHLC `DECIMAL(12,4)`、`vol`/`amount DECIMAL(20,4)`，唯一键 `(stock_code, trade_date)`，766 万行 / 5596 只。
- 日线来自 Tushare `daily`（**未复权**）；分钟/周/月线来自东财 `push2his.eastmoney.com`（`fqt=1`，**前复权**，不落库）。
- `trade_db` 有三张周期死表：`stock_minute_data` / `stock_weekly_data` / `stock_monthly_data`，有 DDL、实体、Mapper，全项目无读写。
- `DailyKlineCache`：L1 `ConcurrentHashMap` 10 分钟 + L2 Redis 30 分钟，key `kline:daily:{code}`，`evict()` 定义了但**无任何调用方**。
- 前端 `LightweightChart.vue`（约 1240 行）用 lightweight-charts v5，`parseTime()` 硬编码 `+08:00`。
- 标的后缀推导有四份实现，规则互不相同：`StockCodeUtil.withSuffix`、`frontend/src/utils/stockCode.ts`、`KlineController`、`EastMoneyDataSource`。`AAPL` 会被推成 `AAPL.BJ`。
- 盲测练习硬编码 A 股：`PracticeSnapshotLoader` 用 A 股正则卡标的，`PracticeState` 里 `lotSize=100`、佣金万三最低 5 元、卖出印花税万五、`ruleVersion="A_SHARE_DAILY_V1"`、隐式 T+1。

## 二、已定决策（约束，不是选项）

1. **市场**：只做美股 + 加密，**美股先上**。不做港股、期货、外汇。
2. **能力边界**：美股**纯看图**（K 线 + 画线 + 买卖点标记），不记账、不下单。加密要做回测 + 半自动；
   ~~全自动实盘~~ **已于 2026-11-09 废弃（ADR-0009 决策 4）**：实盘凭证不进 Java 服务，
   需要实盘成交由人在 OKX 侧执行。本条为早期规划，以 ADR-0009 为准。
3. **标的身份**：`<代码>.<市场>` 两段式，market ∈ {SH, SZ, BJ, US, CRYPTO}。例 `600519.SH`、`AAPL.US`、`BTCUSDT.CRYPTO`。交易所、计价币种、精度、交易单位、时区一律进 `instrument` 表，**不写进符号**。A 股存量符号不重写。
4. **美股行情**：落库，但**只同步自选清单**，不做全市场。日线落库，分钟线仍走代理。
5. **精度与单位**：价格列统一 `DECIMAL(20,8)`；显示精度取 `instrument.price_scale`（东财响应里的 `decimal` 字段可直接填）；单位统一「元 / 股 / 币」，A 股历史 `amount` 需 **×1000**（Tushare 千元 → 元）、`vol` 需 **×100**（手 → 股）；币种**原币直显、不折人民币**，总资产分币种展示不合并。
6. **复权**：只存原始价 + `adj_factor` 表。`adjust` 是查询参数（`none` / `forward`），在内存里算序列。**禁止落前复权**。
7. **存储**：一张 `bar` 表，`period` 列区分周期；按市场或按周期分表一律否决。
8. **时间轴**：`ts` = bar 开盘时刻的 UNIX 秒（UTC）。A 股日线 01:30 UTC，加密 00:00 UTC，美股 13:30 / 14:30 UTC。
9. **接口**：新增 `GET /market/bars?symbol&period&from&to&limit&adjust` 作为唯一新契约；旧 `/stock/daily|minute|weekly|monthly` 改为薄适配转调。缓存 key 统一 `kline:{period}:{symbol}:{adjust}`。
10. **策略**：自己写代码，不做规则引擎 / DSL。**独立 Python 进程**持有交易所密钥并直连交易所，不放进 Web 主站；与主站只保留两个契约：取 bar、回写成交。回测与实盘共用同一个 `Strategy` 接口。
11. **通道**：`ExchangeAdapter` 可插拔。第一版只接自建 paper 撮合；真实通道第一顺位 Gate（本机私有接口实测可达）。
12. **周期**：按市场下发，服务端维护「市场 → 周期列表」。`period` 受控集合 `1m/5m/15m/30m/1h/4h/1d/1w/1mo`，非法值返回 400。
13. **交易记录**：定义放宽为「一笔已发生的买入或卖出，来源可为人工录入或策略执行」，加 `source` 字段（`MANUAL` / `STRATEGY` / `PAPER`）。

## 三、`bar` 表

```sql
CREATE TABLE bar (
  instrument_code VARCHAR(20) NOT NULL COMMENT '标的代码，如 AAPL.US',
  period          VARCHAR(4)  NOT NULL COMMENT '1m/5m/15m/30m/1h/4h/1d/1w/1mo',
  ts              BIGINT      NOT NULL COMMENT 'bar 开盘时刻，UNIX 秒，UTC',
  open_price      DECIMAL(20,8),
  high_price      DECIMAL(20,8),
  low_price       DECIMAL(20,8),
  close_price     DECIMAL(20,8),
  pre_close       DECIMAL(20,8) NULL COMMENT '前收盘（除权口径，原样搬运）',
  volume          DECIMAL(28,8) COMMENT '股/币，不是手',
  amount          DECIMAL(28,8) COMMENT '计价货币元，不是千元',
  PRIMARY KEY (instrument_code, period, ts),
  CONSTRAINT chk_bar_period CHECK (period IN ('1m','5m','15m','30m','1h','4h','1d','1w','1mo'))
) COMMENT='行情 K 线';
```

配套 `instrument(code, market, name, currency, price_scale, lot_size, tick_size, tz, session, status, exchange)` 与 `adj_factor(instrument_code, ts, factor)`。`lot_size` 用 `DECIMAL(20,8)`；`exchange` 仅美股必填（NASDAQ/NYSE/AMEX，东财 secid 前缀用）。

## 四、硬约束（踩过的坑，违反会静默出错）

- **周期代号不能靠大小写区分**。`1M`（月）与 `1m`（分钟）在 MySQL 默认不区分大小写的排序规则下会撞主键，月线一律写 `1mo`。
- **`amount` / `vol` 单位混用，且换算方向曾写反**。`amount` 注释是「千元」（Tushare 口径），东财返回的是「元」；`vol` 注释「手」，东财是「股」。**同列混源必错，且只有 `SUM` 全表对账（×1000 / ×100 精确比率）能发现**。
- 迁移前必须完整备份 + 副本库全量校验；`stock_daily_data` 保留两周（过渡期双写）再 `DROP`；执行手册见 `docs/multi-market-stage0.md`。
- **删掉前端的 `toTushareCode` / `toPureCode`**。只留服务端一套解析，否则会有两套真相。
- **`DailyKlineCache.evict()` 必须接到同步任务后**。现在只能靠 TTL 过期，自动下单读到 30 分钟前的数据会出事。
- **`isAStock()` 这类判断改读 `instrument.market`**，不要再靠六位数字正则。
- **复权因子是派生数据**。美股可用 `fqt=0` 与 `fqt=1` 两条序列求比值得到，在拆股日按整数比校准；随时可重建，不作为事实源。

## 五、已实测的数据源（不要重复探测）

| 源 | 结果 |
|---|---|
| 东财美股 `secid=105.<code>` / `106.<code>` | 通。返全史（AAPL `dktotal` 10623）、`decimal` 精度、`fqt` 复权参数 |
| 东财加密 | 不通，`rc:100` |
| `data-api.binance.vision` | 通，**只读行情，不能下单** |
| `api.gateio.ws` | 通，私有接口可用。candlesticks 字段序为 `[ts, 计价量, close, high, low, open, 基础量, 是否收线]`，必须归一化 |
| `api.binance.com` / `www.okx.com` / `bybit.com` / `coingecko.com` | 不可达（OKX 的 DNS 解析到 `169.254.0.2` 黑洞地址） |

「可达」只对当前 shell 环境有效。判定通道可用性必须落在**部署目标环境**里——浏览器能开网页不等于 JVM 能连，也不等于容器能连。

## 六、阶段

0. **地基**（不可逆，先做）：建 `instrument`、收敛 `StockCodeUtil`、建 `bar` 并迁移 A 股 766 万行、建 `adj_factor`、`KlineQueryRepository` 改查 `bar`、新增 `/market/bars`、缓存加维度并接上 `evict()`、删除三张死表。A 股功能必须全程保持通过。
1. **美股看图**：东财美股适配器、自选清单、日线同步、复权因子推导、前端去硬编码。
2. **加密回测**：Binance Vision / Gate 适配器、日线与 4h 落库、Python 回测进程、自建撮合器（次根开盘、bps 滑点、maker/taker）。
3. **半自动**：真实 `ExchangeAdapter`、订单状态机 + `clientOrderId` 对账 + 写前日志、信号推送人工确认、四道风控（单笔上限 / 日累计上限 / 最大回撤熔断 / 全局 kill switch）。
4. ~~**全自动**：去掉 approval gate、密钥 AES-GCM + KEK 走环境变量、常驻守护与断连恢复、`POST /strategy/fills` 回写（HMAC）~~
   —— **已于 2026-11-09 废弃（ADR-0009 决策 4 / `multi-market-rollout.md` 阶段 4）**。
   常驻守护与断连恢复保留，由 `docs/okx-实施计划.md` P3 承接；成交对账改由 WS `orders` 频道 + 独立订单表（P4）承接。
   系统不持有实盘凭证，不做全自动实盘交易。

## 七、现在做什么

从阶段 0 的 0.1～0.3 开始：建 `instrument` 表并回填 A 股 → 收敛 `StockCodeUtil` 为唯一解析器并删除前端推断函数 → 建 `bar` 表并迁移 7,663,187 行。

这三步不动用户可见功能。迁移前先完整备份，在副本库（`data_db_verify`）跑一遍全量校验：行数、抽样比价、成交额/成交量 `SUM` 对账、LTTB 一致性。详细执行手册（含 DDL、转换 SQL、代码改动清单、回滚预案）：**`docs/multi-market-stage0.md`**。

## 八、术语

统一使用：标的 / 市场 / 标的代码 / 周期 / 原始价 / 复权。禁止再用：股票代码 / `ts_code` / `secid` / 频率 / 粒度。
