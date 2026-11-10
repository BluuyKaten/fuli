# OKX 接入实施计划

- 建立日期：2026-11-09
- 上位文档：`docs/adr/0009-okx-mcp-tools-and-full-api-scope.md`（决策）、`docs/okx-接入范围对照.md`（范围与阻塞）、`docs/okx-接入手册.md`（配置与运维）
- 定位：**本文是排期与验收依据**；不重新做决策，也不复述范围对照。决策有变先改 ADR，再改本文。

## 0. 读前须知

**不可协商的硬约束**（来自 ADR-0009，任一条被破坏即视为该期验收失败）：

1. 实盘 API Key / Secret / Passphrase **不进入 Java 服务**；所有私有请求恒定携带 `x-simulated-trading: 1`。
2. 提币权限永不开启；系统不提供实盘自动下单。
3. **不放宽** `instrument.code` / `bar.instrument_code` / `adj_factor.instrument_code` 的 `varchar(20)`，不重建 7.67M / 16.8M 行大表。
4. `bar` 只存已完结 OHLC；深度、资金费率、持仓量、Greeks 一律进独立表。
5. 10ms 级深度数据只进内存环形缓冲 + 降采样，**不落 MySQL**。
6. 所有写操作必须过 JWT + 用户级 `sys_user.okx_trade_enabled=1`（P10 起取代 `OKX_WRITE_USER_IDS`），
   并同时留 `[OKX-AUDIT]` 日志与 `okx_audit_log` 记录。

**当前进度**：**P0–P10 代码已全部落地**（P0–P9 于 2026-11-09，P10 于 2026-11-10）；
V10、V11 迁移均已执行并验证；全反应堆 7 模块编译、模块测试、**data-service 真实启动**
（含 Spring AI MCP stateless 自动配置）均通过。
**仍未做**：真实 OKX 网络联调、LLM 接入、账户快照表、前端「系统设置」与实时视图、
审计表的 RANGE 分区切换——见 §7 收尾说明与最后的「需要补充」清单。
**范围确认**：2026-11-09 用户确认继续全量推进（不收工、不缩范围）。

## 1. 全局前置条件（每期开工前逐条检查）

| # | 条件 | 检查方法 | 缺失后果 |
|---|---|---|---|
| 1 | `JWT_SECRET` 已注入且与 gateway / auth 完全一致（≥32 字节） | `GET /data/okx/status` 带令牌返回 200 | 所有 OKX 端点 503 |
| 2 | 本次操作者 `sys_user.okx_trade_enabled=1` | 带令牌 POST `/data/okx/sync/all` 返回 200 | 写操作 403 |
| 3 | **模拟盘专用** API Key 三件套（模拟盘与实盘 Key 不通用） | `GET /data/okx/trade/config` 能读到 `acctLv` | 私有接口签名失败 |
| 4 | `OKX_PROXY`（国内网络） | `curl -x <proxy> https://www.okx.com` 200 | 域名被解析到黑洞地址，立即失败 |
| 5 | 旧配置 `OKX_SIMULATED=false` / `okx.simulated=false` 已移除 | 服务能启动 | `OkxSimulatedGuard` 启动即失败 |
| 6 | `V7__verify_bars.sql` / `V7__verify_adj_factor.sql` 当前全绿 | 跑一遍验收脚本 | 无法区分「原有红」与「本次改红」 |
| 7 | 执行任何**触碰存量表**的迁移前，确认 0.9 双写观察期已收尾 | 见 `sql/migrations/V9__retire_stock_daily_data.md` 的触发器（部署后双写满 2 周） | 停双写会毁掉回滚靶子 |

> **第 7 条已复核降级**：V10 只 `CREATE` 新表、不 ALTER/DROP 任何存量表，与双写窗口无冲突，
> 因此**不是 P2 的前置**。它只在执行 `V9__retire_stock_daily_data` 或任何改存量表的迁移时才需要确认。

## 2. 分期总览与依赖

| 期 | 目标 | 依赖 | 状态 |
|---|---|---|---|
| P0 | 鉴权 + 读写 scope + 结构化审计 | — | ✅ 完成 |
| P1 | 实盘物理隔离（Q12） | P0 | ✅ 完成 |
| P2 | 加密域独立表（DDL + 实体 + Mapper + 订单域服务） | P1 | ✅ 完成（代码 + 迁移已执行） |
| P3 | WS 基础设施（连接/登录/订阅重放/重连/心跳/背压/kill switch） | P1 | ✅ 完成，**默认关闭**；序号校验与降采样归 P8 |
| P4 | WS 一期：私有频道 + 订单状态机 + 对账 | P2 + P3 | ✅ 完成（写前日志已接线；`account` 频道不落库留 P6） |
| P5 | MCP 工具层（**只读工具**） | P0 | ✅ 完成（9 个 `@McpTool`，零写工具；真实启动验证通过） |
| P6 | 七模块能力补齐 | P2 + P5 | ✅ 完成（受控端点清单 36 项 + 统一执行器，写端点统一走写前日志） |
| P7 | WS 二期：公共行情实时视图 | P3 | ✅ 完成（频道解码，明确不写 `bar`；前端实时视图未做） |
| P8 | WS 三期：高频深度 | P3 + P7 | ✅ 完成（1s 降采样稀疏落库，原始 10ms 帧只在内存） |
| P9 | Agent 编排层（新建 `ai-service`） | P5 + P4 | ✅ 完成（第 7 模块 + 网关路由；规则编排，**未接 LLM**） |
| P10 | 多账号凭证隔离 + 用户级写权限 + 审计落表 + Agent 只读身份 | P0 / P4 / P9 | ✅ 完成（ADR-0010；V11 已执行；163+19+9+23 测试全绿） |
| P11 | 前端设置页 + 余额快照 + WS 真机联调 + 审计分区 | P10 | ✅ 完成（V12/V13 已执行；180+19+9+23 测试全绿） |

**依赖说明（容易排错）**：P5 可与 P2–P4 并行推进，因为它是对现有 Service 的薄封装、不碰表。
但 **P5 只允许注册只读工具**；写工具必须等 P4 的「先落库再发网络 + `clOrdId` 幂等」落地后才能注册，
否则 Agent 下单会绕过写前日志，出现孤儿单。P9 同理，必须等 P4 + P5 都验收完。

## 3. 各期详述

### P2 · 加密域独立表

- **已完成（2026-11-09）**：
  - DDL：`V10__crypto_domain.sql` + `V10__crypto_domain_rollback.sql`，8 张表，纯 CREATE、可重入、不改存量表。
  - 代码：`com.fuli.data.crypto` 下 8 个实体 + 8 个 Mapper（`BaseMapper` + 订单状态查询）、
    `CryptoOrderState` 状态机、`CryptoOrderService`（写前日志 / clOrdId 幂等 / 状态流转 /
    乱序与终态保护 / 成交落库 / 持仓对账）；`DataApplication` 的 `@MapperScan` 已加上新包。
  - 测试：`CryptoOrderServiceTest` 18 例；三模块测试 + 其余三服务编译全绿。
- **迁移已执行（2026-11-09）**：MySQL 8.4.8 / `data_db`。流程：结构备份 + 行数基线 →
  独立副本库 `okx_v10_verify_20261109` 预演（8 张表全通过，已清理）→ 正式执行 →
  验证 8 张表存在、`SHOW CREATE TABLE instrument/bar/adj_factor` 与执行前 `diff` 无差异、
  `COUNT(*)` 无差异（6062 / 7,675,526 / 16,823,991）、`V7__verify_bars.sql` 与
  `V7__verify_adj_factor.sql` 全绿。备份与基线在 `sql/migrations/backup/`。
- **待做**：P4 把 `CryptoOrderService` 接到 `OkxTradeService` 与 WS `orders` 频道
  （**当前它是独立服务，尚未被任何下单路径调用**，因此写前日志还没真正生效）。
- **字段口径**：`clOrdId` 幂等键、`ordId`、`state`（含 `pending` = 已落库未发网络）、
  `accFillSz`（WS 里是累计值）、`tradeId` / `posId` 对账、`cancelSource`、`cTime`/`uTime`/`fillTime`。
  时间统一**毫秒 epoch**（与 `bar.ts` 的秒不同）；instId 用 `VARCHAR(64)` 绕开 `varchar(20)` 限制。
- **执行门控**：① 确认 V10 脚本评审通过；② `mysqldump --single-transaction` 备份；
  ③ 在副本库 `data_db_verify` 跑一遍；④ 执行后逐字对比
  `SHOW CREATE TABLE instrument/bar/adj_factor` 与执行前一致。
- **验收**：新表全空且 A 股链路不受影响；`V7__verify_*.sql` 仍全绿；写前日志可在无网络时落库；
  回滚脚本演练通过（DROP 8 张表后系统回到 P2 之前）。
- **回滚**：`V10__crypto_domain_rollback.sql`，`DROP` 这 8 张表即可，不触碰任何存量对象。
- **不做**：不给 `bar` 加列、不改 CHECK、不给加密行进 `instrument`（继续用独立表）、
  不把原始 10ms 深度流写进 `crypto_book_snapshot`（该表只存降采样稀疏帧）。

### P3 · WS 基础设施

- **已完成（2026-11-09）**：`com.fuli.data.okx.ws` 四个组件 + 配置 + 12 例单测。
  `OkxWsClient`（JDK 17 内置 WebSocket：连接、登录 ACK 后订阅重放、指数退避重连、心跳 `ping`、kill switch）、
  `OkxWsLogin`（复用 `OkxSigner`，时间戳为 **Unix 秒**）、`OkxWsSubscription`（简写/JSON 两种订阅配置）、
  `OkxWsFrameBuffer`（有界环形缓冲，满则丢最旧并计数）、`OkxWsProperties`（**强制模拟盘域 wspap.okx.com**，填实盘域启动失败）。
  默认 `okx.ws.enabled=false`。
- **范围澄清**：序号 / `checksum` 校验与降采样**移到 P8**——只有 `books` 类深度频道才有这两个字段，
  私有 `orders` / `account` / `positions` 频道没有；P3 只负责把帧可靠送进有界缓冲。
- **验收**：单测覆盖背压淘汰与计数、订阅解析与重放、登录签名手工对拍、模拟盘域强制、
  重连退避封顶、消息分发异常隔离、登录 ACK 触发订阅。**⚠️ 尚未做真实网络联调**
  （需模拟盘凭证 + 代理），断网重连与 1 秒内 kill switch 仍需在部署环境实测，见 §4 通用验收清单。
- **回滚**：`okx.ws.enabled=false` 即可，REST 链路完全不受影响。
- **不做**：不写 MySQL；不引入 MQ（若背压不够，另开 ADR 再决定）。
- **技术选型已定**：JDK 17 内置 `java.net.http.WebSocket`，不引第三方库，
  与 REST 侧 `OkxClient` 的 JDK `HttpClient` 一致（§6 第 5 项已关闭）。

### P4 · WS 一期 + 订单状态机

- **已完成（2026-11-09）**：
  - `OkxTradeService.placeOrder` 固定顺序 `createPending → 发网络 → markSubmitted`；
    `clOrdId` 为空生成 32 位幂等键，命中未完成单不发网络；`sCode≠0` 当场定格 rejected。
    `cancelOrder` 先查本地单，找不到只 WARN。
  - `OkxWsMessageDispatcher` 解码 `orders`（→ 状态机 + 成交明细）与 `positions`（→ 快照去重落库），
    注册到 P3 的 `OkxWsClient`；`event` 类消息跳过、坏帧只 WARN。**`account` 频道不落库**（V10 无该表，留 P6）。
  - `OkxOrderReconciler`：`orders-pending` vs 本地未完成单 vs 孤儿单的**只读**差异报告，只报告不自动修数据。
  - 测试：`OkxTradeServiceWriteAheadTest`(6) / `OkxWsMessageDispatcherTest`(9) / `OkxOrderReconcilerTest`(3)；
    data-service 累计 **100 个测试全绿**，三模块测试 + 其余三服务编译 `ALL_GREEN`。
  - 真实库 smoke：`crypto_order` 插入 `pending` 成功、插入非法 state 被 CHECK 拒绝、测试数据已清理。
- **仍未做**：真实网络联调（需模拟盘凭证 + 代理）；断网 1 分钟恢复的端到端验收需在部署环境执行。
- **交付（原定范围）**：`orders` / `account` / `positions` 私有频道订阅；状态机 `pending → live → partially_filled → filled / canceled`；`clOrdId` 幂等；`tradeId` / `posId` 对账；`orders-pending` 与 WS 快照对账；所有订单**先落库再发网络**。
- **验收**：直接采用 `docs/multi-market-rollout.md:76` 的完成标志——「断网 1 分钟后恢复，订单状态与交易所一致，无重复下单、无孤儿单」；`clOrdId` 复用不会重复下单；强平/ADL 导致的持仓变化（`tradeId` 不更新）能靠 `uTime` + `pos` 识别。
- **回滚**：关闭 WS 订阅，回退到 REST 轮询查询；P2 的表保留（只是不再写入）。
- **不做**：合约/杠杆/期权；不实现复杂拆单。

### P5 · MCP 工具层（只读）

- **交付**：根 `pom.xml` 加 `spring-ai-bom 2.0.x`；`data-service` 加 `spring-ai-starter-mcp-server-webmvc`；用 `@McpTool` 暴露只读工具（ticker、K 线、余额、持仓、挂单）；MCP 端点登记进 `OkxAuthPaths`；gateway `/api/okx/mcp/**` 路由联调。
- **验收**：无令牌调 MCP 端点 → 401；带令牌 `tools/list` 正常返回；**`tools/list` 中不出现任何写工具**；`OkxAuthenticationFilter` 对该端点仍生效（Spring AI 的 HTTP 端点默认未鉴权，不能只靠 starter 默认配置）。
- **回滚**：移除 starter 依赖与工具 bean；网关路由保留（无害）。
- **不做**：不装 `okx-trade-mcp` npm 包、不引入 Node、不新建 `ai-service`、不注册写工具。

### P6 · 七模块能力补齐

- 顺序：`market` → `spot` → `account` → `swap` / `futures` → `option` → `bot`，每模块独立验收。
- **每模块验收**：模拟盘下单/改单/撤单/批量（≤20 笔）走通；`sCode` 逐笔检查；期权 Greeks 与期权链可读；网格/DCA 机器人创建→查询→停止生命周期完整；每个写工具都过白名单并留审计。
- **回滚**：按模块下架工具注册，不影响 REST 既有链路。
- **注意**：`docs/multi-market-rollout.md:58` 阶段 2.4「不做部分成交」与 `fillSz` 诉求冲突，P4 前先定（见 §6）。

### P7 / P8 · WS 二期、三期

- P7：`tickers` / `candle*` 公共频道 → 实时视图，**不写 `bar`**；验收重点是「最后一根未完结 K 线不进事实表」。
- P8：`books5` / `books-l2-tbt`；10ms 帧只进环形缓冲 + 降采样；验收重点是 MySQL 零写入、背压可观测。

### P9 · Agent 编排层

- 新建第 7 模块 `ai-service`，经网关 `/api/ai/**` 纳入 JWT，经 Feign 调 data-service；先只编排只读工具。
- **不在本期内**：任何自动下单能力。

### P10 · 多账号凭证隔离、用户级写权限、审计落表、Agent 只读

决策见 `docs/adr/0010-okx-multiuser-credentials-and-agent-readonly.md`。

- **已完成（2026-11-10）**：
  - 凭证：`okx_account`（唯一键 `user_id+label`）+ AES-256-GCM 逐行随机 IV + AAD 绑定 userId；
    KEK 只来自 `OKX_CRED_KEK`，缺失启动失败；`OkxConfig` 删除凭证字段，
    `OkxClient` 私有方法首参改为 `OkxCredentials`，**无全局回退**；
    `OkxTradeService` / `OkxCapabilityService` / `OkxMcpTools` / `OkxWsClient` /
    `OkxOrderReconciler` 全部改为按 userId 取凭证；
    新增 `POST/GET /data/okx/account`（只回掩码，契约见 `docs/okx-前端契约.md`）。
  - 权限：`sys_user.okx_trade_enabled`（V11 已 ALTER）+ auth-service
    `GET /auth/internal/okxTradeEnabled` + data-service `OkxTradePermissionClient`
    （30 秒缓存、fail-closed）；**删除 `OKX_WRITE_USER_IDS`**；保留 `okx.trade-enabled` 总闸。
  - 审计：`okx_audit_log` 双写（日志 + 表），落表失败只 WARN；`OkxAuditRetentionJob`
    每日 03:30 分批删 2 个月前数据；scope 分 read/write/credential。
  - Agent：auth-service `POST /auth/oauth/token`（client_credentials，
    `type=SERVICE` / `scope=orders:read` / `aud=fuli-data` / 900s）；
    ai-service `ServiceTokenProvider` 定时刷新 + `OkxReadOnlyClient`；
    data-service 对 SERVICE 主体写操作硬 403 + aud 校验；
    `okx_order_suggestion` + `POST /ai/suggest-order`（只写建议单）+ `GET /data/okx/suggestions`。
  - 迁移：`V11__okx_multiuser_credentials.sql`（data_db 3 表）+
    `V11__auth_okx_trade_enabled.sql`（auth_db 加列），**已在正式库执行**；
    流程为 结构备份 + 行数基线 → 副本库预演 → 正式执行 → 二次执行验证可重入 →
    存量三表 `SHOW CREATE TABLE` 逐字无差异、行数无差异（6062 / 7,675,526 / 16,823,991）。
- **验收**：`mvnw -pl common-api,data-service,gateway-service,ai-service -am test` 全绿
  （7 / 163 / 23 / 9）；`auth-service` 19 例全绿；全反应堆 `compile` 通过；
  data-service 真实启动（Tomcat 8084、MCP 9 工具）；加密单测覆盖「同明文两次密文不同 /
  解密可还原 / 错 KEK 失败 / 篡改失败 / AAD 跨用户失败」；审计单测覆盖「落表失败不影响主流程」；
  保留策略单测覆盖「2 个月 cutoff + 分批 + 批次上限 + 中途异常」。
- **真机冒烟**：401/403/400 分层符合预期；凭证落库为密文（58/56 字节 = iv12+密文+tag16）；
  审计表无任何明文；`okx_trade_enabled` 从 0→1 后，下单从 403 变为 400（参数错误），
  证明用户级闸门生效且未被业务异常吞成 400。
- **回滚**：`V11__okx_multiuser_credentials_rollback.sql`（DROP 3 表）+
  `V11__auth_okx_trade_enabled_rollback.sql`（DROP COLUMN）；代码回退需与之一致。
- **不做**：不做「按账户切换交易」（`label` 预留未启用）、不做 KEK 轮换自动化、
  不做审计表分区（等量级触发）、不做前端页面。

### P11 · 前端设置页 / 余额快照 / WS 真机 / 审计分区

- **前端**：`frontend/src/views/okx/OkxSettingsPage.vue`（路由 `/okx`，侧栏「OKX 账户」）：
  录入/更新 Key（提交后立即清空明文）、已配置列表（只显示掩码）、凭证与总闸状态、
  余额快照（总权益 / USDT 可用 / 逐币种明细）、WS 状态标签。`npm run build` 通过。
- **余额快照**：`V12__crypto_account_snapshot.sql` 已执行；`OkxWsMessageDispatcher` 的
  `account` 频道改为落库（每币种一行 + `ccy='TOTAL'`，唯一键 `(user_id, ccy, u_time)` 去重）；
  新增 `GET /data/okx/snapshots/latest` 与 `/snapshots?ccy=&limit=`；
  保留 90 天（`OkxAccountSnapshotRetentionJob`）。
- **WS 真机联调**：`OkxWsClient` 接入 `okx.proxy`（此前 WS 不走代理，国内必然握手失败）；
  订阅简写第二段按频道映射（公共频道→`instId`，此前一律 `instType`，会被拒订）；
  public 端点配私有频道时启动即失败；新增 `GET /data/okx/ws/status`（含 `mode`、
  `lastFrameAgeSeconds`、`lastFramePreview`）与 `POST /data/okx/ws/stop`。
  **实测**：经 7897 连 `wss://wspap.okx.com/ws/v5/public` + `tickers:BTC-USDT`，
  收到真实行情帧（`last=74758.1`），`droppedFrames=0`，`lastFrameAgeSeconds=0`。
- **审计分区**：`V13__okx_audit_partition.sql` 已执行（19 行数据完整搬迁，7 个分区）；
  `OkxAuditPartitionJob` 每日 03:10 补齐未来 3 个月并 `DROP PARTITION`；
  `OkxAuditRetentionJob` 在分区形态下自动让位（回滚到 V11 形态时重新接管）。
- **验收**：四模块测试 7/180/23/9 + auth 19 全绿；前端构建通过；
  四个服务 + 前端 dev server 实测可用（经前端代理调 OKX/WS/快照接口均 200）。

## 4. 每期通用验收清单

- [ ] `mvnw -pl common-api,data-service,gateway-service -am test` 全绿；其余三服务 `-am compile` 通过
- [ ] `V7__verify_bars.sql` / `V7__verify_adj_factor.sql` 全绿（A 股作用域谓词未被破坏）
- [ ] 所有写操作有 `[OKX-AUDIT]` 记录，且日志里无凭证、无完整请求体
- [ ] 抓包或日志确认**没有**不带 `x-simulated-trading: 1` 的私有请求
- [ ] 回滚演练通过：关掉本期新增开关后，上一期能力仍可用
- [ ] `GatewayRouteConfigTest` 的路由 ID 期望值同步（每加一条路由必改）
- [ ] 新增/变更的配置项写进 `docs/okx-接入手册.md`，环境变量列进 `.env.example`

## 5. 回滚总原则

- **默认「只加不改」**：新增表、新增类、新增端点、新增开关；回退 = 关闭开关或移除新增物。
- **配置回退优先于代码回退**：`auto-sync` / `trade-enabled` / WS 开关 / MCP 工具注册都能先关。
- **唯一禁止的操作**：改 `instrument` / `bar` / `adj_factor` 的列宽。它不是回滚能救的，会触发千万级大表重建。
- 每期上线前留一份「上一期可用状态」的验证记录，否则回滚无从判断成功。

## 6. 未决决策

### 已关闭（2026-11-09）

| # | 原问题 | 结论 |
|---|---|---|
| 1 | `multi-market-rollout.md` 阶段 4「全自动 / 去掉 approval gate / 密钥加密存储」与「实盘凭证不进服务」冲突 | 按「后出且已接受的 ADR 覆盖早期规划」：**废弃阶段 4 的实盘全自动目标，ADR-0009 决策 4 不变**。`multi-market-rollout.md` 阶段 4 与 `prompts/multi-market-kline.md` 的能力边界已同步标注废弃；4.3 守护/重连由 P3 承接，成交对账改由 WS `orders` 频道 + `tradeId`（P4）承接 |
| 6 | 0.9 双写观察期是否阻塞 P2 | **复核后判定不阻塞**：V10 只 CREATE 新表、不 ALTER/DROP 任何存量表，与双写窗口无冲突。降级为「执行 `V9__retire_stock_daily_data` 或任何触碰存量表迁移之前」的门控（本计划 §1 第 7 条），不再是 P2 前置 |

| 5 | WS 客户端技术选型（是否引入第三方库） | **已关闭（2026-11-09）**：JDK 17 内置 `java.net.http.WebSocket`，不引第三方库；与 REST 侧 `OkxClient` 的 JDK `HttpClient` 一致，理由见 §3 P3 |

### 仍需按期关闭

| # | 待决问题 | 最晚时点 | 影响 |
|---|---|---|---|
| 2 | rollout 阶段 2.4「不做部分成交」vs `fillSz` / `partially_filled` | P4 之前 | 决定订单状态机与撮合口径 |
| 3 | ~~审计是否从日志升级为持久化表~~ **已关闭（P10 落地）** | — | 见 ADR-0010 决策 3：日志 + `okx_audit_log` 双写，保留 2 个月 |
| 4 | ~~MCP 客户端用哪个用户身份、scope 如何映射、token 怎么签发与轮换~~ **已关闭（P10 落地）** | — | 见 ADR-0010 决策 4：client_credentials + `type=SERVICE` + `orders:read` + aud 校验；Agent 只读 |
| 7 | ~~stage0 旧方案与 rollout 3.2 写前日志口径对齐~~ **已关闭（P4 实现承载）** | 口径由 `CryptoOrderService` + 本计划 §3 P4 + 手册第八节承载；`multi-market-stage0.md:655` 已标注废弃，不存在两套订单模型 |

## 7. 实施收尾（P5–P9，2026-11-09）

| 期 | 落地内容 |
|---|---|
| P5 | 根 `pom.xml` 加 `spring-ai-bom 2.0.1`、`data-service` 加 `spring-ai-starter-mcp-server-webmvc`；`OkxMcpTools` 暴露 **9 个只读工具**（行情/K线/盘口/资金费率/持仓量/标记价/余额/持仓/挂单）。**零写工具**——MCP 调用不经过 `OkxController`，会把写 scope 白名单和写前日志一起绕过去。端点 `/okx/mcp`（若回落到默认 `/mcp`）都已在 `OkxAuthPaths` 保护清单内 |
| P6 | `OkxModuleCatalog` 登记 **36 项**受控端点，覆盖 `market/spot/swap/futures/option/account/bot` 七模块；`OkxCapabilityService` 统一执行，写端点一律 `createPending → 发网络 → markSubmitted`；`OkxCapabilityController` 用 `OkxAuthz` 做读写 scope 分级。**用白名单表代替 60+ 个手写方法**，新增能力只加一行 |
| P7 | `OkxMarketStreamService` 处理 `tickers`/`candle*`，只做实时视图、**绝不写 `bar`**（bar 是已完结事实表） |
| P8 | `OkxDownsampler` 按 1 秒稀疏化 `books*`/`bbo*`，只有降采样后的帧写 `crypto_book_snapshot`；原始 10ms/100ms 流只在 `OkxWsFrameBuffer` 内存里 |
| P9 | 新建第 7 模块 `ai-service`（8085），`AgentOrchestrator` 规则编排 + `RestClient` 调公共 `/market/bars`；网关加 `/api/ai/**` 路由并纳入 JWT。**刻意不内嵌 LLM**：模型供应商/Key 由使用者决定 |

**验证**：`mvnw -pl common-api,data-service,gateway-service,ai-service -am test` 通过；
全反应堆 7 模块 `compile` 通过；`data-service` 真实启动成功
（`Started DataApplication in 2.391 seconds`，MCP stateless 自动配置加载，Tomcat 18084）。

**代码已完成但下列仍需补**：真实 OKX 网络联调（模拟盘凭证 + 代理）、LLM 接入、
`account` 频道余额快照表（V10 未建）、前端实时视图、MCP `tools/list` 的 HTTP 端到端验证、
服务账号身份/token 方案（§6 第 4 项）。

## 8. 明确不做（防止范围回潮）

实盘自动下单与提币、`okx-trade-mcp` npm 包 / Node 进程 / 第二套凭证、放宽 `varchar(20)`、
高频深度落 MySQL、改 `bar` 的 9 周期 CHECK、Agent 无人值守全自动交易。
任何一条要解禁，先走新 ADR 取代 ADR-0009，再更新本文。
