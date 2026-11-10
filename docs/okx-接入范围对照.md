# OKX 接入范围对照

决策依据：`docs/adr/0008-okx-crypto-market-and-trading.md`（已部分取代）、`docs/adr/0009-okx-mcp-tools-and-full-api-scope.md`
**执行计划：`docs/okx-实施计划.md`**（本文是范围与阻塞清单，不是排期文档；任务拆解、验收标准、回滚点看实施计划）
核查日期：2026-11-07 ~ 11-09
本文回答的问题：**当前系统接入了 OKX 的哪些能力，四份输入文档各覆盖什么，接下来接什么、不接什么。**

## 一、一句话结论

**2026-11-09 收尾状态**：P0–P9 代码已全部落地——V5 REST、WS 客户端与私有/公共频道解码、
9 个 MCP 只读工具、七模块 36 项受控端点、订单状态机与写前日志、第 7 个模块 `ai-service`。
V10 的 8 张加密域表已在 `data_db` 执行。权限**永久停在模拟盘可写**，实盘物理隔离已落地
（代码里不存在实盘分支）。**仍未联调**：真实 OKX 网络（需模拟盘凭证 + 代理）、LLM、账户快照表、前端实时视图。

系统是「复利」股票复盘系统（A 股 / 美股行情复盘、交易记录、盲测练习），不是交易终端。OKX 是 2026-11-05 新增的多市场扩展。

## 二、四份输入文档 × 现状

| 输入文档 | 覆盖内容 | 现状 | 本期（ADR-0009） |
|---|---|---|---|
| `okx.txt` | 交易账户全流程：产品配置、WS 市场数据、账户模式与杠杆、订单管理、orders / account / positions 频道、`tradeId` 对账、自成交保护、分页 | 只用了 REST 的 `public/instruments`、`market/candles`、`market/history-candles` + 6 个私有端点；**WS 零频道**；改单、批量、杠杆、合约、STP、对账全无 | 七模块全开 + WS 全频道，分三期 |
| `okx3.txt` | V5 REST / WebSocket 全量 API（约 1.4MB） | 实际只调 **8 个端点**，下单锁死 `SPOT + tdMode=cash + market/limit` | 按七模块逐步补齐；WS 地址按 443 / `wspap` 修正 |
| `okx2.txt` | Agent Trade Kit：MCP / CLI / Skills，7 模块 60+ 工具 | **完全没接** | 只采用「工具契约」思路，**不安装该 npm 包**，由 Java 自建 MCP Server |
| `okx-mcp.txt` | `okx-trade-mcp` npm alias 包（1.2.8，发布于 7 个月前）与 `mcpServers` 配置示例 | `~/.workbuddy/mcp.json` 为 `{"mcpServers": {}}`，全库无 MCP 代码/配置 | 不注册、不引入；版本陈旧，以 GitHub 为准 |

## 三、已落地的能力（真实代码，非设计稿）

位置：`data-service/src/main/java/com/fuli/data/okx/`（9 个类）、`config/OkxConfig.java`、`controller/OkxController.java`。

| 类别 | 端点 | 代码位置 |
|---|---|---|
| 公共免签 | `GET /api/v5/public/instruments` | `OkxMarketService.java:66` |
| 公共免签 | `GET /api/v5/market/candles` | `OkxMarketService.java:111` |
| 公共免签 | `GET /api/v5/market/history-candles` | `OkxMarketService.java:141` |
| 私有只读 | `GET /api/v5/account/config` | `OkxTradeService.java:44` |
| 私有只读 | `GET /api/v5/account/balance` | `OkxTradeService.java:48` |
| 私有只读 | `GET /api/v5/account/positions` | `OkxTradeService.java:55` |
| 私有只读 | `GET /api/v5/trade/orders-pending` | `OkxTradeService.java:62` |
| 私有写入 | `POST /api/v5/trade/order` | `OkxTradeService.java:112` |
| 私有写入 | `POST /api/v5/trade/cancel-order` | `OkxTradeService.java:121` |

签名：`OkxClient` 四头 `OK-ACCESS-KEY / SIGN / TIMESTAMP / PASSPHRASE` + **无条件** `x-simulated-trading: 1`，
HMAC-SHA256/Base64 见 `OkxSigner`。HTTP 客户端是 JDK 原生 `java.net.http.HttpClient`，
**未引入任何 OKX SDK / ccxt / okhttp / WS 库**。

实测（`docs/okx-接入手册.md`「已验证的真实链路」，2026-11-07）：466 个 USDT 现货对写入 `instrument`、
897 根已完结日线写入 `bar`、`/market/bars` 按统一契约读通。

默认安全侧（`application.yml` 的 `okx` 段）：`auto-sync: false`、`trade-enabled: false`；
**模拟盘是代码常量**，不是配置项（ADR-0009 决策 4）。
**鉴权（2026-11-09 实施，2026-11-10 由 ADR-0010 升级）**：`/data/okx/**` 需 Bearer JWT，
写操作另需用户级 `sys_user.okx_trade_enabled=1`（原 `OKX_WRITE_USER_IDS` 白名单已删除），
凭证按用户存于 `data_db.okx_account`（AES-GCM 密文），审计双写 `okx_audit_log`；
经网关走 `/api/data/okx/**`（复用已有 `/api/data/**` 路由），直连 8084 同样受
`OkxAuthenticationFilter` 校验，不能绕过。缺 `JWT_SECRET` 时这些端点返回 503。

**P4 已接通（2026-11-09）**：下单走「写前日志 → 发网络 → 登记结果」，`orders` / `positions`
频道经 `OkxWsMessageDispatcher` 落 `crypto_order` / `crypto_fill` / `crypto_position`；
`OkxOrderReconciler` 提供 `orders-pending` 与本地单的只读差异报告。
V10 的 8 张表已在 `data_db` 建好（迁移记录见 `docs/okx-实施计划.md` §3 P2）。

## 四、本期范围与不做的事

| 维度 | 决定 |
|---|---|
| 接入通道 | Java 侧 MCP Server（Spring AI 2.0.x + `spring-ai-starter-mcp-server-webmvc`），**不用 npm 包 / Node / 第二套凭证** |
| 模块落点 | 本期不新建 `ai-service`；工具层薄封装在 data-service，端点经 gateway `/api/okx/mcp/**` 纳入 JWT |
| 能力 | `market / spot / swap / futures / option / account / bot` 七模块全开，WS 全频道 |
| 权限 | **只读 + 模拟盘可写**；实盘凭证不进 Java 服务；提币永不开启 |
| 数据模型 | 新建加密域独立表；不放宽 `varchar(20)`、不动 `bar` / `adj_factor` 大表；独立订单表 |
| WS | 一期私有频道 → 二期公共行情 → 三期深度；10ms 深度只进内存环形缓冲 + 降采样，不落 MySQL |

**明确不做**：实盘自动下单、提币、把高频深度写进 MySQL、放宽 `bar` 的 9 周期 CHECK、新建 Agent 编排模块（后建）。
**排期与验收**：见 `docs/okx-实施计划.md`（P0/P1 已完成，P2 起未开工）。

## 五、上线硬阻塞

| # | 阻塞项 | 证据 |
|---|---|---|
| 1 | ~~下单端点无身份 / 无鉴权 / 无审计~~ **已闭环（2026-11-09）** | `OkxAuthenticationFilter` + `OkxAuthz` 读写 scope + `[OKX-AUDIT]` 结构化审计；见 `docs/okx-接入手册.md`「鉴权与审计」 |
| 2 | ~~MCP HTTP 端点默认未鉴权~~ **已闭环（P5）** | Spring AI 2.0.1 MCP Server 已落地并真实启动验证；9 个只读工具；`/okx/mcp` 与 `/mcp` 均受 `OkxAuthenticationFilter` 保护，网关 `/api/okx/mcp/**` 路由已纳管 |
| 3 | 符号列宽 `varchar(20)` 零余量，合约 `BTC-USDT-SWAP.CRYPTO` 为 22 字符 | `V7__instrument_bar_adj_factor.sql:23,45,64`、`OkxMarketService.java:79-83` |
| 4 | ~~无 WS 客户端、无背压~~ **已有客户端与有界缓冲（P3，默认关闭）** | `com.fuli.data.okx.ws`：`OkxWsClient`（JDK 17 内置 WebSocket、登录/订阅重放/重连退避/心跳/kill switch）+ `OkxWsFrameBuffer`（满则丢最旧并计数）。**仍不引入 MQ**，按设计高频流只进内存 |
| 5 | ~~无可复用的订单 / 成交 / 持仓表~~ **已有独立表与订单域服务（P2，迁移未执行）** | `V10__crypto_domain.sql` 8 张表 + `com.fuli.data.crypto`（状态机 / 写前日志 / 对账）；`trade_record` 仍是手工记账，不复用 |
| 6 | `bar` 只有 OHLC，无深度 / 资金费率 / Greeks | `V7__instrument_bar_adj_factor.sql:44-59` |
| 7 | ~~既有方案冲突~~ **已闭环（P4）** | `stage0:655` 旧方案已废弃；写前日志口径由 `CryptoOrderService` + 实施计划 §3 P4 + 手册第八节承载，rollout 3.2 的「先落库再发网络」已实现 |
| 8 | ~~`rollout` 阶段 4「全自动」与「实盘凭证不进服务」冲突~~ **已闭环（2026-11-09）** | 按「后出且已接受的 ADR 覆盖早期规划」废弃阶段 4 的实盘全自动目标，ADR-0009 决策 4 不变；`multi-market-rollout.md` 阶段 4、`prompts/multi-market-kline.md` 已同步标注 |
| 9 | ~~Q12「实盘凭证不进 Java 服务」未在代码里落地~~ **已闭环（2026-11-09）** | `OkxConfig.isSimulated()` 为常量且无 setter；`OkxClient` 无条件加 `x-simulated-trading: 1`；`OkxSimulatedGuard` 对旧 `OKX_SIMULATED=false` 启动 fail-fast；`OkxSimulatedGuardTest` 覆盖常量、无 setter、fail-fast 与请求头 |

## 六、事实修正记录

| 处 | 原文 | 更正 | 依据 |
|---|---|---|---|
| `ADR-0008:95` | `wss://ws.okx.com:8443/ws/v5/business` | 8443 已下线迁 443，地址不带端口；实盘 `/ws/v5/{public,private,business}`，模拟盘 `wss://wspap.okx.com/ws/v5/{...}` | `okx3.txt`「WebSocket 8443 端口下线」「模拟盘交易」章节 |
| `GLOSSARY.md:13` | `BTCUSDT.CRYPTO` | `BTC-USDT.CRYPTO` | ADR-0008 决策 1 已取代该示例，词汇表当时未同步 |
| `multi-market-stage0.md:655` | `trade_record` 加 `source` 复用手工表 | 废弃，改独立订单表 | ADR-0009 决策 5 |
| `OkxConfig` / `application.yml` | `simulated: ${OKX_SIMULATED:true}` 可关 | 改为系统常量，无 setter、无配置绑定；旧 `OKX_SIMULATED=false` 启动失败 | ADR-0009 决策 4 / Q12 |
