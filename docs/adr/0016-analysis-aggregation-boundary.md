# ADR-0016：分析聚合下沉的边界 —— 只下沉月度盈亏

- 状态：已接受（2026-11-11）
- 关联：ADR-0004（K 线两级缓存）、ADR-0015（练习快照缓存）
- 落地：`trade-service/.../TradeRecordMapper#aggregateMonthlyProfit`、`TradeRecordController#internalMonthlyProfit`、`analysis-service/.../AnalysisService#getMonthlyProfit`

## 背景

`analysis-service` 不直连数据库，全部通过 Feign 调 trade-service。它有三个方法各自
`getTradeList(userId, null, start, end)` 拉一次**全量交易列表**，再用 stream 聚合：

| 方法 | 聚合方式 | 输出规模 |
| --- | --- | --- |
| `getStatistics` | 已经是 SQL 聚合（trade-service 算好） | 1 行 |
| `getMonthlyProfit` | 内存按月分组 | M 个月 |
| `getAssetCurve` | 内存逐笔推导权益曲线 | D 天 × 2 数组 |
| `getPerformanceStats` | 内存算胜率/盈亏比/连胜连亏/持仓周期 | 1 个对象 |

Caffeine 缓存只对「同一个 key」有效，不同时间区间就是不同 key，照样全量拉。
问题不在 CPU，在**载荷**：N 笔交易（几千行）过一遍 HTTP + JSON，只为得到 M 行（最多几十行）。

## 决策

**只把 `getMonthlyProfit` 下沉成 SQL 聚合，另外两个不动。**

### 为什么月度盈亏适合下沉

`GROUP BY 月份` 是纯分组聚合，没有任何顺序依赖：每笔交易独立贡献自己的
`profit_loss`，月份之间互不影响。SQL 表达出来只有四列，没有窗口函数、没有自连接。
收益是量级差（N 笔 → M 行），成本是两条 SQL 加一次端点。

### 为什么资产曲线不下沉

它要按时间顺序**逐笔**累加现金与持仓：买入扣现金、改加权成本；卖出加现金、减持仓；
每日再用当日收盘价给持仓估值。写成 SQL 需要
`SUM() OVER (ORDER BY trade_date, id)` 加上「持仓数量」和「加权成本」两条递推——
而加权成本是**路径依赖**的（后一次买入的均价依赖前一次的结果），窗口函数表达不了，
只能退化成递归 CTE 或者自连接逐笔回填。改完之后还要证明「和内存实现逐位对齐」，
而这个对齐涉及 BigDecimal 的舍入顺序，风险远大于收益。

保留内存实现，但记下一条更划算的优化（本次未做）：`getAssetCurve` 与
`getPerformanceStats` 在同一次页面加载里各自拉一次全量交易，可以合并成一次。

### 为什么绩效统计不下沉

胜率/盈亏比/最大连胜连亏依赖「买入与卖出配对成往返」（先进先出），这是**顺序依赖的状态机**。
SQL 里要表达就是又长又慢的自连接，而且配对规则一旦和数据顺序耦合，SQL 的
执行计划变化都可能让结果漂移。这类计算留在内存里，反而更可验证。

## 约束：结果必须逐位一致

这是给用户看的账，差一分钱都会被问。两条防线：

1. **胜率不在 SQL 里算**。`winCount / tradeCount` 需要 4 位小数 + HALF_UP，
   而 MySQL 与 H2 的除法精度、舍入默认值都不一样。SQL 只返回 `winCount` 和 `tradeCount`，
   在 Java 里用 `BigDecimal` 复算，与旧实现同一行代码。
2. **对拍测试**（`MonthlyProfitAggregationTest`）：把 analysis-service 的原内存实现
   原样搬进测试当参照物，用同一份数据跑新旧两遍 `assertEquals`。覆盖无交易、跨年、
   平盘、买入行无盈亏、含手续费金额、区间过滤、他人数据与逻辑删除。

月份字符串用 `EXTRACT` + `LPAD` 拼而不用 `DATE_FORMAT`：后者是 MySQL 专有，
H2（测试环境）没有，用它等于让这条聚合只能靠人工验证。已在 MySQL 8.4 上实测，
两种写法输出一致（`2025-12` / `144.3500`，scale 4 与旧实现相同）。

## 后果

- `monthly-profit` 的载荷从「N 笔交易」降到「M 个月」；月度趋势图与仪表盘直接受益。
- **部署顺序**：trade-service 必须先于 analysis-service 上线，否则新端点 404，
  analysis 降级为空列表（前端显示「暂无月度盈亏数据」，不报错）。
- `getPerformanceStats` 里仍保留 `computeMonthlyProfit`（它复用已拉取的交易列表，
  不会多一次 Feign 往返）——所以同一个「月度盈亏」在代码里暂时有两份实现。
  两者口径由对拍测试绑住；若将来绩效统计也换了数据源，记得同步删掉内存版本。
