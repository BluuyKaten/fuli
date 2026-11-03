# 股票复盘系统（fuli）项目总结与架构说明

| 项目 | 内容 |
| --- | --- |
| 文档版本 | v2.2 |
| 更新日期 | 2026-11-02 |
| 适用代码 | `stock-parent 1.0.0` |
| 替代版本 | v1.0（2026-08-05），其中列出的多项高危问题已在后续迭代修复，不再适用 |

> 说明：本文档依据当前代码实际实现重写，不以历史计划或旧文档为准。文中所有结论均可对应到具体类名或文件路径，便于复核。

---

## 一、项目定位与功能边界

**fuli（复利）** 是一个面向个人投资者的 **A 股复盘工具**，而非交易终端。它解决的核心问题是：交易者事后无法还原当时的判断依据，缺少可量化的自我评估。

核心闭环：

```
看 K 线 → 标注买卖点 → 录成交易记录 → 查看持仓/盈亏/绩效 → 写复盘笔记
```

在此之上提供两类差异化能力：

1. **历史盲测练习**：选择一段历史行情，逐根"揭晓下一日"，用与正式账户隔离的模拟账户按次日开盘价撮合，结束时给出账户收益、买入持有基准、最大回撤与费用复盘。
2. **风控预警与多维绩效**：价格止盈/止损、涨跌幅越限监控；胜率、盈亏比、获利因子、连胜连亏、持仓周期与收益区间分布。

**功能边界（明确不做）**：不接券商实盘、不下真实委托、不做行情推送订阅服务、不覆盖港美股与衍生品。行情数据全部依赖外部接口（Tushare / 东方财富）同步入库后再使用，因此"先同步、后使用"是使用前提。

**代码规模**：后端 6 个 Maven 模块、150 个主代码 Java 文件、约 10,100 行；测试 21 个文件、110 个测试用例。前端约 12,150 行（`.vue` + `.ts`）、10 个路由页面、43 个前端测试用例。数据库 13 张表。

---

## 二、系统架构

```
                        浏览器（Vue 3 SPA :3000）
                                  │  /api/**  （Vite 代理）
                                  ▼
                  ┌───────────────────────────────┐
                  │   gateway-service  :8080      │
                  │   JWT 验签 / 路由 / 头透传      │
                  │   剥离伪造头 / 拦截 internal   │
                  └───┬───────┬───────┬───────┬───┘
        /api/auth/**  │       │       │       │  /api/data/**
      /api/watchlist/**      │       │       │
                      ▼       ▼       ▼       ▼
              ┌──────────┐┌──────────┐┌──────────┐┌──────────┐
              │ auth     ││ trade    ││ analysis ││ data     │
              │ :8081    ││ :8082    ││ :8083    ││ :8084    │
              │ 用户/资金 ││ 交易/持仓 ││ 统计/绩效 ││ 行情同步  │
              └────┬─────┘└────┬─────┘└────┬─────┘└────┬─────┘
                   │           │  ▲        │ Feign     │
                   │           │  └────────┘ 取数      │
                   ▼           ▼                      ▼
              auth_db      trade_db              data_db
                                  │                  │
                        本地消息表 local_message       └── Tushare / 东方财富
                                  └── Feign ──► auth（扣款/入账，msgId 幂等）
```

架构要点：

- **网关是唯一外部入口**，业务服务同时保留独立的 Bearer 校验，不信任网关透传的用户头，绕过网关直连端口也无法伪造身份。
- **服务边界按数据所有权划分**：`auth_db` 归 auth，`trade_db` 归 trade，`data_db` 归 data。analysis 不持有数据库，全部通过 Feign 聚合（历史上曾直连 `trade_db`，已移除）。
- **两种运行模式**：`local`（关闭 Nacos，网关与 Feign 直连 localhost）与默认模式（Nacos 注册发现 + 配置中心）。两者由 `application-local.yml` 与 `spring.config.activate.on-profile: "!local"` 区分。

---

## 三、技术栈

### 3.1 后端

| 类别 | 选型 | 版本 |
| --- | --- | --- |
| 基础框架 | Spring Boot | 4.1.0 |
| 微服务 | Spring Cloud | 2025.1.2 |
| 微服务 | Spring Cloud Alibaba（Nacos 2.4） | 2025.1.0.0 |
| 网关 | spring-cloud-starter-gateway-server-webflux | 随 BOM |
| ORM | MyBatis-Plus（boot4 专用 starter） | 3.5.17 |
| 数据库 | MySQL（驱动 mysql-connector-j） | 8.0 / 9.2.0 |
| 认证 | JJWT（HMAC-SHA256） | 0.13.0 |
| RPC | OpenFeign + LoadBalancer | 随 BOM |
| 接口文档 | springdoc-openapi-starter-webmvc-ui | 2.8.8 |
| 实时推送 | spring-boot-starter-websocket + SseEmitter | 随 BOM |
| 熔断降级 | spring-cloud-starter-circuitbreaker-resilience4j | 随 BOM（5.0.2）|
| 本地缓存 | spring-boot-starter-cache + Caffeine | 随 BOM |
| 令牌黑名单 | spring-boot-starter-data-redis / -reactive | 随 BOM |
| 工具 | Hutool / FastJSON2 / Lombok | 5.8.39 / 2.0.58 / 1.18.40 |
| 测试 | JUnit 5 + Mockito + H2 | 随 BOM |
| 构建 | Maven（编译目标 **Java 17**） | 3.8+ |

### 3.2 前端

| 类别 | 选型 | 版本 |
| --- | --- | --- |
| 框架 | Vue | 3.5.x |
| 构建 | Vite | 6.x |
| 语言 | TypeScript | 5.7 |
| UI | Ant Design Vue | 4.2.x |
| 图表 | ECharts / lightweight-charts | 5.5.x / 5.2.x |
| 状态 / 路由 | Pinia / Vue Router | 2.3 / 4.5 |
| HTTP | Axios（统一拦截器） | 1.7 |
| 测试 | Vitest | 4.x |
| 构建优化 | Vite `manualChunks` 三方库拆包（入口 2.7MB→179KB） | — |

### 3.3 基础设施

MySQL 8.0（必需）、Nacos 2.4（仅 Nacos 模式）、Redis 7（已引入依赖，核心链路未使用）、Docker Compose（仅编排基础设施，不启动 Java 服务与前端）。

---

## 四、模块详解

### 4.1 common-api（公共依赖包，无独立进程）

约 810 行，是整个系统的契约层与安全基础设施：

- 统一响应 `Result<T>`、`BusinessException` + `BizCode`、`TradeTypeEnum`；
- 共享 DTO / VO：`LoginDTO`、`TradeDTO`、`TradeQueryDTO`、`TradeVO`、`StatisticsVO`、`PositionVO`、`MonthlyProfitVO`、`LoginVO`；
- Feign 契约：`AuthFeignClient`、`TradeFeignClient`、`DataFeignClient`（各服务只调用这一份，避免签名漂移）；
- 安全组件：`BearerUserFilter`（独立验签）、`InternalServiceFilter`（内部接口凭据，常量时间比较）、`AuthenticatedUser`、`RequestPaths`；
- 按需装配：`JwtAutoConfiguration`、`InternalKeyAutoConfiguration`，并在 `@PostConstruct` 校验密钥强度——**JWT ≥ 32 字节、内部密钥 ≥ 16 字节，不满足直接启动失败**（fail-closed）。

### 4.2 gateway-service（:8080）

约 240 行，`GatewayRouteConfig` 集中声明 4 条路由（避免与 YAML 重复注册），`uri` 支持 `fuli.routes.*` 覆盖，默认为 `lb://服务名`。

`JwtGlobalFilter`（`Ordered = -100`）职责：

1. 拒绝任何含 `%`、`\` 或 `internal` 路径段的请求（返回 403）；
2. 移除入站的 `X-Internal-Key`、`X-User-Id`、`X-Username`，防止伪造；
3. 白名单精确匹配（`/api/auth/login`、`/register`、`/captcha` 及其子路径、`/actuator/health`、`/actuator/info`），**不用裸 `startsWith`**，避免 `/api/auth/loginxxx` 绕过；
4. 生成/透传 `X-Request-Id`（traceId）并回写响应头；
5. 验签通过后注入 `X-User-Id` / `X-Username`，并查询 Redis 令牌黑名单，拉黑的令牌直接拒绝（fail-open）。

### 4.3 auth-service（:8081，auth_db）

用户、资料、密码、现金账户与自选股。`SecurityConfig` 为无状态会话，`/auth/internal/**` 在 Spring Security 层放行，但由 `InternalKeyFilter`（`HIGHEST_PRECEDENCE + 10`）在任何业务逻辑之前校验 `X-Internal-Key`。

资金接口均要求携带 `msgId`，由 `IdempotentMessageServiceImpl` 通过 `idempotent_message.msg_id` 唯一索引保证"同一消息只扣一次"：

| 状态 | 重复请求的处理 |
| --- | --- |
| SUCCESS | 幂等返回 `true`，不重复执行 |
| PROCESSING | 返回 `false`，让调用方稍后重试 |
| FAILED | 返回 `false`，允许重新执行 |

`sys_user` 含现金余额（注册默认 ¥200,000）、BCrypt 密码、`DefaultAdminPasswordChecker`（非 local 模式下拒绝以已知默认管理员密码启动）。

主要接口：`/auth/login`、`/auth/register`、`/auth/logout`（登出把 jti 写 Redis 黑名单）、`/auth/profile`(GET/PUT)、`/auth/password`、`/auth/watchlist`、`/auth/internal/{deductCash,addCash,userCash,userInitialCash,resetCash}`。

### 4.4 trade-service（:8082，trade_db）

体量最大（约 4,880 行），承载 6 个业务域：

| 域 | 关键类 | 接口前缀 |
| --- | --- | --- |
| 交易与持仓 | `TradeRecordController` / `TradeRecordServiceImpl` / `PositionSummaryServiceImpl` | `/trade` |
| 行情与画线 | `KlineController`、`StockController`、`StockDataRouterService` | `/stock` |
| 复盘笔记 | `TradeJournalController` / `TradeJournalServiceImpl` | `/trade/journal` |
| 风控预警 | `PriceAlertController` / `PriceAlertServiceImpl` | `/trade/alert` |
| 盲测练习 | `PracticeController` / `PracticeService` / `PracticeEngine` | `/trade/practice` |
| 实时报价 | `QuoteWebSocketHandler` + `WebSocketConfig` | `/ws/quotes`（3 秒轮询） |

服务内自建 `TradeAuthenticationFilter`（继承 `BearerUserFilter`），对 `/trade/**`、`/stock/drawing`、`/stock/holding`、`/stock/available-quantity`、`/stock/fix-holding` 强制验签；`/trade/internal/**` 走内部密钥而非用户令牌。

### 4.5 analysis-service（:8083，无数据库）

`AnalysisService`（526 行）与 `DashboardService`（205 行）全部通过 `TradeFeignClient` / `DataFeignClient` 取数后内存聚合，输出统计、月度盈亏、资产曲线、仪表盘与多维绩效（`PerformanceStatsVO`：胜率、盈亏比、获利因子、单笔均值、连胜连亏、平均持仓天数、极值交易、收益/持仓周期分布、月度统计、个股盈亏榜）。5 个聚合入口加 Caffeine 短 TTL 缓存（30s），避免重复全量拉取。

接口：`/analysis/{statistics, monthly-profit, asset-curve, dashboard, performance}`。

### 4.6 data-service（:8084，data_db）

约 2,000 行，行情数据的唯一写入方。`TushareClient` 封装 Tushare Pro，`TushareSyncService` 提供基础信息、单只日线、按交易日、全市场增量四类同步；`SyncProgressManager` 以 `SseEmitter` 推送进度；`SyncStatus/ SyncResult` 记录失败日期并支持重试。

定时任务：每日 18:00 同步当日行情、工作日 19:00 同步基础信息、工作日 19:30 增量同步全部股票。

接口：`/data/stock/{search,daily,info,latest-price,latest-prices,infos}`、`/data/tushare/sync/*`（含 `progress/{taskId}` 的 SSE 与 `last-failures` / `retry-failures`）。

### 4.7 frontend（:3000）

```
src/
├── api/          10 个按域拆分的请求模块
├── composables/  useChartCore / useDrawingTools / useIndicators /
│                 useRealtimeQuote / useTradeMarkers / useDocking / useFlexPanels
├── utils/        request.ts（统一拦截器）、indicators/（MA/EMA/MACD/KDJ/RSI/BOLL 纯函数）
├── stores/       user.ts（Pinia）
├── layouts/      BasicLayout.vue
└── views/        login、dashboard、trade、stock、practice、journal、alert、analysis、account
```

指标计算在前端以纯函数实现并有独立单测（6 个指标文件各配测试），便于与图表渲染解耦。

页面与路由：

| 路由 | 页面 | 主要能力 |
| --- | --- | --- |
| `/login` | LoginPage | 登录 / 注册 |
| `/dashboard` | DashboardPage | 总资产、浮动盈亏、持仓、资产曲线、月度收益 |
| `/trade`、`/trade/add` | TradeList、TradeForm | 交易分页查询、筛选、编辑备注、删除回滚 |
| `/kline` | KLinePage + LightweightChart | K 线、指标、画线、买卖点标注、盘口、下单面板 |
| `/practice` | PracticePage | 盲测练习：建仓、委托、揭晓、复盘 |
| `/journal` | JournalPage | 复盘笔记、情绪打分、月度足迹 |
| `/alert` | AlertPage | 价格/涨跌幅预警管理 |
| `/performance` | PerformancePage | 多维绩效图表 |
| `/sync` | StockSync | 数据同步与进度 |
| `/account` | ProfilePage | 资料、改密、现金重置 |

---

## 五、数据模型

按服务所有权分库，共 13 张表：

| 库 | 表 | 说明 |
| --- | --- | --- |
| auth_db | `sys_user` | 用户、BCrypt 密码、现金余额、资料 |
| auth_db | `idempotent_message` | 资金消息幂等表（`msg_id` 唯一索引 + 状态机） |
| trade_db | `trade_record` | 交易记录：日期、方向、价格、数量、费用、已实现盈亏 |
| trade_db | `position_summary` | 持仓汇总：数量、加权平均成本（卖出用原子扣减 SQL 防超卖） |
| trade_db | `trade_journal` | 复盘笔记：情绪分（1-5）、市场情绪、策略类型、正文、反思、标签 |
| trade_db | `price_alert` | 预警：类型 1 止盈 / 2 止损 / 3 涨幅超限 / 4 跌幅超限、目标值、状态、触发值 |
| trade_db | `local_message` | 本地消息表：`msg_id`、topic、payload、状态、重试次数、死信标记 |
| trade_db | `practice_session` | 练习会话：`state_json`（账户状态）+ `snapshot_json`（冻结行情）+ `version` |
| data_db | `stock_info` | 股票基础信息（代码、名称、行业、市场、上市日期） |
| data_db | `stock_daily_data` | 日线（开高低收、前收、涨跌额、涨跌幅、量额） |
| data_db | 迁移脚本 | `migrate_trade_db_to_data_db.sql` 用于把 trade_db 中的历史行情搬到 data_db |

另有 `sql/practice.sql`（仅新增练习表，可重复执行，不删原表）、`sql/restore.sql`、`sql/migrations/V2__lightweight_charts.sql`。

**历史遗留**：`sql/trade_db.sql` 仍会创建 `stock_info` / `stock_daily_data`，但 trade-service 已无对应 Mapper，行情统一走 data-service。新环境会因此多出两张空表，见第八节。

---

## 六、核心业务链路

### 6.1 鉴权链路（双层验签）

```
前端 Bearer Token
   → 网关 JwtGlobalFilter 验签，剥离伪造头，注入 X-User-Id
   → 业务服务 BearerUserFilter 用同一密钥【重新验签】并写入 Request Attribute
   → Controller 从 Attribute 取 userId，不读请求头
```

设计意图：网关透传的头只能作为"已通过网关"的旁证，不能作为身份来源。因此绕过网关直连 8081/8082 时，伪造 `X-User-Id` 无效；请求必须自带有效签名令牌。代价是每请求多一次 HMAC 验签（毫秒级），换取的是单点失守不会导致横向越权。

### 6.2 交易录入与资金一致性（本项目核心设计）

入口：`TradeRecordController#create` → `TradeRecordServiceImpl#createTrade`（单本地事务）。

三个方向分别采用不同策略，依据是"失败时会不会凭空造钱"：

| 方向 | 策略 | 理由 |
| --- | --- | --- |
| 买入（扣款） | **事务内同步原子扣款**，余额不足当场拒单 | 扣款必须在交易落库前得到确定结果，否则并发下单会绕过余额校验 |
| 卖出（入账） | 本地消息表 + 提交后异步入账 | 只增不减，失败重试无资损风险 |
| 删除（回冲） | 本地消息表 + 提交后异步反向操作 | 同上 |

买入的完整步骤：

1. **校验**：价格 > 0、数量 > 0；
2. **同步扣款**：调用 auth 的 `PUT /auth/internal/deductCash`（携带一次性 `msgId`）。服务端执行 `UPDATE sys_user SET cash = cash - ? WHERE id = ? AND cash >= ?`，**校验与扣减是同一条语句**，并发下不可能出现负余额。返回 `BizCode.CASH_INSUFFICIENT(4002)` 时直接抛业务异常，交易不落库；
3. **回滚补偿**：扣款成功后通过 `TransactionSynchronization#afterCompletion` 注册补偿。若本地事务未提交（任何异常导致回滚），`CashCompensationService` 用 `REQUIRES_NEW` 独立事务写入一条反向入账消息（`msgId = REF-<原始ID>`，方向 SELL），由既有重试任务投递退款，避免"钱扣了、单没了"；
4. **落库与建仓**：保存 `trade_record`，按加权平均更新 `position_summary`（`新均价 =（原持仓×原均价 + 新买入×成交价）/ 总数量`）。

卖出与删除的步骤：

1. **可卖数量校验**：可卖 = 总持仓 − 当日买入数量（A 股 T+1）。判断依据是 `trade_date`，补录历史交易不会被误冻；
2. **价格区间校验**：成交价必须落在交易当日实际最高/最低价区间内（缺当日完整行情直接拒绝，提示先同步）；
3. **持仓原子扣减**：`decreaseQuantity` 的 `affected = 0` 即判定持仓不足，防止并发卖超；
4. **写本地消息**：同一事务内写 `local_message`（PENDING），payload 为强类型 `CashChangeMessage`；
5. **提交后投递**：`TradeEventListener` 用 `@TransactionalEventListener(AFTER_COMMIT)` + `@Async` 调用 auth，**首次投递成功即回写消息为 SUCCESS**，不再依赖 5 分钟后的重试扫描；
6. **失败补偿**：投递失败则消息保持 PENDING/FAILED，由 `LocalMessageRetryServiceImpl` 每 5 分钟按指数退避重试，超限进入死信并告警。auth 侧用 `idempotent_message.msg_id` 唯一索引保证"同一消息只生效一次"。

**为什么不用 MQ**：项目刻意选择"本地消息表 + 幂等消费"这一最小实现，在没有中间件的前提下拿到"至少一次投递 + 恰好一次生效"。买入因为需要即时反馈（余额不足必须当场拒绝），改为同步扣款，因此只保留"同步调用 + 回滚补偿"这一条最短路径。

**删除交易**：`deleteTrade` 一律走 `deleteTradeWithRollback`——只允许删除某股票的最后一笔（`isLastTrade`），删除后按剩余交易重建持仓（`rebuildPosition`），同时写入方向相反的资金消息（`REV-` 前缀的独立 msgId，与正向消息互不干扰）。

**编辑限制**：`updateTrade` 只允许改备注。修改价格/数量/类型会破坏持仓与资金一致性，被显式禁止（需要改就删除后重录）。

### 6.3 行情同步与多源降级

数据写入路径唯一：`TushareClient → TushareSyncServiceImpl → data_db`，分片按 30 天切批，避免单次请求过大。

读取路径分两类：

- **历史日线**：trade-service 通过 `DataFeignClient` 向 data-service 查询；data-service 对裸 6 位代码自动补交易所后缀（`600519 → 600519.SH`），先用原值查一次以兼容历史库，未命中再补后缀重查；
- **分钟/周/月线**：`StockDataRouterService` 按 `StockDataSource` 接口路由，主源 Tushare 失败或返回空时自动切到东方财富（`EastMoneyDataSource`），两者都失败返回空列表而不抛异常；
- **实时快照 `/stock/quote`**：当前未接入实时行情源。优先读 `realtime_quote` 表（为空），未命中时退化为 data-service 的最新日线收盘价，返回 `source: "daily"`、`stale: true` 与空的五档，前端显示"日线收盘"角标。**早期版本此处由定时任务随机生成模拟盘口，已删除**；
- **WebSocket `/ws/quotes`**：`QuoteWebSocketHandler` 每 3 秒推送 `realtime_quote` 中 10 秒内更新的记录；没有真实行情源时不会推送任何数据。

### 6.4 盲测练习（设计质量最高的一块）

- `PracticeEngine` 是**纯静态函数式引擎**：不访问数据库、不读系统时钟、不调用行情接口，输入 `(state, snapshot, request)` 输出新状态，因此可以完整单测（`PracticeEngineTest`）。
- `PracticeService` 负责持久化：单事务内 `SELECT ... FOR UPDATE` 读行 → 引擎计算 → `UPDATE ... WHERE version = 旧值`，乐观锁冲突返回 409。
- 会话创建时把行情**冻结**为 `snapshot_json`，之后与真实行情、正式账户完全隔离；`state_json` 记录现金、持仓、可卖数量、委托、成交、权益曲线、标注。
- 撮合规则：委托在当前决策日提交，**下一根日线开盘价**成交；100 股整数倍；资金或可卖股数不足则整单拒绝；无量或一字线不成交且不追单；固定训练费率（双向佣金万三、最低 5 元，卖出印花税万五），逐项四舍五入到分。
- 基准与指标：首根可成交日按开盘价买入最大可负担整手的"买入持有"参照账户；最大回撤按每日收盘总资产相对此前峰值计算；结束后指标冻结在实际结束日，剩余持仓只估值不强平。

### 6.5 绩效分析

`AnalysisService#getPerformance` 取该用户区间内全部交易，单次遍历完成统计（避免十余次 stream 遍历），输出 20 余项指标与 5 组分布/排行；`DashboardService` 聚合总资产、总市值、现金、持仓浮动盈亏与当日盈亏。

---

## 七、配置、启动与工程质量

### 7.1 配置与密钥

- 代码库**不保存任何生产密钥**：`application.yml` 只留 `${DB_USERNAME:root}` 一类带默认值的本地项，`jwt.*` 与 `fuli.internal-key` 均不设默认值；
- `local` profile 内含显式标注"仅本地"的开发密钥，生产必须通过 `JWT_SECRET` / `INTERNAL_KEY` 环境变量注入；
- 启动时校验长度与"已知弱值"，不满足直接失败，避免空默认值静默生效；
- Nacos 模式通过 `spring.config.import: optional:nacos:{服务名}.yaml` 拉取远程配置，`local` profile 显式关闭 Nacos。

### 7.2 启动顺序

`MySQL → auth(8081) → data(8084) → trade(8082) → analysis(8083) → gateway(8080) → 前端(3000)`。`common-api` 需先 `mvn clean install`。使用行情功能前必须先在"数据同步"页同步股票基础信息与对应区间日线。

### 7.3 已建立的工程防护

| 方面 | 现状 |
| --- | --- |
| 后端测试 | 110 个测试用例（`mvn test` 实测全绿；gateway 23 / auth 6 / trade 77 / analysis 4），覆盖资金消息可靠性、交易回滚、越权隔离（Bearer 边界）、内部接口边界、练习引擎、快照加载、预警、笔记、绩效 |
| 前端测试 | 43 个用例（12 个文件，`npm test`），覆盖指标计算、画线工具、买卖点标记；另有基于真实 Chrome/Edge 的练习页浏览器回归脚本（`node tests/practice-browser-smoke.mjs`） |
| 安全 | 双层验签、内部密钥常量时间比较、网关剥离伪造头、`internal` 路径拦截、密钥强度 fail-closed 校验 |
| 一致性 | 本地消息表 + 幂等消费 + 死信告警；持仓原子扣减；练习乐观锁 + 行锁 |
| 接口文档 | 各业务服务引入 springdoc（`/swagger-ui`） |
| 本地环境 | `docker-compose.yml` 提供 MySQL/Redis/Nacos 与初始化脚本挂载 |

---

## 八、现存问题与改进建议

以下结论均已对应到当前代码，按优先级排列。

### 8.1 资金与一致性（2026-11-02 已修复）

| # | 原问题 | 修复方式 | 回归用例 |
| --- | --- | --- | --- |
| 1 | 买入"先查后扣"存在并发透支窗口：校验只读余额，扣款在事务提交后异步执行，两个并发买入可同时通过 | 买入改为**本地事务内同步原子扣款**，`cash >= amount` 条件更新即判定；失败直接拒单、交易不落库；本地事务回滚时由 `CashCompensationService`（`REQUIRES_NEW`）登记反向入账消息退款 | `TradeRecordServiceImplTest` 新增 4 例（余额不足 / 服务不可用 / 扣款未确认 / 回滚不落库）、`CashCompensationTest` 3 例、`AuthInternalServiceTest#deductCash_insufficientBalance_...` |
| 2 | 卖出只校验总持仓，T+1 未在服务端强制 | `createTrade` 改用 **可卖数量 = 总持仓 − 当日买入**（依据 `trade_date` 判断，补录历史交易不受影响）；`StockServiceImpl#isAStock` 同步修正裸 6 位代码的兜底判断 | `createTrade_sellSameDayAsBuy_isRejectedByT1` |
| 3 | 异步投递首次成功未回写消息状态，导致 PENDING 残留并在 5 分钟后被重发 | `LocalMessageService#markSuccessByMsgId`，`TradeEventListener` 在资金变动成功后立即回写 SUCCESS；失败仍保持待重试 | `TradeEventListenerTest` 3 例 |

**残余风险（未完全消除，需知晓）**

- 进程在"远程扣款成功"与"本地事务提交"之间被强制终止时，`afterCompletion` 不会执行，退款补偿无法登记，需人工对账（余额与交易流水核对后用 `PUT /auth/internal/resetCash` 校正）。要彻底消除此窗口，需在 auth 侧引入预占记录（RESERVED/CONFIRMED/RELEASED）+ 超时对账，即完整的 TCC 方案。
- `LocalMessageRetryServiceImpl` 的扫描仍无行锁，多实例部署会重复投递同一批消息（有幂等兜底，不会重复扣款，但会放大请求量），见 8.2 第 4 项。

### 8.2 中优先级（性能 / 可运维性）

| # | 问题 | 位置 | 建议 |
| --- | --- | --- | --- |
| 4 | 重试扫描并发保护（**2026-11-02 已修复**） | `LocalMessageServiceImpl#tryClaim` | 已用条件更新实现租约抢占：`UPDATE ... SET next_retry_time = 租约 WHERE id=? AND status IN (0,2) AND next_retry_time <= now`，第一个实例把时间推后后其他实例不再命中；进程崩溃则租约到期自动重新可扫 |
| 5 | 练习会话整行 JSON 读写（**2026-11-03 已修复**） | `practice_session` + `PracticeService` | 已加 7 个摘要列，`list()` 免解析完整 state_json（历史行回退解析兜底）；`snapshot_json` 本为 insert-only。bars 拆表留待后续 |
| 6 | 分析链路全量拉取无缓存（**2026-11-03 已修复**） | `AnalysisService` / `DashboardService` | 已加 Caffeine 短 TTL 缓存（5 个聚合入口 30s）。下沉 SQL 聚合留待后续 |
| 7 | 无熔断与限流（**2026-11-03 已修复熔断**） | 各服务 Feign 调用 | 已引入 Resilience4j 熔断 + 超时，调用点 try/catch 兜底；网关限流仍未做 |
| 8 | Redis 引入但未使用（**2026-11-03 已启用**） | auth-service 依赖 | 已用于令牌黑名单（logout 即时失效）；网关限流/分布式缓存仍未做 |

### 8.3 低优先级（可维护性 / 体验）

| # | 问题 | 建议 |
| --- | --- | --- |
| 9 | 前端巨石组件 | `LightweightChart.vue`(955)、`JournalPage.vue`(807)、`KlineChart.vue`(767)、`PerformancePage.vue`(674) 行；两套图表库并存，建议统一到 lightweight-charts 并继续拆分组合式函数 |
| 10 | 遗留行情表（**2026-11-03 已修复**） | `trade_db.sql` | 已从 `trade_db.sql` 与测试 schema 移除 `stock_info`/`stock_daily_data` |
| 11 | 宽松默认值 | `WebSocketConfig` 的 `setAllowedOrigins("*")`、`docker-compose.yml` 明文 root 密码，上生产前需收紧 |
| 12 | 缺少 CI | 无 `.github/workflows`，`mvn test` / `npm test` 仍手动执行，建议加最小流水线 |
| 13 | 文档漂移（**2026-11-02 已修复**） | README 的 JDK 要求已对齐为 17+；旧版本文档（v1.0）描述的多数问题已修复，本文档即为其替代 |

### 8.4 联调阶段发现并修复（2026-11-02）

本地起服务跑通全链路时发现三个只在实际运行中才暴露的问题：

| # | 问题 | 影响 | 修复 |
| --- | --- | --- | --- |
| 14 | **实时行情是随机数伪造的** | `DataSyncScheduler` 每 3 秒为 3 只股票生成随机价格与随机五档写库，`/stock/quote` 与 WebSocket 把假数据当真实行情展示，用户看到的盘口完全不可信 | 删除该定时任务；`/stock/quote` 在无实时源时退化为 data-service 的最新日线收盘，返回 `source=daily` / `stale=true` 与空五档，前端显示"日线收盘"角标与"暂无五档" |
| 15 | **裸代码查不到行情** | 行情库统一存 `600519.SH`，若交易记录/预警/练习传 `600519` 就查不到：卖出被误判"缺少行情"（4004）、预警 `currentPrice` 恒为空、持仓市值算成 0 | data-service 的 `/daily`、`/info`、`/latest-price`、`/infos` 统一做代码归一：先用原值查（兼容历史库），未命中再补 `.SH/.SZ/.BJ` 重查；trade-service 的卖出校验另有一层兜底 |
| 16 | 重试扫描多实例重复投递 | 见 8.2 第 4 项 | 租约式抢占，见 8.2 第 4 项 |

**联调验证**（MySQL 8.4 真实库 + 五个服务 + 前端，local profile）：

- 买入 → 现金 200000→189995 同步扣减、`local_message` 无新增扣款消息；
- 超额买入 → `4002 可用资金不足`，未落库、余额不变；
- 当日买入当日卖出 → `4001`（T+1）；
- 删除买入 → 反向消息 `TRADE_REVERSE` 3 秒内被标记 SUCCESS，现金回到 200000，持仓归零；
- 历史卖出 → `TRADE_SELL` 消息 SUCCESS，现金 198916 与「1490 买 / 1480 卖 / 100 股 / 佣金 5 / 印花税 74」完全吻合；
- `/stock/quote?code=600519` 返回真实日线 1309.22（`source=daily`），不再有伪造盘口；
- 预警列表 `currentPrice=1309.22`、`changePct=0.05`；
- 练习：建仓 → 委托 → 揭晓下一日按开盘价成交（cash 99684 / qty 100 / 1 笔成交）；
- 前端 `npm test` 43 例通过、`npm run build` 通过、真实 Chrome 练习页回归通过。

**已知的产品约束（非缺陷）**：盲测练习要求「起点前 120 根 + 练习区间」内 `preClose` 与上一根收盘价连续（容差 0.5%），除权除息日会被判为"价格口径断层"而拒绝创建。当前同步的是未复权日线，因此含分红日的窗口无法开练，需要选择无除权的区间，或后续接入复权数据。

---

## 九、演进建议

**已完成（2026-11-03）**：createTrade 幂等键（`request_id` + 唯一索引）、重试扫描租约抢占、练习会话摘要列、analysis 缓存（Caffeine）、Feign 熔断（Resilience4j）、清理 trade_db 遗留表、traceId 全链路可观测、令牌黑名单（logout 即时失效）。

**短期（1-2 周）**：补齐 CI（`mvn test`/`npm test` 仍手动执行）；网关限流；前端 antd 按需引入（当前 `app.use(Antd)` 全量引入，`vendor-antd` 约 1.3MB）。

**中期（1-2 月）**：练习会话 bars 拆表（快照独立表，减少整行重写）；analysis 聚合下沉 SQL；补齐接口文档页。

**长期**：策略回测框架（在 `PracticeEngine` 之外抽象可插拔策略层）、复盘报告导出（PDF）、多账户/多人协作、移动端。

---

## 十、变更记录

| 版本 | 日期 | 变更 |
| --- | --- | --- |
| v2.3 | 2026-11-03 | 生产级加固五块：**安全**（WebSocket 源白名单 + 握手 JWT 鉴权、生产关闭 swagger、actuator 三处收口为仅 health/info、SQL/网关日志收敛、令牌黑名单）、**可观测**（traceId 全链路 + actuator 健康检查）、**可靠**（Resilience4j 熔断 + 10 处降级兜底，幂等键/重试行锁确认已存在）、**性能**（analysis Caffeine 缓存、practice_session 摘要列、Redis 令牌黑名单）、**代码质量**（清理 trade_db 遗留行情表）。前端 manualChunks 拆包。测试 113 例全绿 |
| v2.2 | 2026-11-02 | 联调阶段修复：删除伪造实时行情的定时任务并改为日线降级、data-service 统一裸代码补后缀、本地消息重试加租约抢占；更新行情链路、测试数量（后端 110 / 前端 43）与联调验证记录 |
| v2.1 | 2026-11-02 | 修复 8.1 的三项资金问题：买入改同步原子扣款 + 回滚退款补偿、卖出补 T+1 可卖量校验、异步投递成功后回写消息状态；新增 10 个回归用例（后端测试 108 例全绿）；同步更新资金链路说明 |
| v2.0 | 2026-11-02 | 依据当前代码全面重写：修正模块职责与端口；补充盲测练习、复盘笔记、风控预警、绩效分析四个业务域；重写资金一致性设计说明；替换已失效的问题清单为 13 项可复核项；固定文档版本头，避免再次漂移 |
| v1.0 | 2026-08-05 | 初版项目总结（基于 MVP 阶段代码，已过时） |
