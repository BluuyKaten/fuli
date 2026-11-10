# V9：退役 `data_db.stock_daily_data`（0.9 操作清单）

配套脚本：`sql/migrations/V9__drop_data_db_stock_daily_data.sql`（默认整段注释，需手工放开）

## 为什么现在不能做

0.9 的触发器是**时间**，不是工作量：双写观察期要满 2 周，而观察期从**新代码部署当天**起算。
当前尚未部署，时钟还没开始走。此时把双写停掉，会直接毁掉回滚靶子：

- 旧表是唯一能「换回旧代码立刻可用」的数据源；
- 一旦停写，旧表冻结在今天，回滚后图表就缺这之后的所有交易日；
- 而 bar 若是坏了要重建，得重跑 7.67M 行迁移 + 5,178 天因子回填。

所以顺序只能是：**部署 → 双写跑满 2 周 → 每日对账全绿 → 停双写 → 再删表**。

## 停双写要改的 4 处（不是删一行）

`StockDailyData` 目前身兼两职：既是 Tushare 解析的载体，又是同步任务的**增量水位**来源。
只删 `upsertDailyData` 里那一行 `stockDailyDataMapper.upsert(...)` 会留下 3 处读旧表的查询，
部署后表现为「同步永远认为昨天没同步过」或「增量起点算错」，且不报错。

| # | 位置 | 现状 | 改法 |
|---|---|---|---|
| 1 | `TushareSyncServiceImpl#upsertDailyData` | `barMapper.upsert(toBar(d))` + `stockDailyDataMapper.upsert(d)` | 删掉后一行，只写 bar |
| 2 | `#isTradeDateAlreadySynced(tradeDate)` | `selectCount` 数旧表当日行数 ≥ 阈值 | 改为 `bar` 当日 `period='1d'` 的 `COUNT(*)`（`ts` 由 tradeDate 换算） |
| 3 | `#getLatestTradeDate(tsCode)` | 旧表按 `trade_date` 倒序取 1 行 | 改为 `SELECT MAX(ts) FROM bar WHERE instrument_code=? AND period='1d'`，再把 ts 换算回 `yyyyMMdd` |
| 4 | `#getGlobalLatestTradeDate()` | 同上，全表 | 改为 `SELECT MAX(ts) FROM bar WHERE period='1d'` |

配套改动：

- `BarMapper` 新增 3 个方法（`countByTradeDate` / `selectMaxTsBySymbol` / `selectGlobalMaxTs`），
  `ts` ↔ `tradeDate` 的换算必须走 `Asia/Shanghai`（与 `KlineQueryService.dayStartTs` 同口径），
  **不要**用 `FROM_UNIXTIME` 裸算——会话时区一变就错一天。
- `StockDailyData` 保留为纯 DTO（去掉 `@TableName`、`@TableId`），或改名 `TushareDailyRow` 更贴切；
  `StockDailyDataMapper` 整个删除。
- `StockDataInitializer#initStockDailyData` 名字有误导（它种的是 `bar`），顺手改名 `initSampleBars`。
- 测试 `DailyBarConversionTest` 仍可用（构造 DTO 断言换算），不受影响。

## 执行顺序（一次窗口内）

```bash
# 0) 停写前最后一次对账（两个数必须相等）
mysql -uroot -p --table -e "
SELECT (SELECT COUNT(*) FROM data_db.bar WHERE period='1d') AS bar_rows,
       (SELECT COUNT(*) FROM data_db.stock_daily_data)      AS legacy_rows;"

# 1) 备份旧表（它已比 11-04 的全量备份多了新数据）
mysqldump -uroot -p --single-transaction data_db stock_daily_data \
  > F:/backups/data_db-stock_daily_data-$(date +%Y%m%d).sql

# 2) 部署停双写的代码（4 处改动 + 删 Mapper）
# 3) 跑一次全市场同步，确认 bar 增长而旧表不动
# 4) 归档一个月（推荐），确认无回滚需求后再 DROP
mysql -uroot -p -e "RENAME TABLE data_db.stock_daily_data TO data_db.stock_daily_data_archive_2026m11;"
```

## 退役后 data_db 的终态

| 表 | 去留 |
|---|---|
| `instrument` | 留（标的主表） |
| `bar` | 留（全市场全周期唯一真相） |
| `adj_factor` | 留（派生，可随时重建） |
| `stock_info` | **后续阶段**再退役——`StockDataController#info(s)` 仍在读它的行业/地区字段，这些字段还没搬进 `instrument` |
