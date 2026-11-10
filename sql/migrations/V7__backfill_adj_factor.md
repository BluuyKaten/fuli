# V7 附：A 股复权因子回填（0.4）

对应 ADR：`docs/adr/0007-unified-bar-store-and-time-axis.md`
执行手册：`docs/multi-market-stage0.md` §6（`adj_factor` 建表）、§17.3（美股因子留待阶段 1.4）

## 为什么存全量

前复权是**视图**，不是事实：`price × factor / factor_latest`。
`factor_latest` 取「窗口末端那根 bar 的因子」，所以：

- 同一根 bar 在「看最近 620 根」和「看 2015 年那一段」时前复权价不同——这是前复权的定义，不是 bug。
- 因子必须按交易日逐行存，才能保证任意窗口都能就地算出该窗口的前复权序列；只存最新因子会让历史窗口全部错位。
- 因子是派生数据，任何时候都可以用本页命令重建，不参与备份优先级判断。

A 股 5,178 个交易日 × 约 1,300–5,600 只 ≈ **1,682 万行**（实测 16,818,420），
`DECIMAL(20,8)` 主键下约 **950 MB**。行数比 `bar` 多是因为因子按「当日全部在市标的」
落库，而 `bar` 只含已同步的标的；因子是 bar 的超集，属设计如此。

## 落库口径

| 列 | 口径 |
|---|---|
| `instrument_code` | 与 `bar.instrument_code` 完全一致（`600519.SH`） |
| `ts` | 与 1d bar 同口径：交易日 **01:30 UTC**（= 09:30 CST），不是本地零点 |
| `factor` | Tushare 累积复权因子，恒 > 0（表上有 `CHECK`） |

`ts` 由日期直接按 UTC 推算（脚本里 `date(...,tzinfo=utc).timestamp() + 5400`），
**不经过本地时区**——本机是 +08:00，任何 `mktime` 类调用都会把结果挪 8 小时。

## 执行

```bash
# 依赖：python3 + pymysql
python -m pip install pymysql

# token 来自环境变量（与 data-service 的 ${TUSHARE_TOKEN} 同源）
export TUSHARE_TOKEN=xxxxxxxx

# 主回填：按交易日拉全市场（可断点续跑）
python scripts/backfill_adj_factor.py

# 补洞 1：bar 里有行、因子对不上的标的，按标的拉全历史
python scripts/backfill_adj_factor_by_code.py --dry-run   # 先看清单
python scripts/backfill_adj_factor_by_code.py

# 验收：A1–A7 逐条 PASS/FAIL
mysql -uroot -p --table < sql/migrations/V7__verify_adj_factor.sql
```

> 主回填按**交易日**拉（`adj_factor?trade_date=`），5,178 次调用；
> Tushare 基础积分 500 次/分钟，脚本自行限速到 380 次/分钟，实测约 100 日/分，
> 全量 40–60 分钟，断点续跑按缺口计算。

**断点续跑语义**：每个交易日的期望行数 = 该日 `stock_daily_data` 的行数；
`adj_factor` 里该 `ts` 的行数 ≥ 期望值即视为完成并跳过。中断后重跑只补缺口。
重复写入走 `ON DUPLICATE KEY UPDATE`，重跑同一天是幂等的。

## 校验

以 `sql/migrations/V7__verify_adj_factor.sql` 为准（A1–A7，每项 PASS/FAIL）。
原文这里的三条 SQL 保留说明如下，其中第 2 条**口径已修正**：

```sql
-- 1) 行数应与源表同量级（因子只在有行情的交易日存在）
SELECT COUNT(*) AS factor_rows, COUNT(DISTINCT ts) AS days,
       MIN(factor) AS min_factor, MAX(factor) AS max_factor
FROM data_db.adj_factor;

-- 2) 【口径已修正】原来的写法是「每条因子行都要有同名 bar 行」，会恒为 FAIL：
--    adj_factor?trade_date= 返回当日全部在市标的，比本地 stock_daily_data
--    多出退市/停牌/未纳入同步的标的。因子是 bar 的**超集**，这是设计使然。
--    真正的不变量是「ts 落在当日 01:30 UTC」，以及「反方向：bar 不缺因子」。
SELECT COUNT(*) AS bad_offset_rows FROM data_db.adj_factor WHERE ts % 86400 <> 5400;
SELECT COUNT(*) AS bar_rows_without_factor FROM data_db.bar b
WHERE b.period='1d' AND NOT EXISTS (
  SELECT 1 FROM data_db.adj_factor f
  WHERE f.instrument_code=b.instrument_code AND f.ts=b.ts);

-- 3) 因子恒正（CHECK 已兜底，这里再确认存量）
SELECT COUNT(*) AS non_positive FROM data_db.adj_factor WHERE factor <= 0;

-- 4) 抽样：茅台最近 3 个交易日的因子应完全相同（无除权时因子不变）
SELECT instrument_code, FROM_UNIXTIME(ts, '%Y-%m-%d') d, factor
FROM data_db.adj_factor WHERE instrument_code = '600519.SH'
ORDER BY ts DESC LIMIT 3;
```

## 验收结果（2026-11-05 实测）

| 项 | 结果 |
|---|---|
| factor_rows | 16,818,420 |
| days | 5,179（比行情多 1 天：Tushare 已出当日因子，bar 等下次同步） |
| codes | 5,889 |
| factor 区间 | 0.64500000 ~ 10055.64000000 |
| A2 交易日覆盖面 | PASS（lead_days = 1） |
| A3 时间轴不变量 | PASS（0 行偏离 01:30 UTC） |
| A4 bar 缺因子 | 711 行（全部为下方「已知例外」） |
| A4c 排除例外后 | PASS（0 行） |
| A6 因子恒正 | PASS |

## 已知例外：北交所 920 号段 2023 年前无因子（源端无数据，711 行）

11 只北交所标的在 `bar` 里有 2020–2022 年的日线，但 Tushare 不给这段的因子：

| 现行代码 | 旧代码 | 名称 | 缺口区间 | 行数 |
|---|---|---|---|---|
| 920751.BJ | 833751.BJ | 惠同新材 | 2020-07-02 ~ 2022-12-23 | 408 |
| 920339.BJ | 873339.BJ | 恒太照明 | 2021-09-02 ~ 2022-06-28 | 89 |
| 920957.BJ | 836957.BJ | 汉维科技 | 2020-05-29 ~ 2022-06-14 | 81 |
| 920145.BJ | 832145.BJ | 恒合股份 | 2020-06-12 ~ 2021-04-12 | 50 |
| 920427.BJ | 833427.BJ | 华维设计 | 2020-06-16 ~ 2020-08-27 | 28 |
| 920892.BJ | 836892.BJ | 广咨国际 | 2020-06-17 ~ 2020-12-24 | 26 |
| 920047.BJ | 430047.BJ | 诺思兰德 | 2020-05-29 ~ 2020-06-29 | 20 |
| 其余 4 只 | — | — | 2020–2022 | 9 |

**成因**：`bar` 来自按标的拉的 `daily?ts_code=`（返回现行代码下的全历史），
而因子主回填按交易日拉 `adj_factor?trade_date=`（返回当时的代码），
两者在 920 号段改号上分叉。按 `bse_mapping` 用旧代码补也不行——
实测 `adj_factor?ts_code=833751.BJ` 与 `adj_factor?trade_date=20200702`
都取不到该股，说明**源端对这段历史根本没有因子**（该股 2023-07-17 才进北交所）。

**影响**：仅这 711 行，`adjust=forward` 在该段退化为原价（`applyForward` 对缺因子的
bar 原样透出，不报错），图上表现为 2023-07-17 处一个台阶。
**不修**：源端缺失，无法修；除非另找因子源。

## 因子为「窗口相对」——`selectRange` 的开区间坑

`AdjFactorMapper.selectRange` 的 `toTs` 是**开区间**，而 `factor_latest` 要的正是
窗口**末端那根 bar** 的因子。首版传 `lastTs`，末端 bar 被排除，分母退化为前一根，
于是「窗口止于除权日」时除权日之前的 bar 全都不缩放——界面看着像没复权，但不报错。
修法：调用处传 `lastTs + 1`，回归测试见 `ForwardAdjustTest`。


## 前复权语义（供阶段 1.5 / 阶段 2 对齐）

`GET /market/bars?adjust=forward` 的实现：

1. 取窗口内 bar，按 `ts` 关联 `adj_factor`；
2. `factor_latest` = 窗口**末端那根 bar** 的因子（末端缺因子时退回窗口内最后一个）；
3. 每根价格 `× factor / factor_latest`，保留 8 位；
4. 成交量、成交额**不缩放**——复权是价格口径，不是量口径。

因此 `adjust=forward` 的序列末端必然等于原始价（线性缩放点到 `factor_latest` 处比值恒为 1）。
这也是验收项之一。

无因子数据的标的（加密、或尚未回填的美股）`adjust=forward` 等价于 `none`，接口不会报错。
