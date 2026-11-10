# ADR-0009：OKX 能力全面接入与 Java 侧 MCP 工具层

- 状态：已接受（2026-11-09）
- 关联：ADR-0006（标的两段式）、ADR-0007（bar 统一存储与时间轴）、ADR-0008（OKX 首个加密数据源）
- 取代：**ADR-0008 决策 6「先不上 WS」与决策 7「量化交易边界」**；ADR-0008 其余决策（符号、周期、ts、单位、只写 `confirm=1`）继续有效
- 依据：2026-11-07 ~ 11-09 设计访谈（Q1–Q14），输入为 OKX 交易账户文档、Agent Trade Kit 文档、V5 API 全量文档与 `okx-trade-mcp` 包说明
- 范围对照：`docs/okx-接入范围对照.md`

## 背景

ADR-0008 在 2026-11-05 把 OKX 作为首个加密数据源接入，范围刻意收窄：只做 V5 REST 的行情采集（实际 8 个端点），私有接口只开放 6 个，下单锁死在 `SPOT + tdMode=cash`，并明确「先不上 WS」「不上合约/杠杆/期权/批量」「默认模拟盘 + 默认关闭」。实测链路已跑通（466 个 USDT 现货对、897 根日线，见 `docs/okx-接入手册.md:72-84`）。

2026-11-09 目标变化：系统后续要内置 Agent 智能体，需要把 OKX 的完整能力暴露为可发现、可调用、可鉴权的工具。核查确认当时全库**没有任何** AI / Agent / MCP 基础设施（无 `spring-ai`、MCP SDK、`@Tool`、LLM 配置），`~/.workbuddy/mcp.json` 也未注册任何服务器。本 ADR 因此一并决定「能力范围」「接入通道」「权限边界」「数据模型」四件事。

## 决策

### 1. 接入通道：MCP 由 Java 服务提供，不引入 npm 包

「接入 MCP」与「安装 `okx-trade-mcp`」不是一回事。MCP 是协议，那个 npm 包只是它的一种实现，且是 Node.js 的 stdio 进程：接进 Java 服务就要 spawn Node、额外维护 `~/.okx/config.toml` 里的第二套凭证，与现有 `OkxClient`（`data-service/.../okx/OkxClient.java`）走环境变量的凭证并行，形成两套限频、两套风控、两套权限。当前 `docker-compose.yml` 只有 mysql / redis / nacos 三个基础设施容器，Java 服务本地启动，引入 Node 还会改变部署形态。

**决定**：用 Spring AI 2.0.x 的 `spring-ai-starter-mcp-server-webmvc` 把现有 OKX 能力暴露成 MCP 工具，走 Streamable HTTP。兼容性已核实：Spring AI 2.0.0 GA（2026-06-12）/ 2.0.1 要求 Spring Boot 4.0/4.1 + Java 17+，与根 `pom.xml:10,31` 的 Boot 4.1.0 + Java 17 对齐；Servlet 栈与 `spring-boot-starter-web` 一致；内置 MCP Java SDK 2.0.0，无需 Node 运行时。工具用 `@McpTool` / `@McpToolParam` 声明。

### 2. 模块落点：本期不新建 ai-service

MCP 工具层作为 `OkxMarketService` / `OkxTradeService` 的薄封装落在 **data-service**，无跨模块调用；MCP JSON-RPC 端点经 gateway 新增 `/api/okx/mcp/**` 路由纳入 `JwtGlobalFilter`（`GatewayRouteConfig.java:30-48` 现为 5 条路由）。Agent 编排层后建，届时再独立成第 7 个模块。

理由：Agent 目前不存在，先建编排层会让 MCP 没有消费者；已定「先定工具契约和鉴权链路，Agent 编排层后建」。代价是 data-service 多引一个 Spring AI BOM。

### 3. 能力范围：七模块全开 + WebSocket 全频道

`market / spot / swap / futures / option / account / bot` 七个模块全开；`orders` / `account` / `positions` / `books` 等频道与改单、批量、杠杆、期权 Greeks、策略委托、网格与 DCA 机器人全部纳入范围。**此条取代 ADR-0008 决策 6 和决策 7**。

### 4. 凭证档位：模拟盘可写为上限，实盘凭证不进 Java 服务

这是本 ADR 唯一不可放宽的边界。凭证档位定义为「只读 / 模拟盘可写 / 实盘可写」三档，系统**只到第二档**：

- 实盘 API Key / Secret / Passphrase **不进入 Java 服务**，系统里根本不存在实盘写路径。需要实盘成交时由人在 OKX 侧执行，系统最多产出下单建议单。
- 「实盘写必须是独立的人工审批动作而不是一个配置项」由此以物理隔离兑现——没有实盘 Key，就不存在被绕过、被代理剥掉 `x-simulated-trading` 头、或被配置误改的可能（ADR-0008:106 指出的正是该风险）。
- 提币权限**永不开启**，任何档位、任何环境。
- 模拟盘与实盘 Key 不通用；REST 侧两者同 host 只差 `x-simulated-trading: 1`，但 WebSocket 是独立域名（决策 7）。

### 5. 数据模型：加密域独立表，不动存量大表

新建加密域独立表（`crypto_instrument` / `crypto_order` / `crypto_position` / `crypto_book` 等），**不放宽** `instrument.code` / `bar.instrument_code` / `adj_factor.instrument_code` 的 `varchar(20)`（`sql/migrations/V7__instrument_bar_adj_factor.sql:23,45,64`）。原因是 `BTC-USDT-SWAP.CRYPTO` 已 22 字符、期权更长，放宽列宽要重建 `bar`（7.67M 行）与 `adj_factor`（16.8M 行）两张大表，而 `multi-market-rollout.md:27` 的 0.9 双写观察期尚未部署。

代价：ADR-0006 / ADR-0007 的「一张 bar 全市场」统一契约被打破。`bar` 继续只存已完结 OHLC（ADR-0008 决策 5 仍有效），深度、资金费率、持仓量、标记价、Greeks 一律进独立表。

订单采用**独立订单表 + 所有订单先落库再发网络**，取代 `docs/multi-market-stage0.md:655` 的「`trade_record` 加 `source` 复用手工表」方案（该行已标注废弃）。`live → partially_filled → filled / canceled` 状态机、`clOrdId` 幂等、`tradeId` 与 `posId` 对账，手工记账表（`quantity INT` 股、`trade_type` 二值、`stock_code varchar(16)`）表达不了。

### 6. WebSocket 分三期，高频深度不落 MySQL

1. 私有频道 `orders` / `account` / `positions`：事件驱动，直接喂独立订单表与 `tradeId` 对账，价值最高。
2. 公共 `tickers` / `candle*`：实时视图，**不写 `bar`**。
3. `books5` / `books-l2-tbt`：10ms 深度，只进内存环形缓冲 + 降采样，**绝不落 MySQL**。

全库目前无 WS 客户端、无 MQ / Redis Stream / 有界队列 / 背压（`OkxClient.java:43` 是同步 `HttpClient`）。连接管理、重连、序号校验、背压需整套新建，是本次工程量最大的一块，单独排期，不混进「接 MCP」。

### 7. WebSocket 地址更正

`wss://ws.okx.com:8443/...` 中的 **8443 端口已宣布下线，迁至 443**，正确地址不带端口：实盘 `wss://ws.okx.com/ws/v5/{public,private,business}`；**模拟盘另用** `wss://wspap.okx.com/ws/v5/{public,private,business}`，域名不同。因决策 4 把权限停在模拟盘，WS 全量实际连的是 `wspap` 域。

### 8. 鉴权是上线硬前置

决策当时的隐患：Spring 官方警告 HTTP 传输的 MCP 端点**默认暴露未经鉴权的 JSON-RPC**，任何可达客户端都能列出并调用所有工具；而 `POST /data/okx/trade/order` 当时在 data-service:8084 是「直连、无认证、仅内网，gateway 未开路由」，`JwtAutoConfiguration` 也确认 data-service 不依赖 JWT——**无身份、无鉴权、无审计**。

**该缺口已于 2026-11-09 闭环**（见「后果」的实施状态）：`/data/okx/**` 与 MCP 候选路径统一走服务侧 JWT 校验，按 `orders:read` / `orders:write` 分 scope，并输出 `[OKX-AUDIT]` 结构化审计；网关侧另有 `JwtGlobalFilter` 双层校验。
  **写权限的白名单形式已于 2026-11-10 被 ADR-0010 取代**：`OKX_WRITE_USER_IDS` 环境变量删除，
  改为用户级 `sys_user.okx_trade_enabled`；审计也从纯日志升级为「日志 + `okx_audit_log`」双写。

将来落地 MCP server 时仍须遵守：写工具标注 `destructiveHint` 并强制走审批；不做 token passthrough（MCP 规范禁止转发客户端令牌，要求校验 `aud`）；不要绕过 `OkxAuthPaths` 里已登记的保护路径。

## 被否决的选项

| 选项 | 否决理由 |
|---|---|
| 注册 `okx-trade-mcp` 到 WorkBuddy 等 AI 客户端 | 能力落在客户端，与 Java 服务无关，后续系统内 Agent 用不上 |
| Java 当 MCP Client 调 npm 包 | 引入 Node 进程、第二套凭证与限频、部署形态变化，且与已有 `OkxClient` 重复 |
| 本期新建 `ai-service` 第 7 模块 | Agent 尚未存在，MCP 无消费者；跨模块还要先补齐 Feign 内部鉴权 |
| 复用 `trade_record` 加 `source` | 表达不了订单状态机、幂等键、成交与持仓对账 |
| 放宽 `varchar(20)` 统一进 `bar` | 需重建 7.67M + 16.8M 行大表，且与 0.9 未部署的回滚窗口叠加 |
| 实盘审批服务 + 每笔二次确认 | 仍是「可配置的实盘写路径」；物理隔离更彻底地兑现「不是配置项」 |

## 后果

- **与既有排期冲突已解决（2026-11-09）**：`docs/multi-market-rollout.md` 阶段 4「全自动 / 去掉 approval gate / 密钥 AES-GCM 加密存储」与决策 4 直接冲突。按「后出且已接受的 ADR 覆盖早期规划」处理，
  **改规划文档、不改本 ADR**：阶段 4 已标注废弃，`docs/prompts/multi-market-kline.md` 的能力边界同步更正；
  阶段 3.3「信号推送 → 人工确认 → 下发」与 3.4 四道风控仍相容，4.3 的守护/重连由实施计划 P3 承接。
  **决策 4 不变**：实盘凭证不进 Java 服务，提币永不开启。
- ADR-0008 后果中「验收脚本按市场分域」「符号列宽零余量」「周期只有 9 个」等约束继续有效；加密衍生品数据进独立表后，`bar` 的 9 周期 CHECK 不必动。
- `docs/multi-market-stage0.md:655` 的方案已废弃；阶段 2.4「不做部分成交」与接入 `fillSz` / `partially_filled` 的诉求相左，实现时需一并复核。
- 术语表新增「接入通道」「Agent 智能体」「工具」「凭证档位」，并修正 `BTCUSDT.CRYPTO` → `BTC-USDT.CRYPTO`（ADR-0008 决策 1 早已取代该示例，词汇表当时未同步）。
- **实施状态（2026-11-09 更新）**：决策 8 的鉴权已先行落地——`data-service` 新增
  `OkxAuthenticationFilter` / `OkxAuthPaths` / `OkxAuthz` / `OkxAuthProperties`，`OkxController`
  全端点接入读写 scope 与 `[OKX-AUDIT]` 结构化审计；网关预置 `/api/okx/mcp/**` 路由；
  `common-api` 的 `AuthenticatedUser.ATTRIBUTE` 放宽为 public 供服务侧写入身份（行为不变）；
  新增   `OkxAuthenticationTest` 覆盖 401/403/503 与伪造身份头。
- **决策 4 的物理隔离亦已落地（2026-11-09）**：核查发现原 `OkxConfig.simulated` 仍是
  可被 `OKX_SIMULATED=false` 关掉的配置项，等于留着实盘写路径。现已改为系统常量
  （无 setter、无配置绑定），`OkxClient` 对每个私有请求**无条件**携带 `x-simulated-trading: 1`，
  `OkxSimulatedGuard` 让旧实盘配置启动即失败；`OkxSimulatedGuardTest` 覆盖常量、无 setter、
  fail-fast 与实际请求头。三模块测试与其余服务编译全绿。
- **P2 已完成（2026-11-09）**：`com.fuli.data.crypto` 下 8 实体 / 8 Mapper / `CryptoOrderState`
  状态机 / `CryptoOrderService`（写前日志、`clOrdId` 幂等、终态冻结、乱序忽略、持仓对账）；
  `V10__crypto_domain.sql` **已在 data_db 执行**（副本库预演 + 存量三表 DDL/行数逐字未变 + V7 验收全绿）。
- **P3 已完成（2026-11-09）**：`com.fuli.data.okx.ws` 用 JDK 17 内置 WebSocket 实现连接、
  登录（复用 `OkxSigner`，时间戳为 Unix 秒）、订阅重放、指数退避重连、心跳、kill switch 与
  有界环形缓冲；`OkxWsProperties.validate()` **强制 URL 为模拟盘域 wspap.okx.com**，
  填实盘域启动即失败——决策 4 在 WS 链路上的延伸。默认 `okx.ws.enabled=false`。
- **P4 已完成（2026-11-09）**：`OkxTradeService.placeOrder` 改为
  `createPending → 发网络 → markSubmitted`，`clOrdId` 为空时生成 32 位幂等键，
  命中未完成单直接跳过发单；`OkxWsMessageDispatcher` 解码 `orders` / `positions` 频道并落库
  （`account` 本期不落库，V10 未建账户快照表，留 P6）；`OkxOrderReconciler` 提供
  `orders-pending` 与本地单的**只读**差异报告（只报告、不自动修数据）。
  在 `data_db` 上 smoke 验证 `crypto_order` 可写且状态 CHECK 拒绝非法值。
- **P5–P9 已落地（2026-11-09）**：Spring AI 2.0.1 MCP Server（9 个只读 `@McpTool`，零写工具，
  端点受 `OkxAuthPaths` 保护）；七模块 36 项受控端点 + 统一执行器（写端点统一走写前日志 + 白名单）；
  WS 公共行情不写 `bar`、深度 1s 降采样稀疏落库；新建第 7 模块 `ai-service`（规则编排，未接 LLM）。
  全反应堆 7 模块编译 + 测试 + data-service 真实启动均通过。
- **执行计划**：`docs/okx-实施计划.md` §7 收尾说明。**仍未实施**：真实 OKX 网络联调、
  LLM 接入、`account` 余额快照表、前端实时视图、MCP `tools/list` 的 HTTP 端到端验证；
  `mcp.json` 未改，实盘凭证未进服务。决策 4（实盘隔离）与决策 1（不装 npm 包）全程未被突破。
