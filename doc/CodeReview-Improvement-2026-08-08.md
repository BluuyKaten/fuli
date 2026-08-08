# 代码审查改进清单（2026-08-08 更新）

> 生成日期：2026-08-08
> 审查范围：全文审计（6 个后端微服务 + 前端 + 配置 + SQL + 文档）
> 关联文档：
> - `doc/CodeReview-Improvement-2026-08-07.md`（上一版改进清单，以下标注「已修复」的项来自该文档）
> - `doc/Project.md`（项目总览）

## 状态图例

- ✅ 已修复（截至 2026-08-08 工作树）
- 🔴 严重 — 必须修复
- 🟡 重要 — 应该修复
- 🟢 建议 — 锦上添花

---

## 一、正确性 / 安全（高优先级）

### ✅ 已修复项（来自 2026-08-07 清单）

| # | 问题 | 现状 |
|---|------|------|
| R1 | 删除/编辑交易时持仓与资金未回滚 | 已改为 `deleteTradeWithRollback`，反向事件 + 本地消息表 |
| R2 | 本地消息表缺少幂等保护 | `auth-service` 已实现 `msgId` 幂等（`IdempotentMessage`） |
| R3 | `validateBuyCash` 失败时降级跳过校验 | 已实现 fail-closed，由 `fuli.cash-validation-fail-strategy` 控制 |
| R4 | 持仓更新并发安全 | `PositionSummaryMapper` 已用原子 SQL `total_quantity >= #{quantity}` |
| R5 | 核心流程测试 | `TradeRecordServiceImplTest` 已覆盖主路径 |

### ✅ 已修复项（2026-08-08）

| # | 问题 | 现状 |
|---|------|------|
| R1 | 网关路由硬编码 localhost | 已改为 `lb://` 服务名 + 本地开发 profile |
| R2 | JWT/内部密钥弱默认值 | 已统一到 `common-api` 的 `JwtProperties`/`InternalKeyProperties`，启动校验 + local profile |
| R3 | 消息 payload 手写 JSON | 已强类型化 `CashChangeMessage` + Jackson 序列化/反序列化 |
| R4 | 删除回滚闭环 | `deleteTrade` 委托 `deleteTradeWithRollback`，反向事件携带幂等 msgId |
| R5 | 死信无告警 | 新增 `DeadLetterNotifier` SPI，默认日志实现，可插拔 webhook/邮件通道 |
| R6 | 前端浮点计算/类型重复/any 扩散 | 新增 `money.ts` 整数分计算；`TradeRecord` 去重并新增 `TradeRecordRequest`；LightweightChart 引入 lightweight-charts 强类型，`parseTime` 提到模块级 |

### 🔴 C1. 网关路由硬编码 localhost（已修复 ✅），服务发现形同虚设

- **文件**：`gateway-service/src/main/java/com/fuli/gateway/config/GatewayRouteConfig.java:24-39`
- **问题**：所有 `uri` 写死 `http://localhost:8081/8082/...`，但 `application.yml` 引入了 Nacos。生产/多实例下无法路由；Docker 容器内 `localhost` 指向网关自身，调用必败。
- **后果**：脱离单机开发环境即不可用，是「生产就绪」的硬伤。
- **修复方案**：
  1. 默认改为负载均衡形式 `uri("lb://auth-service")`，配合 Nacos discovery。
  2. 新增 `application-local.yml` profile，保留 `localhost` 用于本地开发。

### 🔴 C2. JWT 密钥在多处硬编码默认值，且与 .env.example 不一致

- **文件**：
  - `auth-service/.../util/JwtUtil.java:20` 默认 `your-256-bit-secret-key-here-...`
  - `gateway-service/.../util/JwtUtil.java:17` 默认 `default-secret-key-for-dev-only-...`
  - `trade-service/.../config/FuliProperties.java:13` 默认 `fuli-stock-internal-2025-secure-key`
  - `InternalKeyFeignInterceptor.java:14` / `InternalKeyFilter.java:28` 默认 `your-internal-key-here-change-me`
- **问题**：① 默认密钥写进源码，忘改配置就能伪造任意 JWT；② 网关与 auth 密钥默认值不同，却要互相解析，不一致会鉴权失败；③ `.env.example` 与代码默认值对不上。
- **后果**：鉴权体系基础可信度受损。
- **修复方案**：
  1. 启动时校验密钥长度 ≥ 32 且非默认值，否则启动失败（抛异常 / `System.exit`）。
  2. 网关与 auth 共用同一 `${JWT_SECRET}`，消除双密钥漂移。
  3. 移除源码中的「示例」默认值，改为 `@Value` 强制注入。

### 🔴 C3. trade-service 与 data-service 共用同一数据库的实体/Mapper

- **文件**：`data-service/.../tushare/TushareSyncServiceImpl.java:38-40`
- **问题**：`data-service` 直接注入并读写 `trade-service` 拥有的 `StockInfoMapper`、`StockDailyDataMapper`，破坏服务边界。
- **后果**：日后拆库或独立部署会立刻断裂，是耦合根源。
- **修复方案**（需规划）：行情表（`stock_info`、`stock_daily_data`）归属 `data-service` 自己的 `data_db`；`trade-service` 通过 Feign 获取行情。

---

## 二、一致性 / 生产就绪（中优先级）

### 🟡 I1. createTrade 的远程资金变动为异步，失败会不一致

- **文件**：`TradeRecordServiceImpl.java:67-173`
- **问题**：持仓更新与交易落库在同一本地事务，但资金变动是异步事件；若事件发布/重试失败，出现「持仓已扣、现金未扣」。
- **修复**：现有本地消息表已覆盖大部分，需补：死信告警（见 I7）+ 文档声明「最终一致」。

### 🟡 I2. 本地消息 payload 用字符串手写 JSON + 手动解析

- **文件**：
  - `TradeRecordServiceImpl.java:157`：`String.format("{\"userId\":%d,\"amount\":%s,...}")`
  - `LocalMessageRetryServiceImpl.java:83-123`：三个 `extractXxx` 用 `indexOf` 手动切割
- **问题**：特殊字符会解析错、字段变更要全改、无 schema。
- **修复**：定义强类型 `CashChangeMessage { userId, amount, msgId }`，用 Jackson 序列化/反序列化。

### 🟡 I3. 网关与服务层双 JWT 解析，职责重叠且密钥易漂移

- **文件**：`gateway-service/.../JwtGlobalFilter.java:51-65`、`auth-service/.../security/JwtAuthenticationFilter.java:43-60`
- **问题**：两套 `JwtUtil`、两套密钥；网关已校验的 token 服务层再验一次；除 auth 外其他服务未统一装 Spring Security。
- **修复**：统一由网关校验并透传 `X-User-Id`，服务层只信任该 Header（auth 内部接口用 InternalKey）；移除服务层 JWT 解析。

### 🟡 I4. 普通 deleteTrade 未走回滚，可被直接调用

- **文件**：`TradeRecordServiceImpl.java:192-194`
- **问题**：`DELETE /trade/{id}` 直接 `removeById`，持仓与现金都不回滚；前端/Swagger 可触发。
- **修复**：禁用裸删除，或让其内部委托 `deleteTradeWithRollback`；资金反向事件用 msgId 幂等。

### 🟡 I5. DashboardService 存在 N+1 调用与静默兜底

- **文件**：`analysis-service/.../service/DashboardService.java:31-122`、`:134-172`
- **问题**：① 每只持仓股票分别 Feign 取最新价/名称；② 全量拉交易在内存计算；③ Feign 失败静默回退到成本价/默认资金 `200000`，用户看到错误资产数而不知情。
- **修复**：批量查询行情；大账户预计算/缓存；Feign 失败返回明确错误而非静默兜底。

### 🟡 I6. 同步任务裸启线程 + 状态在内存

- **文件**：`TushareSyncServiceImpl.java:283`、`SyncProgressManager.java:24`
- **问题**：① `new Thread(...)` 无线程池、无异常上报；② 重启后任务/失败列表丢失；③ 多实例下 SSE 与任务状态分散。
- **修复**：用 `TaskScheduler`/线程池；失败落库持久化；状态外置 Redis。

### 🟡 I7. 死信无告警

- **文件**：`LocalMessageServiceImpl.java:52-55`
- **问题**：资金进死信意味着用户现金与持仓永久不一致，仅 `log.error` 无法及时触达。
- **修复**：死信时增加通知通道（webhook/钉钉），暴露「死信查询 + 手动重试/冲正」接口。

---

## 三、前端 / 体验（中低优先级）

### 🟡 I8. 前端 TradePanel 用浮点计算金额/可买数量

- **文件**：`frontend/src/views/stock/TradePanel.vue:102-109`
- **问题**：`tradePrice * tradeQuantity`、`userCash / tradePrice` 用 JS 浮点，有精度问题；前端展示可能误导。
- **修复**：金额用整数（分）或 `decimal.js`；前端校验仅作提示，后端为最终真源。

### 🟡 I9. LightweightChart.vue 类型 any 扩散与内嵌函数

- **文件**：`LightweightChart.vue:70-72`（`any`）、`:251-330`（`parseTime` 内嵌 try）、`:398-470`（未引用死样式）
- **修复**：为 chart 实例与 K 线数据定义 interface；`parseTime` 提到模块级；删除未引用样式。

### 🟡 I10. 前端 TradeRecord 类型重复声明

- **文件**：`frontend/src/api/trade.ts:2-24` 与 `:26-44`
- **修复**：删除重复，统一一处定义；`createTrade` 使用专门请求 DTO。

### 🟡 I11. 网关白名单用 startsWith，易被绕过

- **文件**：`JwtGlobalFilter.java:73-78`、`SecurityConfig.java:34-36`
- **问题**：`/api/auth/loginxxx` 也会放行；网关与 auth 各维护一份白名单易漂移。
- **修复**：精确匹配或正则；统一白名单位置。

### 🟡 I12. auth-service internal/* 接口用请求参数传 userId，存在越权

- **文件**：`AuthController.java:141-210`
- **问题**：任何拿到 internalKey 的人都能任意操纵用户现金；`resetCash` 可直接改写余额。
- **修复**：internal 接口走内部网络；`resetCash` 加审计日志或限制仅测试环境可用。

### 🟡 I13. 测试覆盖不足

- **文件**：`trade-service/src/test/.../TradeRecordServiceImplTest.java`
- **问题**：仅 1 个测试类、仅覆盖 createTrade 主路径；auth 幂等、data 限速、analysis 兜底、前端计算均无测试。
- **修复**：优先补：auth 幂等性、涨跌停边界、死信路径、T+1 可卖。

---

## 四、锦上添花（低优先级）

- **M1** `TradeRecordServiceImpl.getStatistics`（`:278-352`）在内存对全量交易做 10+ 次 stream 遍历 → SQL 聚合或缓存。
- **M2** `KlineController.getSuffix`（`:138-142`）用代码首位判断市场 → 统一用 `stock_info.market`。
- **M3** `QuoteWebSocketHandler.broadcastQuotes`（`:49-81`）每 3 秒全量查库 + 每行情 × 每 session 双重循环 → 只推订阅股票、增量推送。
- **M4** `useRealtimeQuote.ts`（`:25-65`）重连固定 5 次后停止、`wsUrl` 写死 8082 → 加可见重连状态与手动重连入口，端口从配置读取。
- **M5** `SyncProgressManager.scheduleCleanup`（`:208-229`）裸启线程做延迟清理 → 用调度器。
- **M6** 多个 `GlobalExceptionHandler` 重复定义 → 抽到 `common-api` 提供 `@RestControllerAdvice` 基类。
- **M7** `analysis-service` 返回裸 `Map<String,Object>` → 定义 VO 对齐前端 `DashboardData`。
- **M8** `doc/CodeReview-Improvement-2026-08-07.md` 部分问题已修复 → 该文档已与此文档合并标注。
- **M9** `auth_db.sql:33` 初始化管理员 `admin/admin123` → 生产启动强制禁用或要求改密。
- **M10** 前端 `KLinePage.vue:109` 默认股票硬编码 `600519 贵州茅台` → 移除或明确为 fallback。

---

## 修复顺序建议

1. **安全/部署前提**（本周）：C1 网关路由、C2 JWT 密钥治理
2. **数据一致性闭环**（本周）：I2 消息 payload 强类型、I4 删除回滚闭环、I7 死信告警
3. **前端质量**（本周）：I8 浮点、I9 类型、I10 去重
4. **架构解耦**（需规划）：C3 行情数据服务边界、I3 统一 JWT、I6 同步持久化
5. **体验与打磨**：I5 批量化、I11/I12 安全加固、I13 测试、M1-M10

每项可单独展开为设计与实现计划。
