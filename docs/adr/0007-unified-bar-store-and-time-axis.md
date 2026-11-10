# 0007 - 行情统一存一张 bar 表，时间轴用 UTC 时间戳

- 状态：已采纳
- 日期：2026-11-04
- 关联：0002（窗口化加载）、0003（字段瘦身）、0004（两级缓存）、0006（标的身份）

## 背景

`data_db.stock_daily_data` 是「A 股单市场 + 日线单周期」的产物：`stock_code VARCHAR(9)`、`trade_date INT`（`20231103`）、OHLC `DECIMAL(12,4)`、`vol`/`amount DECIMAL(20,4)`，唯一键 `(stock_code, trade_date)`，766 万行（实测 7,663,187 行）、5596 只。

接入美股与加密后，这张表有四处装不下：

1. **精度**：`DECIMAL(12,4)` 装不下 8 位小数的币价，SHIB 的 `0.00000823` 会存成 `0.0000`。
2. **单位**：`amount` 注释是「千元」（Tushare 口径），东财返回的是「元」；`vol` 注释是「手」，东财是「股」。现在两列都只来自 Tushare 所以没暴露，一旦美股（东财）写进同一列，同列两种单位，所有成交额统计会静默算错。
3. **时间**：`trade_date INT` 把时间点压成了日期字符串。加密日线的 UTC 00:00 对应北京时间 08:00，界面上「今天涨没涨」会和这根 K 线差 8 小时；美股还要处理夏令时。
4. **周期**：分钟/周/月线在 `trade_db` 有三张表（`stock_minute_data` / `stock_weekly_data` / `stock_monthly_data`），有 DDL、实体、Mapper，全项目无读写——实际走东财实时抓取。

## 决策

**一张 `bar` 表，用 `period` 列区分周期，不按市场也不按周期分表。**

```sql
CREATE TABLE bar (
  instrument_code VARCHAR(20) NOT NULL COMMENT '标的代码，如 AAPL.US',
  period          VARCHAR(4)  NOT NULL COMMENT '1m/5m/15m/30m/1h/4h/1d/1w/1mo',
  ts              BIGINT      NOT NULL COMMENT 'bar 开盘时刻，UNIX 秒，UTC',
  open_price      DECIMAL(20,8),
  high_price      DECIMAL(20,8),
  low_price       DECIMAL(20,8),
  close_price     DECIMAL(20,8),
  pre_close       DECIMAL(20,8) NULL COMMENT '前收盘（除权口径，原样搬运，不换算）',
  volume          DECIMAL(28,8) COMMENT '股/币，不是手',
  amount          DECIMAL(28,8) COMMENT '计价货币元，不是千元',
  PRIMARY KEY (instrument_code, period, ts),
  CONSTRAINT chk_bar_period
    CHECK (period IN ('1m','5m','15m','30m','1h','4h','1d','1w','1mo'))
) COMMENT='行情 K 线';
```

`pre_close` 必须随 bar 一起存：除权除息日的前收盘不等于前一根收盘（Tushare 给的是除权参考价），无法从 OHLC 推算；盲测练习的「除权缺口」守护和旧接口的 `preClose` 字段都依赖它。`changeAmount` / `pctChg` 不存，由 close − preClose 在适配层计算。

配套两张表：`adj_factor(instrument_code, ts, factor)` 与 `instrument(...)`（见 ADR-0006）。

**时间轴约定**：`ts` 是 bar 的开盘时刻，换算成 UTC 秒。

| 市场 | 日线 ts | 说明 |
|---|---|---|
| A 股 | 01:30 UTC | 09:30 CST |
| 美股 | 13:30 / 14:30 UTC | 09:30 ET，夏令时 / 冬令时 |
| 加密 | 00:00 UTC | 交易所原生 |

理由是**夏令时是渲染问题，不是存储问题**：一旦存的是绝对时刻，跨时区和跨夏令时的对齐全部免费。

**复权不落存储维度**。只存原始价，`adjust` 是查询参数（`none` / `forward`），按 `adj_factor` 在内存里算序列，缓存 key 必须带上它。

**`period` 取代三张死表**，`stock_minute_data` / `stock_weekly_data` / `stock_monthly_data` 一并删除。

**迁移一次性做**，`INSERT ... SELECT`，`stock_daily_data` **保留两周作为回滚靶子**（过渡期新同步双写 `bar` 与 `stock_daily_data`），观察期结束后停双写、再 `DROP`。不采用视图方案——回滚要的是「旧代码读到最新数据」，视图给不了新表写回旧口径。

## 备选方案与取舍

- **只给 `stock_daily_data` 加 `market` 列**（否决）：`ts` 和精度迟早要改，改完和统一表的改动量一样，却留下三张死表和一个语义分裂的表名。
- **分市场分表**（`us_daily_data` / `crypto_daily_data`）（否决）：短期最省事，代价是查询、缓存、同步、缩略图四套代码各写三遍，而这三个市场的 K 线语义真的相同。
- **落前复权**（否决）：前复权每来一次分红送转都会重写全部历史价格，今天存的值明年对不上、缓存全部失效，更要命的是回测不可复现——同一策略今天跑和明年跑能出不同净值。原始价是事实，复权是视图。
- **保留 `trade_date INT`、按市场加时区列**（否决）：等于让每一行都背一个常量时区，且查询仍要做换算。

## 后果

- **周期代号不能用大小写区分**。`1M`（月）和 `1m`（分钟）在 MySQL 默认的不区分大小写排序规则下会撞主键，所以月线统一写 `1mo`。这一条看起来琐碎，但一旦有数据写进去才发现，排查成本极高。除应用层受控集合校验外，`bar` 表加 `CHECK (period IN (...))` 兜底：即使绕过应用写入，非法周期也写不进去。
- **主键宽度从 40 字节涨到约 104 字节**（`VARCHAR(9)+INT` → `VARCHAR(20)+VARCHAR(4)+BIGINT`），写入变慢，ADR-0003 的瘦身收益部分回吐。换来的是一张表覆盖全市场全周期，`KlineQueryRepository` 只需一个实现，且聚簇索引天然按 `(标的, 周期, 时间)` 排好。ADR-0005 的「分片键 `stock_code`」随之改为 `instrument_code`。
- **所有按日期范围的查询改成 `ts BETWEEN`**，涉及 `KlineQueryRepository`、`StockDataController`、`PracticeSnapshotLoader`。对外接口仍接受 `yyyyMMdd` 和 ISO 日期，由 controller 换算，不要把 epoch 泄漏给前端。
- **复权序列在内存里算**，缓存必须带 `adjust` 维度（`kline:{period}:{symbol}:{adjust}`），否则前复权和原始价互相污染。`DailyKlineCache.evict()` 迄今无人调用，这次必须接上同步任务——自动下单读到 30 分钟前的数据是会出事的。
- **A 股历史 `amount` 要 ×1000**（Tushare 千元 → 元），`vol` 要 **×100**（手 → 股），才能和美股、加密同列。迁移必须校验三件事：行数、抽样比价、成交额量级（`SUM` 全表对账，比率必须精确为 1000 / 100）。
- **交易日历仍未引入**。加密 7×24 不需要它；A 股和美股仍靠自然日推算，`TushareSyncServiceImpl` 的 `missingDays` 仍会把周末算成缺失日。这是已知缺口，不在本次范围内。
- **`trade_db` 还有两张行情陈旧副本**：`trade_db.stock_daily_data`（约 676 万行）与 `trade_db.stock_info`（5667 行），是迁入 `data_db` 之前的全量快照，代码无任何引用（唯一引用方是 `sql/migrate_trade_db_to_data_db.sql`）。阶段 0 删三张死表时一并删除，否则会留下「哪个库才是行情事实」的长期歧义。

## 修订记录

- 2026-11-04（复审修订）
  - `bar` 增加 `pre_close`（原样搬运）：除权除息日前收盘无法从 OHLC 推算，盲测练习的除权缺口守护和旧接口 `preClose` 依赖它。
  - 单位换算方向订正：A 股 `amount` 千元 → 元 **×1000**（原文 ÷1000，方向反了），`vol` 手 → 股 **×100**（原文遗漏）。
  - `bar` 增加 `CHECK (period IN (...))`：周期受控集合的第二道防线。
  - 回滚策略明确为「保留旧表 + 双写两周」，不用视图。
  - 补记 `trade_db.stock_daily_data` / `stock_info` 陈旧副本，纳入阶段 0 清理。
