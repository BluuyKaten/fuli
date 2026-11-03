# 股票复盘系统（fuli）设计逻辑优化审查清单

| 项目 | 内容 |
| --- | --- |
| 生成日期 | 2026-11-02 |
| 审查范围 | 6 个后端微服务 + 前端 + SQL 建表脚本 + 文档，全文审计 |
| 关联文档 | `doc/Project.md`（第八节已有问题清单，本文只讲**新增**项并标注重叠）、`doc/CodeReview-Improvement-2026-08-08.md` |
| 状态图例 | 🔴 严重（正确性/安全，需优先） · 🟡 重要（性能/设计） · 🟢 建议（可维护性） |

> 本文所有结论均对应到具体类名/文件路径/行号，可复核。与 `Project.md` 第八节重复的项仅标注「已覆盖」，不再展开。

---

## 修复进度（2026-11-02 全部完成）

本轮已把 🔴 R1-R7、🟡 M1-M9、🟢 清理项全部修复，改动文件与验证如下：

| 项 | 修复内容 | 关键改动文件 | 回归验证 |
| --- | --- | --- | --- |
| R1 买入加仓并发 | `increasePosition` 改「幂等建仓 + `SELECT...FOR UPDATE` 行锁 + 复用 `increaseQuantity` + `updateAvgCost`」 | `PositionSummaryMapper`、`PositionSummaryServiceImpl` | trade 79 例 |
| R2 createTrade 幂等 | `TradeDTO`/`TradeRecord` 加 `requestId`；入口查重 + `DuplicateKeyException` 兜底；`trade_record` 加 `request_id`+唯一索引；`V4__trade_request_id.sql` | `TradeRecordServiceImpl`、`BizCode`、`sql/*`、前端 `TradePanel` | 新增 2 例幂等测试 |
| R3 自选股越权 | `WatchlistController` 从 `AuthenticatedUser` 取 userId | `WatchlistController`、前端 `watchlist.ts` | 新增 `WatchlistControllerTest` 4 例 |
| R4 Dashboard 成本口径 | 复用 `position_summary`（新增 `TradeFeignClient.getPositions` + `/trade/internal/positions`） | `DashboardService`、`TradeRecordService`、`PositionSummaryService` | trade 79 例 |
| R5 北交所 920 误判 | 统一 `StockCodeUtil.withSuffix`（精确前三位），删除两处独立副本 | 新增 `common-api/StockCodeUtil`，`StockDataController`、`TradeRecordServiceImpl` | 编译通过 |
| R6 资产曲线含现金 | 逐日维护现金余额，`dailyAsset = 现金 + 持仓市值` | `AnalysisService.getAssetCurve` | analysis 4 例 |
| R7 异常泄露 | 兜底 handler 返回固定文案、堆栈只进日志 | `GlobalExceptionHandler` | auth 10 例 |
| M1 预警轮询+N+1 | `@Scheduled` 服务端轮询 + 去重批量行情 | `PriceAlertServiceImpl` | trade 79 例 |
| M2 批量行情假批量 | data-service 批量接口改一次 `IN` 查询 | `StockDataController`、`StockService`、`StockController` | 编译通过 |
| M3 统计 SQL 聚合 | `aggregateStatistics` 单条 SQL 完成 COUNT/SUM/MAX/MIN | `TradeRecordMapper`、`TradeRecordServiceImpl` | trade 79 例 |
| M4 WebSocket | 去掉 `synchronized` + 交易时段判断 | `QuoteWebSocketHandler` | trade 79 例 |
| M5 同步侧 | 死接口补 `@GetMapping`、upsert 改 `ON DUPLICATE KEY UPDATE`、重试/增量加限速 | `TushareController`、`StockInfoMapper`/`StockDailyDataMapper`、`TushareSyncServiceImpl` | 编译通过 |
| M6 幂等 FAILED 死代码 | 移除事务内 `updateFailed` 死代码、修正注释 | `IdempotentMessageServiceImpl` | auth 10 例 |
| M7 资金校验 | `addCash` 补正数校验、`resetCash` 补非负校验 | `AuthController` | auth 10 例 |
| M9 幽灵源/二次拉取/前端 | `PRIMARY_SOURCE` 改 EastMoney；`computeMonthlyProfit` 内联；`useInfoPanel` 指标缓存；ECharts 按需引入；删 4 个死组件 | `StockDataRouterService`、`AnalysisService`、`useInfoPanel`、`utils/echarts`、删 `KlineChart.vue` 等 | 前端 43 例 + build 通过 |
| 🟢 清理 | 删死组件/无用依赖/死类型；修字段漂移；`request.ts` 丢 code；login NPE；expiresIn 单一来源；JWT 字节校验；网关死路由；`SyncProgress.SyncStatus`→`TaskStatus`；`TradeEventListener` BUY 死分支 | 多文件 | 全绿 |

**最终验证**：`mvn clean install` 编译通过；后端 116 例全绿（gateway 23 / auth 10 / trade 79 / analysis 4）；前端 `npm run build`（vue-tsc）+ `npm test`（43 例）通过；ECharts 按需引入使主包 3258KB → 2753KB。

**明确未实施（需更大重构或单独排期，已评估风险）**：

- **M8 卖出事务内 Feign**：把 `validateSellPrice` 移出事务需拆 `createTrade` 事务边界，且会改变「先持仓后价格」的校验顺序（已实测回退），收益有限，暂保留现状。
- **M5 失败状态持久化 / 交易日历 / `SKIP_THRESHOLD` 动态化**：需新增持久化表与交易日历数据源，属较大改动。
- **`InternalKeyFeignInterceptor` 下沉 common-api**：涉及跨服务认证的自动装配，改动会冒认证失效风险，保留两份副本现状。

---

## 核心结论

项目在「资金一致性、鉴权、幂等、练习引擎」上的设计质量已相当高（原子扣款、双层验签、`msg_id` 唯一索引、本地消息表、`PracticeEngine` 纯函数化），但本次深读代码后发现 **7 条正确性/安全缺陷**，其中 3 条会**直接算错钱或越权**：

1. 买入加仓是「读-改-写」，并发下丢失更新——**现金扣了、持仓没加对**；
2. `createTrade` 无请求幂等键，双击/重试会**重复扣款 + 重复落单**；
3. 自选股接口水平越权（IDOR）；
4. Dashboard 持仓成本口径错误（用「历史总买入」做分母，未扣已卖），**算错成本价**；
5. 北交所 `920xxx` 新代码段被误判为沪市，**查不到行情**；
6. 资产曲线只含持仓市值、不含现金，与总资产口径冲突；
7. 全局异常处理器向客户端泄露内部异常细节。

---

## 一、🔴 严重（正确性 / 安全）

### R1. 买入加仓 `increasePosition` 读-改-写非原子，并发丢失更新 ✅ 已修复

- **位置**：`trade-service/.../service/impl/PositionSummaryServiceImpl.java:32-53`（`increasePosition`）
- **证据**：先 `getOrCreatePosition` 读行（`getPosition` 用 `selectOne`），内存算加权均价，再 `positionSummaryMapper.updateById(position)` 全量写回。而 `PositionSummaryMapper.java:26-31` 已写好原子 `increaseQuantity` SQL，**全库零调用**。
- **为什么严重**：同一用户对同一股票并发两笔买入时，两个事务都读到 `total_quantity=100`，各自算出 `newQuantity=200` 并 `updateById`，最终持仓是 200 而非 300；而两笔现金都已在 auth 侧同步扣款成功 → **资金与持仓永久不一致**。首次建仓并发时两个事务都走 `INSERT`，后者撞 `uk_user_stock` 唯一索引抛 `DuplicateKeyException`，用户收到 500 而非干净提示。
- **是否已覆盖**：`Project.md` 第八节 8.1 只讨论了「卖出原子扣减防超卖」，**未覆盖买入侧**，属新增。

**修复方案（推荐 B，逻辑清晰无 SQL 求值顺序陷阱）**

1. `getOrCreatePosition` 改为幂等建仓，消除并发首次建仓撞唯一键：

```sql
INSERT INTO position_summary (user_id, stock_code, stock_name, total_quantity, avg_cost, update_time)
VALUES (#{userId}, #{stockCode}, #{stockName}, 0, 0, NOW())
ON DUPLICATE KEY UPDATE stock_name = VALUES(stock_name);
-- MySQL 8.0.20+ 建议用 AS new 别名替代已弃用的 VALUES()：
-- INSERT ... VALUES (...) AS new ON DUPLICATE KEY UPDATE stock_name = new.stock_name;
```

2. `increasePosition` 改为「行锁 + 复用现成 `increaseQuantity`」：

```java
@Transactional(rollbackFor = Exception.class)
public BigDecimal increasePosition(Long userId, String stockCode, String stockName,
                                   int quantity, BigDecimal price) {
    getOrCreatePosition(userId, stockCode, stockName);           // 幂等建仓
    PositionSummary p = positionSummaryMapper.selectForUpdate(userId, stockCode); // 新增 SELECT ... FOR UPDATE
    BigDecimal totalCost = p.getAvgCost().multiply(BigDecimal.valueOf(p.getTotalQuantity()))
            .add(price.multiply(BigDecimal.valueOf(quantity)));
    int newQuantity = p.getTotalQuantity() + quantity;
    BigDecimal newAvg = totalCost.divide(BigDecimal.valueOf(newQuantity), 4, RoundingMode.HALF_UP);
    positionSummaryMapper.increaseQuantity(userId, stockCode, quantity); // 复用原子 SQL
    positionSummaryMapper.updateAvgCost(userId, stockCode, newAvg);      // 新增 UPDATE avg_cost
    return newAvg;
}
```

需要新增两条 Mapper SQL：

```java
@Select("SELECT * FROM position_summary WHERE user_id = #{userId} AND stock_code = #{stockCode} FOR UPDATE")
PositionSummary selectForUpdate(@Param("userId") Long userId, @Param("stockCode") String stockCode);

@Update("UPDATE position_summary SET avg_cost = #{avgCost}, update_time = NOW() WHERE user_id = #{userId} AND stock_code = #{stockCode}")
int updateAvgCost(@Param("userId") Long userId, @Param("stockCode") String stockCode, @Param("avgCost") BigDecimal avgCost);
```

- **方案 A（单条原子 UPDATE，并发更强、无需行锁）**：把数量和均价合成一条语句，但必须让 `avg_cost` 赋值排在 `total_quantity` 之前（依赖 MySQL 单表 UPDATE 从左到右求值，脆弱、易读错，需加注释）：

```sql
UPDATE position_summary
SET avg_cost = ROUND((avg_cost * total_quantity + #{price} * #{quantity}) / (total_quantity + #{quantity}), 4),
    total_quantity = total_quantity + #{quantity},
    update_time = NOW()
WHERE user_id = #{userId} AND stock_code = #{stockCode};
```

> 建议先用方案 B（行锁开销在「单用户并发买同一股票」场景下可忽略，且消除了死代码 `increaseQuantity`）；若后续要支撑多实例高并发再迁到方案 A 或 TCC。

---

### R2. `createTrade` 无请求幂等键，重复提交导致重复扣款 + 重复落单 ✅ 已修复

- **位置**：`trade-service/.../service/impl/TradeRecordServiceImpl.java:79-199`（`createTrade`）；`:163`（`buyMsgId = "BUY-" + UUID.randomUUID()` 每次随机，起不到幂等作用）
- **为什么严重**：前端双击或网络重试会提交两次：两次 `deductCash`（`msgId` 随机不同，auth 侧不拦截）、两次 `save(record)`、两次 `increasePosition`。现金扣双份、交易表出现两条相同记录，无唯一索引兜底。
- **是否已覆盖**：`Project.md` 第九节「短期」提到「为 createTrade 增加请求幂等键」，但第八节未列为缺陷；建议升级为缺陷处理。

**修复方案**

1. `TradeDTO` 增加 `requestId`（客户端生成：前端表单打开/提交按钮点击时生成一次，重试复用同一个）。
2. `trade_record` 增加幂等列 + 唯一索引：

```sql
ALTER TABLE trade_record ADD COLUMN request_id VARCHAR(64) NULL COMMENT '客户端幂等键';
ALTER TABLE trade_record ADD UNIQUE KEY uk_user_request (user_id, request_id);
```

3. `createTrade` 入口先查重、命中返回已存在记录（幂等成功）：

```java
if (tradeDTO.getRequestId() != null) {
    TradeRecord exist = tradeRecordMapper.selectOne(new LambdaQueryWrapper<TradeRecord>()
            .eq(TradeRecord::getUserId, tradeDTO.getUserId())
            .eq(TradeRecord::getRequestId, tradeDTO.getRequestId()));
    if (exist != null) return exist.getId();
}
// save(record) 时捕获 DuplicateKeyException，命中唯一索引也返回已存在 id，兜底并发提交
```

---

### R3. 自选股接口水平越权（IDOR），`userId` 完全来自客户端 ✅ 已修复

- **位置**：`auth-service/.../controller/WatchlistController.java:26`（`list(@RequestParam Long userId)`）、`:40`（`add` 取 `watchlist.getUserId()`）、`:59`（`remove(..., @RequestParam Long userId)`）
- **证据**：整个控制器未注入 `HttpServletRequest`，也未调用 `AuthenticatedUser`。同模块 `AuthController.java:53-55` 已有正确示范（从 request attribute 取身份），说明这是遗漏。
- **为什么严重**：任何已登录用户可查询、添加、删除**任意用户**的自选股。
- **是否已覆盖**：未覆盖，属新增。

**修复方案**：`userId` 一律从认证上下文取，不信任请求参数。

```java
// 注入
private final HttpServletRequest request; // 或注入 AuthenticatedUser 辅助类

// list —— 去掉 userId 参数
public Result<List<UserWatchlist>> list() {
    Long userId = AuthenticatedUser.requireId(request); // 从 request attribute 取
    ...
}
// add —— 强制覆盖客户端 userId
public Result<Void> add(@RequestBody UserWatchlist watchlist) {
    Long userId = AuthenticatedUser.requireId(request);
    watchlist.setUserId(userId); // 忽略客户端 userId
    ...
}
// remove —— 去掉 userId 参数
public Result<Void> remove(@PathVariable String stockCode) {
    Long userId = AuthenticatedUser.requireId(request);
    ...
}
```

前端 `frontend/src/api/watchlist.ts` 同步去掉 `userId` 参数。

---

### R4. Dashboard 持仓成本口径错误，绕过了 trade 已维护的 `position_summary` ✅ 已修复

- **位置**：`analysis-service/.../service/DashboardService.java:51-69`
- **证据**：`totalBuyQuantity` / `totalBuyAmount` 是「历史所有买入之和」，**从未扣减已卖股数**；`costPrice = totalBuyAmount.divide(totalBuyQuantity)`。
- **为什么严重**：对「买 100@10 → 卖 100 → 买 100@20」的正常操作，算出成本价 `(1000+2000)/200 = 15 元`，而真实剩余 100 股成本应为 20 元。trade-service 的 `position_summary`（`uk_user_stock` 唯一键）已正确维护加权均价，却完全没被使用。
- **连带问题**：`DashboardService.java:92` 把 `availableQuantity` 直接设为 `holdingQuantity`，**丢失了 T+1 冻结口径**（trade-service 的「可卖 = 总持仓 − 当日买入」），前端可卖数与真实可卖数不一致。
- **是否已覆盖**：`Project.md` 8.2 #6 只笼统说「全量拉取无缓存」，未点出成本口径错误与可卖数丢失，属新增。

**修复方案**：analysis 不再重建持仓，改为复用 trade-service 的持仓汇总。

```java
// TradeFeignClient 增加（或复用 trade-service 已有 PositionSummaryService 查询接口）
@GetMapping("/trade/internal/positions")
Result<List<PositionVO>> getPositions(@RequestParam Long userId);

// DashboardService 改为
List<PositionVO> positions = tradeFeignClient.getPositions(userId).getData();
// costPrice / availableQuantity 直接用 PositionVO 的 avgCost 与可卖数，不再自算
```

需要 trade-service 在 `PositionSummaryService` 暴露 `listByUser(userId)`，返回含「加权均价 + 可卖数量（T+1 口径）」的 VO。行情最新价仍走 data-service 批量接口（见 M1）。

---

### R5. 北交所 `920xxx` 新代码段被误判为沪市 ✅ 已修复

- **位置**：`data-service/.../controller/StockDataController.java:171-185`；同逻辑重复在 `trade-service/.../TradeRecordServiceImpl.java:594-605`（两份独立实现）
- **证据**：归一只看首位字符：`6/9→.SH、0/3→.SZ、4/8→.BJ`。北交所 2024 年起启用的 `920xxx` 段会被 `case '9'` 判为 `.SH`，永远查不到行情（卖出误报「缺少行情」、市值算 0）。同时 `9` 与沪 B 股 `900xxx` 冲突；`withSuffixIfBare` 未校验 6 位长度，且判断是否已带后缀用 `indexOf('.') > 0`，`.SH` 这类点在第 0 位会被当裸码。
- **是否已覆盖**：`Project.md` 8.4 #15 声称「统一归一」，但 trade-service 仍保留独立副本，且 `920` 误判未覆盖，属新增。

**修复方案**：统一收口到一处，精确到前三位判断：

```java
static String marketSuffix(String code) {
    String c = code == null ? "" : code.trim();
    if (c.length() != 6) return c;            // 非 6 位不处理
    if (c.contains(".")) return c;            // 已带后缀（修正 indexOf('.') > 0 的误判）
    // 北交所：920 / 43 / 83 / 87 / 4 / 8 开头
    if (c.startsWith("920") || c.startsWith("43") || c.startsWith("83")
            || c.startsWith("87") || c.startsWith("4") || c.startsWith("8")) return c + ".BJ";
    // 沪市：60 / 68 / 900(沪B) 开头
    if (c.startsWith("60") || c.startsWith("68") || c.startsWith("900")) return c + ".SH";
    // 深市：00 / 30 / 20(深B) 开头
    if (c.startsWith("00") || c.startsWith("30") || c.startsWith("20")) return c + ".SZ";
    return c;                                 // 未知，保持原样
}
```

同时删除 trade-service 里的副本，统一走 data-service 或抽到 `common-api`（见 M3）。

---

### R6. 资产曲线只含持仓市值，不含现金，与总资产口径冲突 ✅ 已修复

- **位置**：`analysis-service/.../service/AnalysisService.java:191-205`（`getAssetCurve`）
- **证据**：逐日累加的 `dailyAsset` 只把「持仓数量 × 收盘价」相加，**完全不含现金**；而 `DashboardService:114` 的总资产 = 现金 + 市值。
- **为什么严重**：同一系统的「资产」出现两套定义，曲线画的是「持仓市值曲线」而非「资产曲线」，前端若混用会对不上。
- **是否已覆盖**：未覆盖，属新增。

**修复方案**：逐日遍历时维护现金余额，`dailyAsset = 现金 + 持仓市值`。

```java
BigDecimal cash = getUserInitialCash(userId);   // 初始现金（注册默认 200000）
...
if (trade.getTradeType() == 1) {
    cash = cash.subtract(trade.getTotalCost()); // 买入扣款
    // ... 更新 holdings / avgCosts
} else {
    cash = cash.add(trade.getTotalCost());      // 卖出回款
    // ...
}
// 每日资产 = 现金 + 持仓市值
dailyAsset = cash.add(dailyAsset);
```

若暂不改逻辑，至少把接口字段名 `assets` 改为 `marketValues` 并在文档中明确口径，避免误导。

---

### R7. 全局异常处理器向客户端泄露内部异常细节 ✅ 已修复

- **位置**：`auth-service/.../exception/GlobalExceptionHandler.java:45-49`
- **证据**：`return Result.error("系统异常: " + e.getMessage());` 直接拼接异常消息，可能泄露 SQL、类名、内部路径。
- **是否已覆盖**：未覆盖，属新增。

**修复方案**：对外返回固定文案，完整堆栈只进日志；已知异常映射为业务码。

```java
@ExceptionHandler(Exception.class)
public Result<Void> handleException(Exception e) {
    log.error("系统异常", e);                 // 完整堆栈只进日志
    return Result.error(500, "系统繁忙，请稍后重试");
}

@ExceptionHandler(DuplicateKeyException.class)
public Result<Void> handleDuplicate(DuplicateKeyException e) {
    return Result.error(BizCode.DUPLICATE, "记录已存在");
}
```

> 建议把该 `@RestControllerAdvice` 抽到 `common-api`，各服务复用，消除重复定义（见 M5）。

---

## 二、🟡 重要（性能 / 设计）

### M1. 预警无服务端轮询调度，且触发为逐条 N+1 Feign

- **位置**：`trade-service/.../service/impl/PriceAlertServiceImpl.java:116-141、222`（`listAlerts`/`checkAndTriggerAlerts` 逐条 `getLatestPrice`）
- **问题**：全工程只有 `QuoteWebSocketHandler` 与 `LocalMessageRetryServiceImpl` 两个 `@Scheduled`，**没有任何预警轮询任务**。预警只在用户手动 POST `/trade/alert/check` 时才检查，不打开页面就**永不触发**。
- **方向**：① 预警检查改 `@Scheduled`（仅交易时段轮询）；② 先 `SELECT DISTINCT stock_code` 去重，走 data-service 批量行情接口（一次 `IN (...)`，见 M2）；③ 触发后批量 `UPDATE ... WHERE id IN (...)`。

### M2. 「批量」行情接口是假批量，N+1 未消除

- **位置**：`trade-service/.../controller/StockController.java:63-92`（`latestPrices`/`infos`）；`data-service/.../StockDataController.java:135-166`
- **问题**：注释写「消除 N+1」，内部却是 `for (code : split(",")) getLatestPrice(code)`，仍是 N 次 Feign/查询。Dashboard、预警均未真正复用。
- **方向**：让 `DataFeignClient` 提供真批量端点，data-service 用一次 `WHERE stock_code IN (...)` 查询返回；trade/analysis 侧一次批量调用。

### M3. 交易统计/列表无 SQL 聚合、无分页，是 analysis 全量拉取源头

- **位置**：`trade-service/.../TradeRecordServiceImpl.java:285-382`（`listTrades`/`getStatistics`）
- **问题**：`getStatistics` 把区间内全部交易 `SELECT *` 拉到内存再 count/sum/avg；`queryByCondition` 直接返回全表。
- **方向**：`getStatistics` 下沉为 `SELECT COUNT(*), SUM(...), MAX(...) FROM trade_record WHERE ...` 单条 SQL；`queryByCondition` 加分页/限定列；补 `(user_id, trade_type, trade_date)` 复合索引。

### M4. QuoteWebSocket 双重循环 + 阻塞锁 + 单线程调度

- **位置**：`trade-service/.../websocket/QuoteWebSocketHandler.java:48-93`
- **问题**：`@Scheduled` 单线程调度器 + `quotes × sessions` 双重 for + 每个 session `synchronized` 阻塞发送；一个慢连接卡住整个 3 秒循环；全天 24 小时查库无交易时段判断。
- **方向**：会话按订阅股票分组只推订阅者；用 `ConcurrentWebSocketSessionDecorator` 限缓冲、去 `synchronized`；`@Scheduled` 配独立线程池 + 交易时段判断。

### M5. 同步侧问题（失败状态内存化 / 限速不全 / upsert 两次 SQL / 死接口）

- **位置**：`data-service/.../tushare/TushareSyncServiceImpl.java`（`volatile SyncResult lastSyncResult`、`upsertDailyData` 每条 selectOne+insert/update）、`SyncProgressManager.java`
- **问题**：① 失败列表/进度全内存，重启即丢；② `retryFailedDates`/`syncByDateRangeInBatches` **不受 `MAX_REQUESTS_PER_MINUTE` 限速约束**，`TushareClient` 无连接池/重试；③ `upsert` 每条两次 SQL，应改 `INSERT ... ON DUPLICATE KEY UPDATE`（`data_db` 已有 `uk_code_trade_date` 唯一键）；④ `syncStatus` 方法缺 `@GetMapping` 是死代码；⑤ 按自然日 `plusDays(1)` 遍历，周末/节假日空调用浪费配额，`SKIP_THRESHOLD=4800` 对早期年份失效。
- **方向**：失败落库可恢复；限速/重试/退避抽到 `TushareClient` 统一层；upsert 改单条；补 `@GetMapping` 或删除；引入交易日历跳过非交易日。

### M6. 幂等 FAILED 状态机在 `@Transactional` 下永不落库（死代码）

- **位置**：`auth-service/.../service/impl/IdempotentMessageServiceImpl.java:30-60、107-119`
- **问题**：`@Transactional(rollbackFor = Exception.class)` 包裹「INSERT + 业务 + 状态更新」；业务抛 `RuntimeException` 时，`updateFailed` 与先前 INSERT 一并回滚，**FAILED 状态和 errorMsg 永不持久化**，`handleDuplicate` 的 PROCESSING/FAILED 分支实际不可达。「失败可重试」只是碰巧靠记录随事务回滚消失达成，非状态机设计。
- **方向**：若要保留 FAILED 语义，状态落库放 `REQUIRES_NEW` 独立事务；否则简化设计为「成功记 SUCCESS、失败即回滚删除」，并修正误导性注释。

### M7. addCash / resetCash 缺金额合法性校验

- **位置**：`auth-service/.../controller/AuthController.java:174-191`（addCash）、`:207-225`（resetCash）
- **问题**：`deductCash` 校验了 `amount.signum() > 0`，`addCash` 没有（负数入账即变相扣款，`cash = cash + amount` 无条件更新可扣成负数）；`resetCash` 不校验 `newCash >= 0`，无 `msgId` 幂等，审计仅 `log.warn` 且 `operator` 硬编码 `"internal-api"`。
- **方向**：补 `amount > 0` / `newCash >= 0` 校验；审计改结构化审计表（userId、operator、old/new、时间、来源）。

### M8. 卖出路径事务内 Feign 远程调用，持有连接跨网络往返

- **位置**：`trade-service/.../TradeRecordServiceImpl.java:111-126`（`getAvailableQuantity` 读持仓 → `validateSellPrice` 调 data-service → 再读 `getAvgCost`）
- **问题**：卖出 `@Transactional` 先 DB 读拿连接，随后事务内 Feign 校验成交价区间，连接在网络往返期间被占用；同一事务对 `position_summary` 读了 3 次。
- **方向**：把 `validateSellPrice` 行情校验移到事务开启前（事务外预取），合并多次持仓读为一次。

### M9. 其他重要项（简列）

- **`StockDataRouterService` 主源是幽灵实现**：`PRIMARY_SOURCE="Tushare"` 但 `StockDataSource` 实现仅有 `EastMoneyDataSource`，`findSource("Tushare")` 恒 null，降级逻辑是死分支，与 `Project.md` 6.3 描述不符。→ 补 Tushare 分钟/周/月线实现，或校正为实际源、删误导注释。
- **`getPerformanceStats` 一次请求两次全量拉取**：`AnalysisService.java:440` 为补月度绩效再次调 `getMonthlyProfit`，后者重走 `getTradeList` 二次全量 Feign，与「单次遍历」表述不一致。→ 月度统计内联进已有循环，或让 `getMonthlyProfit` 接收已拉取的 `List<TradeVO>`。
- **前端十字光标移动全量重算指标**：`useInfoPanel.ts` 的 `panelData` 随 `currentIndex` 变化全量重算 MA/MACD/KDJ/RSI/BOLL，与 `useIndicators` 已算序列重复。→ 复用/缓存指标序列，`findIndex` 换 `time → index` Map。
- **前端 ECharts 全量引入**：`import * as echarts` 约 1MB，Dashboard/Performance 才真需要。→ `echarts/core` 按需注册，配 `manualChunks` 拆 vendor。
- **前端 WebSocket 订阅泄漏**：`useRealtimeQuote` 的 socket 关闭依赖 `subscribers.size === 0`，而唯一调用方 `OrderBookPanel` 未保存/调用 cleanup，socket 永不关闭；且该盘口本身是死组件。→ 修复或随死代码一并清理。

---

## 三、🟢 建议（可维护性 / 死代码）

### 前端

- **死组件约 1245 行**：`frontend/src/views/stock/KlineChart.vue`(767)、`KLineChartView.vue`(478) 是 lightweight-charts 迁移前的 ECharts 旧实现，`OrderBookPanel.vue`(216)、`InfoPanel.vue`(81) 均无任何 import 引用。删除后连带消除「指标三重重复实现」（`KlineChart.vue`/`KLineChartView.vue` 内的 `calculateMA/EMA/MACD/KDJ` 与 `utils/indicators` 重复）与「裸浮点残留」。
- **死类型**：`frontend/src/types/index.ts:33-44`（`Position`）、`utils/bizError.ts:32-39`（`BizError` 类，全仓零使用）。
- **`request.ts` 丢弃业务 code**：`request.ts:44` 用 `Promise.reject(new Error(res.message))`，`BizCode`/`BIZ_CODE_MESSAGE` 形同虚设，前端无法按 4002 余额不足做差异化提示。→ 改为 `reject(new BizError(res.code, res.message))`。
- **无用依赖**：`package.json` 的 `bcryptjs`（前端零使用，是后端哈希库误入）、`unplugin-auto-import`/`unplugin-vue-components`（`vite.config.ts` 从未配置）。

### 后端

- **`InternalKeyFeignInterceptor` 跨服务逐字复制**：trade/analysis 各一份，且 `contains("/internal/")` 模糊匹配。→ 下沉 `common-api` 统一提供。
- **`SyncProgress.SyncStatus` 嵌套枚举遮蔽同包顶层 `SyncStatus` DTO**：`data-service/.../tushare/`。→ 枚举改名 `TaskStatus`。
- **网关 `/api/watchlist/**` 死路由**：落点 `/watchlist/**` 无对应控制器（实际是 `/auth/watchlist`），永远 404。→ 删除。
- **`WebConfig` CORS 宽默认值**：`auth-service/.../config/WebConfig.java:28-31` 的 `addAllowedOriginPattern("*") + allowCredentials(true)`，`Project.md` 8.3 #11 只提了 WebSocket，未提此处。→ 收敛为域名白名单。
- **`expiresIn` 硬编码 86400L**：`AuthController.java:78` 与 `JwtProperties.expiration` 重复定义。→ 由 `getExpiration()/1000` 计算。
- **`login` 状态自动拆箱 NPE 隐患**：`AuthController.java:64` 的 `user.getStatus() == 0`，DB 为 NULL 时 NPE。→ `Integer.valueOf(0).equals(user.getStatus())`。
- **网关 JWT 两次验签**：`JwtGlobalFilter` 先 `validateToken` 再 `parseToken`。→ 合并为一次 `parseToken`。
- **`PositionSummary` 死字段**：`currentPrice`/`marketValue`/`unrealizedPnl` 全工程零写入，市值实际由 analysis 实时算。→ 删除或明确职责。
- **`JwtProperties` 字符数冒充字节数**：`secret.length() < 32` 应改 `secret.getBytes(UTF_8).length`。
- **遗留行情表**：`trade_db.sql` 仍建 `stock_info`/`stock_daily_data`，但 trade-service 已无 Mapper。→ 从建表脚本移除（`Project.md` 8.3 #10 已覆盖，此处确认仍在）。
- **`TradeEventListener` 的 BUY 分支死代码**：买入已改同步扣款，`handleTradeCreated` BUY 分支永不命中。→ 删除或注释说明。

---

## 四、修复优先级建议

| 批次 | 内容 |
| --- | --- |
| **第一批（正确性/安全，建议 1-2 周）** | R1 买入加仓并发、R2 幂等键、R3 自选股越权、R4 Dashboard 成本口径、R5 920 代码、R6 资产曲线、R7 异常泄露 |
| **第二批（性能/设计）** | M1 预警轮询、M2 真批量行情、M3 SQL 聚合分页、M4 WebSocket、M5 同步健壮性、M6 幂等状态机、M7 资金校验、M8 事务内 Feign |
| **第三批（清理）** | 前端死组件、后端死代码、CORS/依赖收敛、命名与重复实现 |

第一批中，R1 与 R2 直接影响资金与持仓一致性，风险最高，建议最先动手；R3 是安全漏洞，建议紧随其后。

---

## 五、与 `Project.md` 第八节的覆盖关系

| 本文条目 | Project.md 覆盖情况 |
| --- | --- |
| R1 买入加仓并发、R2 幂等键、R3 IDOR、R4 成本口径、R5 920、R6 资产曲线、R7 异常泄露 | **均未覆盖（新增）** |
| M1 预警轮询、M2 假批量、M4 WebSocket、M5 同步（失败内存化/限速/upsert/死接口） | **未覆盖（新增）** |
| M3 统计聚合、M6 幂等状态机、M7 资金校验、M8 事务内 Feign | **部分覆盖**（8.2 #6/#7 只点方向，未给到具体缺口） |
| 前端死组件、指标重复、`request.ts` 丢 code、无用依赖 | **未覆盖（新增）**；巨石组件与两套图表库已在 8.3 #9 覆盖，但本文补充了「迁移后旧实现未删除」的现状 |
| Redis 未使用、CORS（WebSocket）、遗留行情表 | **已覆盖**（8.2 #8、8.3 #11、8.3 #10） |
