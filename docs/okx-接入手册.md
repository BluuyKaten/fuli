# OKX 接入手册（行情 + 模拟盘交易 + Agent 只读）

决策依据：`docs/adr/0008`（首个加密数据源）、`docs/adr/0009`（能力范围 / MCP / 实盘隔离）、
`docs/adr/0010`（多账号凭证 / 用户级写权限 / 审计落表 / Agent 只读身份）
执行计划：`docs/okx-实施计划.md` ｜ 范围对照：`docs/okx-接入范围对照.md`
前端契约：`docs/okx-前端契约.md`
代码位置：`data-service/.../okx/`（`OkxClient` / `OkxCredentialService` / `OkxTradeService`）、
`data-service/.../security/`（鉴权与审计）、`ai-service/.../agent/`（服务令牌与只读客户端）

接口前缀：`/data/okx/**`。**所有端点需要 JWT 鉴权**；
写操作还需要用户级 `okx_trade_enabled=1`。经网关走 `/api/data/okx/**`（自带 JWT 校验），
直连 data-service:8084 也由服务侧 `OkxAuthenticationFilter` 校验，无法绕过。

## 一、配置

```yaml
okx:
  base-url: https://www.okx.com     # 模拟盘与实盘同址，不要填模拟盘域名
  # 凭证不在这里（ADR-0010）。每个用户在「系统设置」里录自己的模拟盘 Key，
  # 密文存 data_db.okx_account；服务按请求里的 userId 解密使用。
  # 模拟盘是系统常量（ADR-0009 决策 4）：无开关、无环境变量，
  # 所有私有请求恒定携带 x-simulated-trading: 1
  proxy: ${OKX_PROXY:}              # 127.0.0.1:7897 或 http://127.0.0.1:7897，空=直连
  quote-ccy: USDT
  symbols: BTC-USDT,ETH-USDT,SOL-USDT
  bars: 1d
  auto-sync: false                  # 定时同步开关，默认关
  trade-enabled: false              # 全局总闸，默认关；用户级开关见下
  max-order-size: "0"               # 单笔上限（基础币），0=不限
  ws-owner-user-id: 0               # WS 私有频道用哪个用户的凭证；0=不登录私有频道
  mcp-owner-user-id: 0              # MCP 私有工具用哪个用户的凭证；0=工具返回「未配置身份」
  agent-owner-user-id: 0            # 服务账号读私有数据时代表谁；0=服务令牌读私有端点 400

  credential:
    kek: ${OKX_CRED_KEK:}           # 32 字节，缺失时启动失败（见 §十一）
    key-version: 1

  auth:
    jwt-secret: ${JWT_SECRET:}                  # 必须与 gateway/auth 一致，否则 OKX 端点全 503
    permission-base-url: ${AUTH_SERVICE_URL:http://127.0.0.1:8081}
    internal-key: ${INTERNAL_KEY:}              # 查用户交易权限用，须与 auth-service 的 fuli.internal-key 一致
    permission-cache-seconds: 30                # 权限缓存；0=不缓存
    service-token-audience: fuli-data           # 服务令牌受众，防 token passthrough

  audit:
    cleanup-cron: ${OKX_AUDIT_CLEANUP_CRON:0 30 3 * * *}   # 每日 03:30 清理 2 个月前的记录

  ws:
    enabled: ${OKX_WS_ENABLED:false}          # 真机联调/看实时行情时开
    url: ${OKX_WS_URL:wss://wspap.okx.com/ws/v5/private}
    subscriptions: ${OKX_WS_SUBSCRIPTIONS:account,positions:SWAP,orders:ANY}
    # …其余见 §八
```

**变量从哪里来**（三种方式任选，优先级从高到低）：

| 方式 | 适用 | 说明 |
|---|---|---|
| IDEA Run Configuration → Environment variables | 本地调试 | 优先级最高，覆盖 `.env` 与 yml |
| 系统环境变量 / `export` | 命令行 | 同上 |
| 仓库根 `.env` | `mvnw spring-boot:run` | 已在各服务 `application.yml` 用 `spring.config.import` 引入；文件缺失时静默跳过 |

> ⚠️ **`.env` 必须是 LF 换行**。CRLF 会让每个值多一个 `\r`：`OKX_CRED_KEK` 变成 33 字节直接启动失败，
> `DB_PASSWORD` 带上 `\r` 会连不上库。用 IDEA 注入则不受此影响。
> ⚠️ **同一个密钥在不同服务里必须同源**：如果 data-service 在 IDEA 里启动、auth-service 用 `mvnw` 启动，
> 两边读到的 `JWT_SECRET` / `INTERNAL_KEY` 必须完全一致，否则网关签发的令牌在服务侧验不过。

环境变量清单：

| 变量 | 必填 | 说明 |
|---|---|---|
| `JWT_SECRET` | 是 | ≥32 字节，与 gateway / auth 完全一致；缺失时 OKX 端点 503 |
| `OKX_CRED_KEK` | **是** | 32 字节，凭证加密主密钥；缺失时 data-service 启动失败 |
| `OKX_PROXY` | 国内必需 | 形如 `127.0.0.1:7897`（不带协议也认）；实测 Clash 混合口 200/0.36s |
| `OKX_TRADE_ENABLED` | 下单时 | `true` 才开全局总闸（默认 false）；仍需用户级 `okx_trade_enabled=1` |
| `OKX_WS_ENABLED` | 看实时行情时 | `true` 才连 WS；默认 false |
| `OKX_WS_URL` | 同上 | 公共 `/ws/v5/public`（免登录）；私有 `/ws/v5/private`（需 Key） |
| `OKX_WS_SUBSCRIPTIONS` | 同上 | 逗号分隔；`tickers:BTC-USDT` 或 `account,positions:SWAP,orders:ANY` |
| `INTERNAL_KEY` | 是 | 与 auth-service 的 `fuli.internal-key` 一致，用于查用户交易权限、写建议单 |
| `AUTH_SERVICE_URL` | 否 | 默认 `http://127.0.0.1:8081` |
| `AI_SERVICE_CLIENT_SECRET` | Agent 需要 | auth-service 与 ai-service 两侧必须一致 |
| `OKX_WS_OWNER_USER_ID` / `OKX_MCP_OWNER_USER_ID` / `OKX_AGENT_OWNER_USER_ID` | 按需 | 见 §一 注释 |

**已删除的环境变量**（ADR-0010）：`OKX_API_KEY` / `OKX_SECRET_KEY` / `OKX_PASSPHRASE`
（凭证改为按用户存库）、`OKX_WRITE_USER_IDS`（改为 `sys_user.okx_trade_enabled`）、
`OKX_SIMULATED`（设为 `false` 会让服务启动失败）。
残留这些变量不会报错，但**也不会有任何效果**——排查「为什么还是没权限」时先确认这点。

## 二、鉴权、权限与审计

| 项 | 行为 |
|---|---|
| 保护范围 | `/data/okx/**`，以及 `/okx/mcp/**`、`/mcp/**`（MCP server 端点） |
| 读 scope | 有效 Bearer 令牌即可；服务令牌需带 `orders:read` |
| 写 scope | 有效令牌 + **该用户 `okx_trade_enabled=1`** + `okx.trade-enabled=true`；服务令牌一律 403 |
| 凭证 scope | `orders:credential`：只要求本人身份，**不要求交易权限**（否则「先配 Key 再授权」成死锁） |
| 缺密钥 | `JWT_SECRET` 未注入或 <32 字节 → 受保护端点一律 **503**（fail-closed），A 股接口不受影响 |
| 身份来源 | 只信服务端解析 JWT 得到的 attribute；伪造 `X-User-Id` 无效，网关也会剥离外部伪造的身份头 |
| 服务令牌 | `type=SERVICE`、`scope=orders:read`、`aud=fuli-data`、15 分钟过期、**无 userId**；受众不符 401 |
| 实盘隔离 | 代码里**不存在实盘分支**：`OkxConfig.isSimulated()` 是常量、`OkxClient` 无条件加 `x-simulated-trading: 1`；旧配置 `OKX_SIMULATED=false` 由 `OkxSimulatedGuard` 启动 fail-fast |
| 审计 | `[OKX-AUDIT]` 结构化日志 **+** `data_db.okx_audit_log` 双写；落表失败只 WARN，不影响主流程；保留 2 个月 |

### 权限从哪来

```
用户录 Key（不需要权限） → 管理员 UPDATE auth_db.sys_user SET okx_trade_enabled=1
                        → data-service 缓存 30s → 写操作放行
```

- 收回权限：把列改回 0，最迟 30 秒生效；要立即阻断就撤销令牌。
- 查询接口：`GET /auth/internal/okxTradeEnabled?userId=`（服务间，需 `X-Internal-Key`）。
- data-service 侧 **fail-closed**：auth-service 不可达/超时/返回非 200 → 按无权限处理。

## 三、先验证连通（不花任何额度）

```bash
export TOKEN='<登录后拿到的 JWT>'
AUTH="Authorization: Bearer $TOKEN"

# 0) 状态自检（读 scope）
curl -s --noproxy '*' -H "$AUTH" http://127.0.0.1:8084/data/okx/status

# 1) 录入自己的模拟盘凭证（写凭证 scope，不需要交易权限）
curl -s -X POST -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"label":"模拟盘-主账户","apiKey":"...","secretKey":"...","passphrase":"..."}' \
  http://127.0.0.1:8084/data/okx/account

# 2) 确认只回了掩码
curl -s -H "$AUTH" http://127.0.0.1:8084/data/okx/account

# 3) 同步现货标的基础信息（写 scope，需 okx_trade_enabled=1）
curl -s -X POST -H "$AUTH" http://127.0.0.1:8084/data/okx/sync/instruments

# 4) 拉 BTC-USDT 日线最新一页（只写已完结的 K 线）
curl -s -X POST -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"symbol":"BTC-USDT.CRYPTO","period":"1d"}' \
  http://127.0.0.1:8084/data/okx/sync/candles

# 5) 用统一契约读回来（与 A 股同一个接口；公共端点，不需要令牌）
curl -s "http://127.0.0.1:8084/market/bars?symbol=BTC-USDT.CRYPTO&period=1d&limit=10"
```

> 经网关调用时把路径换成 `/api/data/okx/...`（8080），网关会先验一次 JWT。
> `JWT_SECRET` 没注入时，0–4 全部返回 **503** 而不是放行。

第 5 步能按时间正序返回 `time/close/volume/amount`，就说明「采集 → bar → 统一读取」整条链路通了。

## 四、常见故障

| 现象 | 原因 | 处理 |
|---|---|---|
| 启动失败：`OKX 凭证加密密钥未配置` | 没设 `OKX_CRED_KEK` | `openssl rand -base64 32` 生成后注入（§十一） |
| 启动失败：`OKX_CRED_KEK 格式不合法` | 长度不是 32 字节 | 用上面命令重新生成；Hex 需 64 位 |
| 启动失败：`OKX 凭证解密失败（secretKey）` 出现在日志 | 换过 KEK，旧密文解不开 | 重新录入凭证（属预期行为） |
| 写操作 403：`未被授予 OKX 交易权限` | `sys_user.okx_trade_enabled=0` | 见 §二，改库后等 30 秒 |
| 写操作 400：`当前用户尚未配置可用的 OKX 模拟盘凭证` | 该用户没录 Key 或 `enabled=0` | 走 `/data/okx/account` |
| 写操作 400：`服务账号读取账户数据需要代理身份` | 服务令牌 + `okx.agent-owner-user-id=0` | 配置代理账户 ID，或改用用户令牌 |
| `ConnectException`，且 `nslookup www.okx.com` 得到 `169.254.x.x` | 域名被解析到黑洞地址，直连必然失败 | 配 `okx.proxy` 走代理 |
| 启动失败：`okx.proxy 缺少 host:port` | 代理写成 `127.0.0.1` 少了端口 | 写成 `127.0.0.1:7897` |
| 启动失败：`OKX 实盘已被 ADR-0009 决策 4 永久禁用` | 沿用了 `OKX_SIMULATED=false` | 删除该配置后重启 |
| `OKX 50113` | 签名错：query 没进签名串 / 时间戳与请求头不一致 / 用成了 apiKey 而不是 secretKey / passphrase 填错 | 对照 `OkxSigner` 注释；`OkxSignerTest` 有独立算出的基准值 |
| `OKX 50102` | 时间戳偏差超过 30 秒 | 校时（NTP） |
| `OKX 51001` | instId 不存在 | 先跑 `/sync/instruments` |
| `/market/bars` 返回 404 未知标的 | `instrument` 里还没有这条 | 先跑 `/sync/instruments` |
| 周期传 `1M` 报「不支持的周期」 | 刻意如此：OKX 的 `1M` 是月线，归一化会变成 1 分钟 | 用我们的词汇 `1mo` |
| 服务令牌 401 | `aud` 不是 `fuli-data`，或签名密钥不一致 | 检查 `fuli.agent.token-audience` 与 `okx.auth.service-token-audience` |
| 服务令牌 400 `invalid_scope` | 申请了 `orders:write` | 服务账号只允许 `orders:read` |
| OKX 端点返回 `{"array":true,"nodeType":"ARRAY",...}` | Boot 4 的 MVC 用 Jackson 3，而客户端用 Jackson 2，`JsonNode` 被当 Bean 序列化 | 已修：控制器边界用 `OkxJson.toPlain()` 转普通对象；自定义控制器返回 `JsonNode` 时同样要转 |

### 代理实测（2026-11-07，Clash Verge）

- 混合代理端口 = 系统代理地址 = `127.0.0.1:7897`，`OKX_PROXY` 填这个即可（带不带 `http://` 都认）。
- 该端口是 HTTP/SOCKS 混合口，Java 的 `ProxySelector` 走 HTTP CONNECT 正常。
- 实测经 7897：`www.okx.com` / `openapi.okx.com` / `eea.okx.com` 都 200，热起来约 0.3s；
  `app.okx.com` 超时、Google/GitHub 被规则挡——**不要拿 Google 通不通判断代理是否可用**。
- 环境里自带的 `http_proxy=127.0.0.1:7360` 不是 Clash；测本机服务端口要加 `--noproxy '*'`，
  否则 curl 会把 `127.0.0.1:8084` 也丢给 7360，报 `upstream connect failed`。

### 已验证的真实链路（2026-11-07）

| 步骤 | 结果 |
|---|---|
| `POST /sync/instruments` | **466** 个 USDT 现货对写入 `instrument`（`market=CRYPTO`、`tz=UTC`、`exchange=OKX`） |
| `POST /sync/all`（3 标的 × 1d） | **897** 根日线（每标的 299 根已完结） |
| `POST /backfill` BTC 30 天 | 99 根（与最新页重叠，upsert 幂等，行数不增） |
| `POST /sync/candles` BTC 1h | 299 根小时线 |
| 落库 ts | 日线全部 `00:00 UTC`（`SUM(ts % 86400 <> 0) = 0`），未完结那根被正确丢掉 |
| `/market/bars` | `meta` 正确（`CRYPTO / UTC / USDT / priceScale=1 / tickSize=0.1`） |
| `from/to` 窗口 | 按 `instrument.tz` 换算，正好命中 |
| `adjust=forward` | 与 `none` 相同（加密无因子，按设计降级） |

> 以上是**行情链路**的历史实测。凭证按用户存库之后（ADR-0010），
> 私有链路尚未做真实网络联调（缺模拟盘凭证 + 代理未起）。

## 五、交易上线步骤（务必按顺序）

**前置条件**：`JWT_SECRET`、`OKX_CRED_KEK`、`INTERNAL_KEY` 三件套已注入。
缺任何一个都不会静默放行：前者让 OKX 端点 503，第二个让服务起不来，第三个让写操作一律 403。

1. **创建模拟盘 Key**：OKX「交易 → 模拟交易 → 个人中心」创建**模拟盘专用** Key
   （与实盘 Key 不通用）。
2. **录入凭证**：`POST /data/okx/account`（前端「系统设置 → OKX 账户」）。
3. **只开读**：`/trade/config`、`/trade/balance`、`/trade/positions`，确认能读到 `acctLv`。
4. **授权交易**：`UPDATE auth_db.sys_user SET okx_trade_enabled = 1 WHERE id = <用户ID>;`
5. **开全局总闸**：`okx.trade-enabled=true`（或环境变量），重启 data-service。
6. **最小单**：`POST /data/okx/trade/order` 下 `sz=0.00001`，核对 `ordId`、`sCode` 与
   `x-simulated-trading` 头（抓包或日志确认）。
7. **观察期**：至少一周模拟盘，确认下单/撤单/查询幂等（`clOrdId` 复用不会重复下单）。

> **没有「切实盘」这一步。** ADR-0009 决策 4 已把实盘从系统里移除：实盘凭证不进 Java 服务，
> 模拟盘是代码常量。需要实盘成交时由人在 OKX 侧执行，系统最多产出下单建议单（§十）。

## 六、限频与容量

| 端点 | 限频 | 说明 |
|---|---|---|
| `/market/candles` | 40 次/2s（IP） | 单次最多 300 根 |
| `/market/history-candles` | 20 次/2s（IP） | 单次最多 100 根，往回翻页用 |
| `/trade/order` | 60 次/2s（UID） | 与 WS 共用额度 |
| `/account/balance` | 10 次/2s（UID） | 别放进高频轮询 |
| WS 订阅/连接 | 每 IP 每秒 480 次消息、每小时 480 次连接 | 断线重连已做指数退避（2s→60s） |

回补实现按 120ms/页 节流（≈16 页/秒 → 实际约 8 页/秒，留一半余量）。

## 七、WebSocket 接入地址

| 环境 | 公共频道 | 私有频道 | 业务频道 |
|---|---|---|---|
| 实盘 | `wss://ws.okx.com/ws/v5/public` | `wss://ws.okx.com/ws/v5/private` | `wss://ws.okx.com/ws/v5/business` |
| 模拟盘 | `wss://wspap.okx.com/ws/v5/public` | `wss://wspap.okx.com/ws/v5/private` | `wss://wspap.okx.com/ws/v5/business` |

- **8443 端口已下线迁 443**，地址里不要带端口；旧地址会收到 `event=notice, code=64010`。
- REST 侧模拟盘与实盘同 host、只差 `x-simulated-trading: 1`；**WS 侧是另一套域名**，
  且模拟盘 Key 与实盘 Key 不通用。
- ADR-0009 决策 4 把权限停在「模拟盘可写」，本期 WS 实际连的是 `wspap` 域。

## 八、WebSocket 客户端（默认关闭，已真机联调）

客户端在 `com.fuli.data.okx.ws`，用 JDK 17 内置 WebSocket，**不引入第三方库**。

```yaml
okx:
  ws:
    enabled: ${OKX_WS_ENABLED:false}
    url: ${OKX_WS_URL:wss://wspap.okx.com/ws/v5/private}
    reconnect-delay-ms: 2000
    max-reconnect-delay-ms: 60000
    heartbeat-seconds: 20
    buffer-capacity: 10000
    subscriptions: ${OKX_WS_SUBSCRIPTIONS:account,positions:SWAP,orders:ANY}
```

- **URL 是硬约束**：`OkxWsProperties.validate()` 只允许 `wss://wspap.okx.com/...`，填实盘域启动失败。
- **代理**：WS 与 REST 共用 `okx.proxy`。不走代理时 `wspap.okx.com` 在国内常被解析到黑洞地址，
  表现为握手超时。
- **公共 vs 私有**：
  - `/ws/v5/public` + `tickers:BTC-USDT`（或 `candle1m:*`、`books5:*`）——**不需要 Key**，
    订阅即可收到推送；启动时若在 public 端点上配了 `account/orders/positions`，
    `validate()` 会直接报错，避免交易所回一句含糊的 "channel doesn't exist"。
  - `/ws/v5/private` + `account,positions:SWAP,orders:ANY`——需要 `okx.ws-owner-user-id`
    指向已录 Key 的用户，登录 ACK 后才订阅。
- **订阅简写的第二段按频道解释**：`account`→`ccy`、`orders`/`positions`→`instType`、
  其余公共频道→`instId`。写错会被拒订。
- **登录签名与 REST 只差时间戳格式**：WS 用 **Unix 秒字符串**，
  `sign = Base64(HMAC-SHA256(ts + "GET" + "/users/self/verify", secretKey))`。
- **凭证从哪来**（ADR-0010）：WS 是长连接、没有当前 JWT，用 `okx.ws-owner-user-id` 指定账户身份，
  再查 `okx_account` 解密。未配置或该用户没录 Key 时**不登录私有频道**，只跑公共行情，
  日志会明确说明，不会换用别人的账户。
- **可观测**：`GET /data/okx/ws/status` 返回
  `enabled / status / mode(PUBLIC|PRIVATE) / subscriptions / droppedFrames / lastFrameAt /
  lastFrameAgeSeconds / lastFramePreview`。「连着但没数据」看后三个字段就够。
- **kill switch**：`POST /data/okx/ws/stop`（写 scope），或 `enabled=false` 重启。
- **背压**：帧先入 `OkxWsFrameBuffer`，满了丢最旧并计数（`droppedFrames()` 可观测）。
- **真机联调结果（2026-11-10）**：经 `127.0.0.1:7897` 连
  `wss://wspap.okx.com/ws/v5/public`，订阅 `tickers:BTC-USDT`，
  `/ws/status` 显示 `status=LOGGED_IN, mode=PUBLIC, lastFrameAgeSeconds=0`，
  帧内容为真实行情（`last=74758.1`），`droppedFrames=0`。
  私有频道的**登录成功**路径仍需模拟盘 Key；无 Key 时会话停在「仅公共频道」并打 WARN。

## 八·二、余额快照（WS account 频道）

- 表：`data_db.crypto_account`（V12）。每次 `account` 推送写
  「每个币种一行 + 一行 `ccy='TOTAL'`（OKX `totalEq`）」，
  唯一键 `(user_id, ccy, u_time)` 去重——交易所会定时重推同一份快照。
- 接口：
  - `GET /data/okx/snapshots/latest` —— 每个币种最新一条；
  - `GET /data/okx/snapshots?ccy=USDT&limit=100` —— 历史曲线。
- 保留 90 天，每日 03:45 分批删除（`OKX_ACCOUNT_CLEANUP_CRON`）。
- 返回字段里权益叫 **`equity`**：表列是 OKX 口径的 `eq`，但 `eq` 是 OGNL 运算符关键字，
  Java 字段不能叫 `eq`（否则 MyBatis-Plus 生成的 `<if test="eq != null">` 会抛
  "Malformed OGNL expression"），所以用 `@TableField("eq")` 映射、对外序列化成 `equity`。
- 页面：前端「OKX 账户」页（`/okx`）底部直接展示总权益、USDT 可用与逐币种明细。

## 八·三、审计分区（V13）

- `okx_audit_log` 改为 `PARTITION BY RANGE (TO_DAYS(create_time))`，
  分区名 `pYYYYMM`，主键变为 `(id, create_time)`（分区列必须进主键）。
- 每日 03:10 执行 `OkxAuditPartitionJob`：
  补齐未来 3 个月分区（`REORGANIZE pmax`）+ `DROP PARTITION` 丢弃 2 个月前的分区。
- 未分区（回滚到 V11 形态）时，03:30 的 `OkxAuditRetentionJob` 自动接管，分批 DELETE。
- 代价：分区只能按月丢，实际保留是「当月 + 前 2 个月」，可能多留最多一个月尾巴；
  换来的是清理从「分钟级锁」变成「毫秒级元数据操作」。

## 九、下单链路与对账

`POST /data/okx/trade/order` 的固定顺序是：

```
解析当前用户凭证 → createPending(写前日志, state=pending) → POST /api/v5/trade/order → markSubmitted
```

- 凭证在最前面解析：没有可用凭证时**连写前日志都不留**，避免库里堆一堆永远不会发出的 pending。
- `clOrdId` 可不传，服务端生成 32 位十六进制幂等键；**同 clOrdId 命中未完成单时直接返回，
  不再发网络请求**（回包 `sMsg` 标注 skipped）。
- 入口 `sCode != 0` 当场定格 `rejected`——被拒的单不会有 WS 推送。
- 后续状态由 WS `orders` 频道推进，终态冻结、更旧的 `uTime` 推送忽略；
  `positions` 频道落 `crypto_position`，定时重推的同一份快照不重复写库。
- **`account` 频道不落库**：V10 没建账户余额快照表，只记 debug。
- 断网恢复后用 `OkxOrderReconciler.reconcile(userId, instType, limit)` 拉 `orders-pending`
  与本地未完成单比对，输出「交易所独有 / 本地独有 / 孤儿单」。**只报告、不自动修数据**。
- `crypto_order.state` 有 CHECK 约束，非法状态写不进去（已在 `data_db` 实测）。

## 十、MCP、七模块、Agent 与建议单

### MCP（Java 侧，不装 npm 包）

- 依赖：根 `pom.xml` 的 `spring-ai-bom 2.0.1` + data-service 的 `spring-ai-starter-mcp-server-webmvc`。
- 端点：`/okx/mcp`（若版本回落默认 `/mcp`，两条路径都在 `OkxAuthPaths` 保护内），**无令牌一律 401**。
- 注册 **9 个只读工具**：`market_get_ticker` / `market_get_candles` / `market_get_orderbook` /
  `market_get_funding_rate` / `market_get_open_interest` / `market_get_mark_price` /
  `account_get_balance` / `account_get_positions` / `trade_get_pending_orders`。
- **不注册写工具**：MCP 调用不经过 `OkxController`，会绕过写 scope 与写前日志。
- 私有三个工具的身份：`okx.mcp-owner-user-id`（0 = 返回 `{"ok":false,"error":"…okx.mcp-owner-user-id…"}`）。
  不要指望它「默认用某个账户」——那正是 ADR-0010 要消除的东西。

### 七模块能力（受控端点表）

```bash
curl -s --noproxy '*' -H "$AUTH" http://127.0.0.1:8084/data/okx/capability/modules

curl -s --noproxy '*' -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"instId":"BTC-USDT"}' http://127.0.0.1:8084/data/okx/capability/market_ticker
```

所有写能力统一经过 `createPending → 发网络 → markSubmitted`，且只对「已授权 + 有凭证」的用户开放。

### Agent：独立服务身份，只读

```bash
# 1) 换服务令牌（auth-service）
curl -s -X POST -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=client_credentials&client_id=ai-service&client_secret=<AI_SERVICE_CLIENT_SECRET>&scope=orders:read' \
  http://127.0.0.1:8081/auth/oauth/token
# → {"access_token":"...","token_type":"Bearer","expires_in":900,"scope":"orders:read"}

# 2) 用服务令牌读受保护端点（data-service 要求 aud=fuli-data + orders:read）
curl -s -H "Authorization: Bearer <access_token>" \
  http://127.0.0.1:8084/data/okx/trade/orders-pending
```

- 服务令牌 **只读**：写端点对 SERVICE 主体是硬编码 403，给 scope 也没用。
- ai-service 每 10 分钟刷新一次（有效期 15 分钟），**从不转发用户令牌**。
- 服务账号没有 OKX 账户：读私有数据要靠 `okx.agent-owner-user-id` 指定代理账户，
  未配置时返回 400 并说明原因。
- 配置：auth-service 的 `fuli.agent.clients.ai-service.secret` 与 ai-service 的
  `fuli.ai.client-secret` 必须一致（都读 `AI_SERVICE_CLIENT_SECRET`）。

### 建议单：Agent 唯一的「写」

```bash
# ai-service（需要用户 JWT，服务自己验签）
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"symbol":"BTC-USDT.CRYPTO","side":"buy","ordType":"limit","sz":"0.01","px":"30000","reason":"回踩均线"}' \
  http://127.0.0.1:8085/ai/suggest-order
# 或经网关 /api/ai/suggest-order
```

- 只写 `data_db.okx_order_suggestion`，**不调用任何下单接口**：
  data-service 侧的 `OkxInternalSuggestionController` 只依赖 `OkxSuggestionService`，
  连 `OkxTradeService` 都没注入——结构上不可能顺手下单。
- 查看自己的建议：`GET /data/okx/suggestions?limit=20`。
- `status` 只是人工处置结果（new/accepted/rejected/expired），**不代表已下单**。
- 内部写入走 `/internal/okx/suggestion` + `X-Internal-Key`（网关会拒绝任何含 `internal`
  段的路径，外部到不了）。

## 十一、多账号凭证（ADR-0010）

### KEK 怎么生成

```bash
openssl rand -base64 32          # 推荐
openssl rand -hex 32             # 也行（64 位 Hex）
```

三种写法都接受：Base64(32B) / 64 位 Hex / 恰好 32 字节 ASCII。
**KEK 不进数据库、不进代码、不进日志**；丢失或更换后，旧密文全部解不开（重新录入即可）。

### 存储格式

```
secret_key_enc = iv(12) || ciphertext || tag(16)     -- AES-256-GCM
AAD            = "okx-account:" + userId + ":" + 字段名
```

- 每行每字段**独立随机 IV**：同一明文两次加密密文不同；
- AAD 绑定 userId：把 A 的密文行搬到 B 名下解密失败；
- `key_version` 记录 KEK 版本，将来轮换可新旧并存（当前只支持 1）。

### 一个用户多个账户

`okx_account` 的唯一键是 `(user_id, label)`，可以存多套（模拟盘主账户 / 子账户 / 测试账户）。
**但本期交易一律使用 `enabled=1` 的第一条**：`label` 是为将来「按账户切换」预留的维度，
接口目前不接受 `account` 参数。要切换就把目标行置为 `enabled=1`、其余置 0。

### 审计表怎么查

```sql
-- 某用户最近的操作
SELECT create_time, action, scope, outcome, inst_id, ord_id, detail
FROM data_db.okx_audit_log WHERE user_id = 1 ORDER BY id DESC LIMIT 50;

-- 某笔订单的全部痕迹
SELECT * FROM data_db.okx_audit_log WHERE ord_id = '<ordId>' ORDER BY id;

-- 被拒绝的写操作（谁在试）
SELECT user_id, action, COUNT(*) FROM data_db.okx_audit_log
WHERE outcome = 'DENIED' AND create_time > NOW() - INTERVAL 7 DAY GROUP BY user_id, action;
```

保留 2 个月（每日 03:30 分批清理）。长期归档与 RANGE 分区切换方案见
`sql/migrations/V11__okx_multiuser_credentials.sql` 末尾。
