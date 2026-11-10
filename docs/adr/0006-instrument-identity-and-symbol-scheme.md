# 0006 - 标的身份用「代码.市场」两段式，属性外置到 instrument

- 状态：已采纳
- 日期：2026-11-04
- 关联：0003（字段瘦身）、0007（bar 存储与时间轴）

## 背景

系统只有 A 股一个市场时，标的是一串没有约束的字符串，三种表示并存：裸 6 位（前端 props、自选股）、Tushare 带后缀（`stock_daily_data`、`trade_record`、`practice_session`）、东财 secid（`1.600519`）。

后缀推导有四份各自为政的实现，覆盖范围互不相同：

| 位置 | 规则 | 缺陷 |
|---|---|---|
| `StockCodeUtil.withSuffix` | 认 920/43/83/87/60/68/900/00/30/20 | 最完整，但只有少数路径调用 |
| `frontend/src/utils/stockCode.ts` | 按首位 6/0/3/4/8 判断 | 920xxx 返回空后缀 |
| `KlineController` | 首位 6→SH，0/3→SZ，其余 BJ | 900/200 误判 |
| `EastMoneyDataSource` | 首位判断转 secid | 北交所归 0 |

`stock_info.market` 存的是 Tushare 的板块类型（主板/创业板/科创板/北交所），不是交易所，所以 `StockServiceImpl.isAStock()` 实际靠六位数字正则兜底。

接入美股与加密后这些规则全部失效：`AAPL` 会被推成 `AAPL.BJ`，`BTCUSDT` 会被推成 `BTCUSDT.BJ`。

## 决策

1. **标的身份是 `<代码>.<市场>`，两段式**。market ∈ {`SH`, `SZ`, `BJ`, `US`, `CRYPTO`}。例：`600519.SH`、`AAPL.US`、`BTCUSDT.CRYPTO`。
2. **符号只承载身份，不承载属性**。交易所、计价币种、价格精度、最小变动单位、交易单位、时区、交易时段全部进 `instrument` 表。
3. `instrument` 成为标的身份主表；`stock_info` 退化为 A 股扩展，只保留 area / industry 这类 A 股专有字段。`instrument.exchange` 仅美股必填（东财 `secid` 前缀用）；`lot_size` 用 `DECIMAL(20,8)` 以支持加密小额成交单位；裸代码只兼容 A 股 6 位数字，美股/加密必须带市场后缀。
4. 后缀推导收敛成一处。前端不再自行推断，只传完整符号或裸代码，解析交给服务端。
5. A 股存量符号不变，`600519.SH` 仍是 `600519.SH`，不做重写。

## 备选方案与取舍

- **三段式 `NASDAQ:AAPL`**（否决）：交易所是属性不是身份。同一标的换交易所不该换 ID；且冒号在查询参数和 URL 路径里都要转义。
- **前缀式 `US.AAPL`**（否决）：`LIKE 'US.%'` 更顺手，但会把 A 股存量符号改成 `SH.600519`，766 万行要重写，收益不抵成本。
- **市场独立成列、符号只存代码**（否决）：`stock_code` 已散落在日线表、交易记录、练习、自选股四处，改成两列组合键的波及面比加后缀大得多，且每条查询都要多带一个条件。

## 后果

- **后缀的语义其实不齐**：`SH` 是交易所，`US` 是国家市场，`CRYPTO` 是资产类别。这不是疏忽，是继承存量的代价。把后缀理解为「命名空间」而非「交易所」就自洽了。若强行统一成交易所，`BTCUSDT` 要写成 `BTCUSDT.BINANCE`，符号从 14 字符涨到 22，而且把单一交易所焊进了身份——同一天 Binance 和 OKX 的 BTCUSDT 是一个标的，不该是两个。
- **列宽要统一到 20**。`stock_daily_data` 现在是 9，正好卡住 `600519.SH`；`BTCUSDT.CRYPTO` 是 14 字符。ADR-0003 从 32 收到 9 的收益要回吐一部分。
- **必须删除前端的推断函数**（`toTushareCode` / `toPureCode`）。只要留一份就会出现两套真相——`LightweightChart` 现在日线传带后缀、分钟线传裸代码，正是这种分裂的产物。
- **`isAStock()` 这类判断改为读 `instrument.market`**，不能再靠正则。
