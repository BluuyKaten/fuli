# K 线数据链路性能治理实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 K 线页在任意跨度下都能秒开，把 766 万行日线库的查询成本与并发成本从"每次穿透"降到"命中缓存"，同时不为当前量级引入分片复杂度。

**Architecture:** 三层治理，顺序不可颠倒。①**先度量**——没有基线就无法证明任何改动有效，也无法在答辩时给出前后对比。②**再收窄数据面**——接口按视窗返回，前端按视窗渲染，指标按视窗计算。③**最后加缓存**——在数据面收窄之后缓存才有意义，否则只是缓存了大量马上要被丢弃的数据。

**Tech Stack:** Vue 3.5 + TypeScript + lightweight-charts 5.2.0 / Spring Boot 4 + MyBatis-Plus / MySQL 8.4 / Redis 7 + Caffeine

**决策依据:** ADR-0002（视窗加载）、ADR-0003（字段瘦身）、ADR-0004（两级缓存）、ADR-0005（不分片）

---

## Global Constraints

- **不改主键，不改分片方案**。分片键固定为 `stock_code`，见 ADR-0005。
- **不改 `uk_code_trade_date` 的结构**，只改列类型。索引名、唯一性语义保持不变。
- **`lightweight-charts` v5 API**：`chart.addSeries(CandlestickSeries, options)`，不是 v4 的 `addCandlestickSeries`。
- **所有日期查询参数必须是整型**。字符串 `'20230101'` 与 `INT` 列比较会隐式转换并绕过索引。
- **每阶段独立可回滚**。Phase 1 之后任何阶段出问题，都不能让系统退回"全量返回"。

---

## Phase 0：基线度量

**先做这个，否则后面全是无根据的优化。** 这一阶段的产出是答辩材料的一半。

### Task 0.1: 记录优化前的真实数字 ✅ 已完成

**Files:**
- Create: `docs/perf/kline-baseline.md`（已完成）
- Create: `docs/perf/measure.mjs`（接口层，可复跑）
- Create: `docs/perf/bench-frontend.mjs`（前端处理开销，可复跑）

**实测结果**（详见基线文档）：

| 场景 | 实测 | 推翻的原假设 |
|---|---|---|
| 日线 600519.SH | 1,558 根 / 332 KB / 43.9 ms | — |
| 日线 000012.SZ（最大） | 5,071 根 / **1,016 KB** / 122.7 ms | 原以为最大 358 KB |
| 1 分钟线 | **240 根** / 21.8 KB / 526 ms | 原以为 5,000 根、1.15 MB |
| 数据库查询 | 5,071 行 **6.03 ms**；727 行 0.97 ms | — |
| 前端指标（reactive） | 91.4 ms；裸数组仅 11.2 ms（**8.2×**） | — |

**归因**：DB 2.8% / 传输序列化 54.7% / 前端处理 42.5%。**数据库不是瓶颈**，这一点必须在答辩时讲清楚。

- [x] **Step 1**: 接口层 5 次取中位数，已写入 `kline-baseline.md`
- [x] **Step 2**: 前端开销用 Node + 真实 `@vue/reactivity` 复现（比浏览器录制更可复现、可对比），已记录裸数组 vs reactive 的 8.2 倍差异
- [x] **Step 3**: `EXPLAIN` 的 `key_len=164`、索引 583 MB 已确认
- [x] **Step 4**: 浏览器端已补测（2026-11-04，puppeteer + CDP Performance）：
  - 首屏 `ScriptDuration` **188.8 ms** / `TaskDuration` 473.4 ms
  - 切股票（600519→002636）增量：`Script` 110.9 ms / `Task` 196.5 ms
  - 这两项覆盖了 Node 基准缺失的 `series.setData()` 与 canvas 绘制部分

---

## Phase 1：数据库层瘦身

风险最低、可独立验证、且是后续所有优化的地基。

### Task 1.1: 执行迁移 ✅ 已完成

**Files:**
- Create: `sql/migrations/V6__slim_daily_columns.sql`（遵循项目 `V{n}__` 约定）
- Create: `sql/migrations/V6__slim_daily_columns_rollback.sql`
- Modify: `sql/data_db.sql` — 建表语句同步为新结构，否则新环境会退回旧定义

**实际结果**：1 分 38 秒完成，`key_len` 164 → **42**，行数 7,663,187 不变，抽样数值一致。

**但索引体积只从 583 MB 降到 498 MB（-15%），不是预期中的 150 MB，查询耗时也持平。** 原因见 ADR-0003「实测结果」：`key_len` 报的是索引键最大可能字节数，而 InnoDB 的 VARCHAR 实际是变长存储，ASCII 内容只占 1 字节/字符。本任务没有带来性能提升，价值在类型语义正确。

- [x] **Step 1**: `mysqldump --single-transaction` 备份 1.1 GB 到 `.workbuddy/tmp/stock_daily_data_backup_20261103.sql`，8 秒完成，尾部有 `Dump completed`
- [x] **Step 2**: 执行 ALTER，实测 1 分 38 秒
- [x] **Step 3**: `key_len=42` 已验证；但索引体积只降 15%
- [x] **Step 4**: 行数 7,663,187 一致；600519.SH 最新三行数值正确

### Task 1.2: 修正所有受影响的读写路径 ✅ 已完成

**实际改动比计划小**：因为 API 层保持返回 `"yyyyMMdd"` 字符串（`formatTradeDate`），**`PracticeSnapshotLoader` 无需修改**，前端零改动。这是「表结构是内部实现，API 是外部契约」的直接收益。

**Files（实际）：**
- `StockDailyData.java` — `tradeDate` String → Integer
- `StockDataController.java` — 新增 `parseTradeDate`（入参转 Integer，非法格式 400）+ `formatTradeDate`（出参转回字符串）；顺带兼容 `2025-01-01` 写法
- `TushareSyncServiceImpl.java` — `isTradeDateAlreadySynced`、`parseDailyData`、`getLatestTradeDate` ×2 共 4 处转整型
- `StockDataInitializer.java` — `setTradeDate` 转整型

- [x] **Step 1**: 全库 grep 出 17 处引用，逐个确认
- [x] **Step 2**: 编译通过（首次失败于 `getLatestTradeDate` 返回类型，已修）
- [x] **Step 3**: 重启 data-service，验证：非法日期返回 400、连字符格式被接受、tradeDate 仍为字符串、Feign 链路 200
- [x] **Step 4**: trade-service 测试套件全绿（见下）
- [x] **Step 5**: 练习模块端到端已通过（admin/admin123，2026-11-04）：
  - 创建练习 `600519.SH` 2026-10-01~11-03 → 200，`currentDate=2026-09-30`
  - 推进一天 → `currentDate=2026-10-08`，**正确跳过国庆假期**，`version` 0→1
  - 结束 → `status=FINISHED`，测试会话已按 id 精确删除，`practice_session` 归零
  - 这条链路正是 `PracticeSnapshotLoader` 拉 3 年区间做连续性校验的地方，**说明 Phase 1 的 `trade_date` 类型改动没有破坏下游**

**一个必须记住的坑**：`parseTradeDate` 对非法格式直接抛 400，而不是静默忽略条件。静默忽略会让区间查询退化成全量查询，是最难排查的性能故障。

---

## Phase 2：后端接口收窄

### Task 2.1: `/stock/daily` 支持视窗参数 ✅ 已完成

**实测验证的四种语义**（缺一不可，第三种是练习模块的命脉）：

| 请求 | 返回 | 体积 |
|---|---|---|
| 无参数 | 620 根，20240411→20261103 | 146.7 KB |
| `limit=100` | 100 根，20260605→20261103 | 23.7 KB |
| 区间 2025 全年，无 limit | 243 根（区间全量，不截断） | 57.5 KB |
| `endDate=20240410&limit=500` | 500 根，20220318→20240410 | 118 KB |

**踩到的坑：`ORDER BY ... ASC LIMIT 620` 取到的是"最早 620 根"**（上市初期），不是最近的。必须 `DESC LIMIT` 再反转。这是语义错误，不是性能问题。

**另一个坑：改 `common-api` 的 Feign 签名后必须 `mvn install`**，只 `compile` 只能更新 target/classes，而 trade-service 运行时加载的是 `~/.m2` 里的旧 jar，表现为 `NoSuchMethodError`。

- [x] **Step 1**: 实现参数解析 + `DESC LIMIT` + 内存反转
- [x] **Step 2**: 空结果返回空数组，保持前端容错逻辑
- [x] **Step 3**: 620 根实测 146.7 KB（原估 143 KB，接近）

### Task 2.2: 全历史缩略条接口 ✅ 已完成

端点实际放在 `StockDataController` 的 `/data/stock/daily/sparkline`（没另建 controller，路径与 `/daily` 不冲突），并补了 trade-service 的转发端点。

**实测：1,559 根 → 240 点，6.3 KB，37 ms，价格区间 1168.6 ~ 2601.0。**

- SQL 只 `SELECT trade_date, close_price`，避免回表
- 服务端 LTTB 降采样
- **未做独立缓存**：原计划「几乎永不变，可缓存一整天」，但单次查询只有 37 ms、6.3 KB，加缓存的复杂度高于收益。若之后 QPS 上来再补。

- [x] **Step 1**: LTTB 实现（按三角形面积选点，保形性优于等距采样）
- [x] **Step 2**: 实测 240 点 / 6.3 KB（原估 4 KB，接近）

### Task 2.3: 分钟线改为缓存优先 ✅ 已完成

**Phase 0 实测推翻了原设计。** 1 分钟线实际只返回 **240 根 / 21.8 KB**——`lmt=5000` 对它不起作用，东方财富对 1 分钟线只提供当日数据。「限制根数」和「聚合降级」都是在打空靶。

真正的症状是 **249–1013 ms 的延迟抖动（3 倍）**，全部来自外部 API。

**实际做法：**
- 进程内 `ConcurrentHashMap` 缓存，TTL **10 秒**，key `{code}:{period}`
- 上游返回空时回退到过期缓存（stale），且**刻意不刷新 expireAt**——否则上游持续失败时过期数据会被不断"续命"成永久缓存
- `HttpClient` 补上 `requestTimeout=3s`（原先只有 `connectTimeout`，连接能建上但服务端不返回时会挂到超时上限）
- 未用 Redis：240 根 / 22 KB / 10 秒 TTL，跨实例共享的收益抵不上一次网络往返

**实测（连续 3 次）：253 ms → 4.0 ms → 4.5 ms，约 60 倍。**

- [x] **Step 1**: 缓存命中验证
- [x] **Step 2**: 连接与请求超时都设为 3 秒
- [x] **Step 3**: 空结果回退 stale（代码已实现；上游故障需断网才能实测）

---

## Phase 3：缓存

### Task 3.1: 接入 Redis ✅ 已完成

**`common-api` 的 redis 依赖是 `provided` scope，不会传递**——原文写的「确认无需新增」是错的，必须在 `data-service/pom.xml` 显式声明，否则 `StringRedisTemplate` 根本没有对应 jar。

- [x] **Step 1**: `dependency:tree` 确认无传递 → 显式声明 `spring-boot-starter-data-redis`
- [x] **Step 2**: L1 `ConcurrentHashMap`（500 条 / 10 min）+ L2 Redis（`kline:daily:{code}` / 30 min）
- [x] **Step 3**: `application.yml` 加 `spring.data.redis`，默认 127.0.0.1:6379

**L1 未用 Caffeine**：功能等价（map + TTL + 容量上限），省一个依赖；W-TinyLFU 在 500 条 / 10 分钟这个尺度上的命中率差异可忽略。

**TTL 从「到收盘」改为 10/30 分钟**：原设计会让收盘后刷新的缓存存活到次日 15:30，而同步任务在 18:00 才写库——用户整晚看到旧数据。详见 ADR-0004「TTL 修正」。

**实测：190.9 ms → 3.7 ms（约 45 倍）；000012.SZ 28.2 ms → 3.9 ms。Redis 中确认写入 `kline:daily:600519.SH` 等 key。**

### Task 3.2: 收敛数据访问入口 ✅ 已完成

- `repository/KlineQueryRepository.java` — 纯 DB 查询（`selectRecent` / `selectRange`），SQL 不再散落在 controller
- `service/KlineQueryService.java` — 参数规整 + 缓存装饰
- `StockDataController` — 只保留参数解析与 `toMap`，`queryDaily` 已删除

这是 ADR-0005「分片就绪」的落地：按 `stock_code` 分片时只需替换 Repository 实现。

- [x] **Step 1**: `selectRecent(code, n)` / `selectRange(code, from, to, limit)` 两个方法
- [x] **Step 2**: 缓存装饰在 Service 层，Repository 保持纯粹
- [x] **Step 3**: 空结果缓存——**已决策不做，不是遗漏**。只有输错股票代码才产生空结果，频率极低；而空标记会让 L1 的 `size() >= want` 判断失效（空 list 永远不满足），要为此增加一条独立分支。收益不抵复杂度，已记入 ADR-0004 的已知缺口。

---

## Phase 4：前端

### Task 4.1: 消除深度响应式 ✅ 已完成

`const dailyData = ref<CandleItem[]>([])` → `shallowRef`。

`dailyData` 只在 `loadData` / `loadOlder` 里整体赋值、在 `refreshIndicators` / `useInfoPanel` 里整体读取，从没有对单个 bar 做响应式修改，深度代理纯属浪费。Phase 0 实测这个浪费是 **91.4ms vs 11.2ms（8.2 倍）**。

- [x] **Step 1**: 改 `shallowRef`，类型检查通过
- [x] **Step 2**: 浏览器实测（CDP `Performance.getMetrics`，2026-11-04）

**实测结论：这个改动的收益已经被窗口化吃掉了，不是一个可测量的优化。**

| 指标 | `shallowRef`（A） | `ref`（B） | 差异 |
|---|---|---|---|
| 首屏 ScriptDuration | 188.8 ms | 179.5 ms | B 快 5% |
| 首屏 TaskDuration | 473.4 ms | 459.3 ms | B 快 3% |
| 切股票 Script 增量 | 110.9 ms | 106.0 ms | B 快 4% |
| 切股票 Task 增量 | 196.5 ms | 190.7 ms | B 快 3% |

**B 组反而更快，差异在噪声范围内。** 原因是两个优化在收益上**重叠而非叠加**：

- Phase 0 测出 8.2 倍差异时，数据是 **5,071 根**，Proxy 开销约 80 ms。
- 窗口化之后只有 **620 根**，Proxy 开销按比例降到约 10 ms。
- 而这 10 ms 淹没在 190 ms 的 Script 总量里（图表渲染、DOM、其他组件），测不出来。

**这条结论比"快 8 倍"更有价值**：它说明先做的窗口化让后续的响应式优化失去了意义。若只做 `shallowRef` 不做窗口化，省的是 80 ms；先做窗口化，这 80 ms 自动变成 10 ms。

实现保留 `shallowRef`——语义上更正确（本来就不需要对单个 bar 做响应式修改），只是不该把它当成性能卖点写进报告。

### Task 4.2: 视窗化加载 ✅ 已完成

- 首次加载 620 根（500 视窗 + 120 前置），`applyInitialWindow` 把可视区间移到前置区之后
- `subscribeVisibleLogicalRangeChange` 监听，`range.from < 20` 时向前追加 500 根
- 追加用 `series.setData()` 全量替换

**两个必须处理的副作用（都踩过）：**
1. `setData` 会把 `timeScale` 重置为「显示全部」，用户当前浏览位置会丢。必须记录 `getVisibleLogicalRange()`，按新增根数偏移后恢复。
2. `onMounted` 里 `loadData()` 没有 await，导致成交标记在数据落地前加载、范围过滤拿到空值。已改为 `loadData().then(() => refreshAutoMarkers())`。

- [x] **Step 1**: 抽 `toCandles` / `toVolumes` / `candlesToVolumes` 供首次加载与左滑共用
- [x] **Step 2**: `loadingMore` 锁防止快速拖拽触发并发请求
- [x] **Step 3**: 返回 0 条或全部重复时置 `hasMoreHistory=false`，停止请求
- [x] **Step 4**: 端到端验证：`endDate=20240410&limit=500` 返回 500 根（20220318→20240410，109 KB，86 ms）

### Task 4.3: 指标按区间计算 ✅ 目标已达成（实现路径不同）

原计划是在前端把 5,000 根裁到「视窗 + 前置」再算。实际改为**从数据源头就只取 620 根**——后端 `limit=620`，前端拿到的就是目标区间，`refreshIndicators` 全量算这 620 根即可。

**这条比原计划更彻底**：既省掉前端的裁剪逻辑，又省掉 4,451 根的传输与解析。不需要 `calcWithLookback`。

- [x] **Step 1**: 无需实现（数据源头已收窄到 620 根）
- [x] **Step 2**: 实测左边缘连续——620 根中 `idx=120`（视窗起点）MA60=**1434.81** 为有效值，前置区 120 根 > MA60 所需 59 根，余量充足

### Task 4.4: 全貌缩略条 ✅ 已完成

`components/chart/SparklineStrip.vue` — SVG 折线，viewBox 0 0 1000 40，`preserveAspectRatio="none"` 横向拉伸；圆点用 `vector-effect: non-scaling-stroke` 锁定像素尺寸，否则会被拉成椭圆。

- [x] **Step 1**: 240 点折线，价格区间留 4% 上下边距防贴边
- [x] **Step 2**: `windowFrom`/`windowTo` 比例高亮当前视窗（由 `visibleLogicalRangeChange` 换算成日期比例）
- [x] **Step 3**: 点击跳转 —— 用首末日期线性插值定位 x，而不是去降采样点里找同日期（240 点覆盖不了每根 K 线）
- [x] **Step 4**: 同时把买卖点画在缩略条上，解决「早于 620 根的成交在主图上看不到」的问题（ADR-0002 的方案 c）

---

## Phase 5：验收

| 指标 | 基线 | 目标 | 实测 | 判定 |
|---|---|---|---|---|
| 日线 000012.SZ 响应体积 | 1,016 KB | < 150 KB | 146.7 KB | ✅ |
| 日线 600519.SH 响应体积 | 332 KB | < 150 KB | 146.7 KB | ✅ |
| 日线响应耗时（单请求） | 122.7 ms | < 50 ms | 100–150 ms | ❌ 未达标 |
| 日线响应耗时（缓存命中） | — | — | 3.7 ms | ✅ |
| 前端指标计算 | 91.4 ms（reactive） | ≈ 11 ms | 待浏览器实测 | ⏳ |
| 分钟线中位数延迟 | 526 ms | < 50 ms | 4.0 ms（命中） | ✅ |
| 索引体积 | 583 MB | ≈ 150 MB | 498 MB | ❌ 原估有误 |
| `key_len` | 164 | 42 | 42 | ✅ |
| 50 并发 P99（无缓存全打库） | — | 不雪崩 | **89 ms / 0 错误 / 877 req/s** | ✅ |

**未达标的两项要如实记录**：
1. **日线单请求耗时 100–150 ms 未达 50 ms 目标**。体积降了 86%，但 Feign 转发（7 ms）+ 620 次 `toMap` + 146 KB 序列化是固定成本。要压只能改 DTO 或去掉 Feign 中转层，收益不划算。
2. **索引体积 498 MB 未达 150 MB**。ADR-0003 里按 `key_len` 线性换算体积的算法是错的——`key_len` 是最大可能字节数，而 VARCHAR 实际变长存储。详见该 ADR 的「实测结果」。

- [x] **Step 1**: 接口层已用 `measure.mjs` 同法复测并填入 `kline-baseline.md`；浏览器 Performance 项仍缺
- [x] **Step 2**: 并发验证——50 并发 200 请求，**0 错误**，P50 42 ms / P95 80 ms / P99 89 ms，吞吐 877 req/s，连接池未打满
- [x] **Step 3**: 回滚演练——Redis 关闭时实测降级：接口仍返回 200 + 620 根正确数据，日志记录「降级查库」「写入 L2 失败不影响本次结果」。系统退化为「可用但慢」，不是「不可用」

---

## 明确不做的事

- **不分库分表、不做读写分离**：见 ADR-0005，当前量级距判据还有 2.6 倍行数余量
- **不引入 gh-ost / pt-online-schema-change**：库无持续写入，停机 5 分钟成本更低
- **不换时序数据库**：日线 766 万行是 MySQL 的舒适区；只有当分钟线落库（3 亿行/年）时才需要重新评估，那时该换的是列式存储而非分片
- **不做前端虚拟滚动**：`lightweight-charts` 已按像素抽稀，渲染不是瓶颈
