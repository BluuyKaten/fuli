# fuli（复利）

股票复盘系统 - 帮助投资者系统化复盘、总结经验、提升交易水平。

## 功能

- 📈 K 线图（蜡烛图 + MA/MACD/KDJ）
- 💰 交易记录与持仓管理
- 📊 盈亏分析、资产曲线、月度收益
- 🔄 Tushare 行情数据同步
- 🎯 历史交易练习：逐根揭晓、独立模拟账户、次日开盘成交与结束复盘
- 📝 **交易复盘笔记（Trade Journal）**：记录买卖逻辑、情绪打分（1-5）、策略分类、经验反思与月度复盘足迹
- ⚠️ **风控价格预警（Risk Alerts）**：支持价格止盈、价格止损、涨跌幅超限实时监控与仪表盘警示
- 🏆 **多维绩效分析（Performance Stats）**：胜率、盈亏比、获利因子、连胜连亏极值、持仓周期分布、收益率区间与个股龙虎榜
- 🔐 **令牌黑名单**：登出后 JWT 立即失效（Redis）
- 🔍 **全链路请求追踪**：traceId 贯穿网关与各服务日志
- 🛡️ **服务熔断与优雅降级**：Resilience4j 熔断 + 超时，下游故障不拖垮调用方
- ⚡ **分析缓存**：Caffeine 短 TTL 缓存聚合结果
- 🪙 **OKX 行情与模拟盘交易**：V5 REST 行情同步、七模块受控端点、WebSocket 私有/公共频道、
  独立订单表 + 写前日志（先落库再发网络）、`clOrdId` 幂等与 `tradeId` 对账
- 🔐 **多用户 OKX 凭证隔离**：每人在「系统设置」录入自己的模拟盘 Key，AES-256-GCM 密文存库、
  KEK 只在环境变量；服务按 JWT 的 userId 解密，代码里没有全局凭证
- 🧾 **OKX 审计**：`[OKX-AUDIT]` 日志 + `okx_audit_log` 双写（保留 2 个月），按用户/标的/订单号可查
- 🤖 **Agent 只读接入**：`ai-service` 用 client_credentials 服务令牌调受保护端点，
  只能读、不能下单；下单建议落到 `okx_order_suggestion` 由人工决定
- 🧰 **MCP 工具层**：Spring AI 暴露 9 个只读工具（零写工具），端点纳入同一套 JWT 鉴权
- 🖥️ **OKX 账户设置页**（`/okx`）：录入/更新自己的模拟盘 Key（只回掩码）、查看凭证与总闸状态、
  余额快照（WS `account` 频道落库）与 WS 连接状态
- 🚦 **网关全局限流**：令牌桶限流，已认证按用户、登录/注册按 IP（防爆破），429 + `Retry-After`，fail-open
- 🎯 **加密回测（阶段 2）**：Binance Vision / Gate 行情落 `bar`，Python 确定性回测引擎
  （次根开盘撮合、滑点/费率参数化、净值/回撤/胜率/获利因子），`Strategy` 接口回测与实盘共用

## 技术栈

- **后端**: Spring Boot 4.1 + Spring Cloud 2025.1 + MyBatis-Plus 3.5 + Resilience4j + Spring AI 2.0（MCP Server）
- **前端**: Vue 3 + Vite + TypeScript + Ant Design Vue + ECharts（manualChunks 拆包）
- **数据库**: MySQL 8.0+
- **缓存**: Redis（令牌黑名单）+ Caffeine（分析缓存）

## 启动指南

### 需要启动什么

完整使用登录、交易、K 线、数据同步、盈亏分析和历史练习时，按下表启动。`common-api` 是公共依赖，只需编译安装，没有独立进程。

| 服务 | 用途 | 本地直连模式 `local` | Nacos 模式 | 默认端口 |
| --- | --- | --- | --- | --- |
| MySQL | 用户、交易、行情和练习数据存储 | 必须 | 必须 | `3306` |
| Nacos | 服务注册、发现和配置中心 | 无需启动 | 必须先启动 | `8848`、客户端 gRPC `9848` |
| Redis | 令牌黑名单（登出失效）；查询失败 fail-open | 可选（建议） | 可选 | `6379` |
| `auth-service` | 登录、注册、用户资料和现金账户 | 必须 | 必须 | `8081` |
| `data-service` | 股票基础信息、历史行情查询与 Tushare 同步 | 必须 | 必须 | `8084` |
| `trade-service` | 交易、持仓、K 线、自选股与历史练习 | 必须 | 必须 | `8082` |
| `analysis-service` | 盈亏统计和资产分析 | 必须 | 必须 | `8083` |
| `ai-service` | Agent 编排（规则版）+ OKX 只读服务令牌；用 OKX / MCP 时需要 | 按需 | 按需 | `8085` |
| `gateway-service` | 前端 API 统一入口 | 必须 | 必须 | `8080` |
| 前端 `frontend` | 浏览器页面 | 必须 | 必须 | `3000` |
| AKTools / AKShare | 独立的 Python 行情 HTTP 服务 | 按需启动 | 按需启动 | 本文指定 `8090` |

- **本地直连模式（推荐先跑通）**：MySQL → auth、data → trade → analysis → gateway → 前端。所有后端统一启用 `local`，网关与 Feign 直连本机，不依赖 Nacos。
- **Nacos 模式**：MySQL、Nacos → auth、data → trade → analysis → gateway → 前端。所有后端不启用 `local`，使用 Nacos 服务发现。
- **AKTools** 与 Java 服务分开启动。当前仓库的 auth 没有调用 AKTools，行情同步使用 Tushare；需要单独使用 AKShare HTTP 接口时才启动它。

### 1. 准备环境（首次）

- JDK 17+（推荐 21）、Maven 3.8+。项目编译目标为 Java 17，见 `pom.xml` 的 `java.version`。
- Node.js 建议使用 22 或 24 LTS（当前 Vitest 4 不支持 Node.js 18）。
- MySQL 8.0+。
- 使用 Compose 启动依赖时，需要 Docker Desktop / Docker Engine 和 Docker Compose；已有本机服务可直接使用。
- 使用 AKTools 时，需要 Python 和 pip；当前机器已用 Python 3.11 验证启动。

下面命令以 **Windows PowerShell** 为例，未特别注明时均在项目根目录执行；每个后端和前端各开一个终端并保持运行。先检查工具版本：

```powershell
java -version
mvn -v
node -v
npm -v
```

`mvn -v` 显示的 Java 应为 JDK 17 或更新版本；若与 `java -version` 不一致，检查 `JAVA_HOME` 和 `PATH`。只在使用相应可选工具时再检查 `docker compose version`、`python --version`、`python -m pip --version`。

### 2. 启动基础设施

**MySQL（必须，已有服务与 Compose 二选一）**：已有本机 MySQL 时启动该服务，默认连接 `127.0.0.1:3306`、用户 `root`、密码 `123456`。Windows 可用 `Get-Service *mysql*` 查看实际服务名，并从“服务”管理器启动。没有本机 MySQL 时，在项目根目录执行：

```powershell
docker compose up -d mysql
docker compose ps mysql
```

Compose 首次创建 MySQL 数据卷时自动初始化 `auth_db`、`trade_db`、`data_db`，包含图表和练习表，无需再执行下一节的手工初始化。已有数据卷不会自动补表。

**Nacos（仅 Nacos 模式必须）**：已手动启动的实例可继续使用。使用本机 Nacos 2.x 时，在其安装目录的 `bin` 下打开单独终端：

```powershell
.\startup.cmd -m standalone
```

也可使用仓库提供的 Nacos 2.4.0 容器，二选一，避免端口冲突：

```powershell
docker compose up -d nacos
docker compose ps nacos
```

默认服务地址为 `127.0.0.1:8848`，Nacos 2.x 控制台为 http://127.0.0.1:8848/nacos/ 。主端口和客户端 gRPC 端口都必须可访问，控制台能打开不代表 gRPC 正常：

```powershell
Test-NetConnection 127.0.0.1 -Port 8848
Test-NetConnection 127.0.0.1 -Port 9848
```

自定义地址可设置 `NACOS_SERVER`；客户端 gRPC 端口通常为主端口加 1000，也需放行。远程 Nacos 若启用了认证，还需按实际服务端配置客户端凭据。

**Redis（可选）**：当前核心流程未调用 Redis，可暂不启动。需要时使用现有 Redis，或启动 Compose 服务：

```powershell
docker compose up -d redis
docker compose exec redis redis-cli ping
```

预期返回 `PONG`，默认地址为 `127.0.0.1:6379`。Compose 仅提供基础设施，不会自动启动 Java 后端、前端或 AKTools。

### 3. 初始化数据库、编译依赖（首次或升级时）

**使用本机空数据库时**，从项目根目录进入 MySQL 客户端：

```powershell
mysql -u root -p
```

然后在 `mysql>` 提示符中按顺序执行（不要在 PowerShell 中直接执行 `source`）：

```sql
source sql/auth_db.sql;
source sql/trade_db.sql;
source sql/data_db.sql;
source sql/migrations/V2__lightweight_charts.sql;
exit;
```

这些初始化脚本含 `DROP TABLE`，只用于空库，**日常启动不要重跑**。已有库先备份并核对缺表；只新增历史练习表可执行 `source sql/practice.sql;`，该脚本不会删除原表。

在项目根目录编译并安装所有模块，确保单模块启动能找到最新的 `common-api`：

```powershell
mvn clean install -DskipTests
```

第一次启动前端时安装依赖：

```powershell
Set-Location frontend
npm ci
```

依赖未变更时，日常启动无需重新安装。后端公共代码变更后应重新运行 Maven 安装命令，再启动各服务。

### 4A. 启动后端：本地直连模式

在 **五个独立终端** 中分别执行下表命令，工作目录都为项目根目录。建议按表格顺序启动，看到每个服务的 `Started ...Application` 后继续。

| 顺序 | 服务 | 启动命令 | IDE 启动类 |
| --- | --- | --- | --- |
| 1 | auth（`8081`） | `mvn -pl auth-service spring-boot:run "-Dspring-boot.run.profiles=local"` | `AuthApplication` |
| 2 | data（`8084`） | `mvn -pl data-service spring-boot:run "-Dspring-boot.run.profiles=local"` | `DataApplication` |
| 3 | trade（`8082`） | `mvn -pl trade-service spring-boot:run "-Dspring-boot.run.profiles=local"` | `TradeApplication` |
| 4 | analysis（`8083`） | `mvn -pl analysis-service spring-boot:run "-Dspring-boot.run.profiles=local"` | `AnalysisApplication` |
| 5 | ai（`8085`，用 OKX 时） | `mvn -pl ai-service spring-boot:run "-Dspring-boot.run.profiles=local"` | `AiServiceApplication` |
| 6 | gateway（`8080`） | `mvn -pl gateway-service spring-boot:run "-Dspring-boot.run.profiles=local"` | `GatewayApplication` |

> 仪表盘会同时调 `/api/trade/**` 与 `/api/analysis/**`：**trade-service（8082）和
> analysis-service（8083）缺任一个，前端就会看到 503**（网关找不到可用实例时的固定回包）。

IDE 中每个启动配置的 **Active profiles** 都填 `local`，或在 **Program arguments** 中填写 `--spring.profiles.active=local`。日志应出现 `The following 1 profile is active: "local"`。本地配置包含各服务一致的开发密钥，仅用于本机开发。

如果数据库账号密码不同，在启动相关后端的同一个终端中先设置：

```powershell
$env:DB_USERNAME = '你的数据库用户名'
$env:DB_PASSWORD = '你的数据库密码'
```

需要同步新行情时，在启动 **data-service** 的终端中设置 Tushare Token，再执行上表中的 data 启动命令；trade 的 Tushare 数据源需要时也设置同一变量：

```powershell
$env:TUSHARE_TOKEN = Read-Host '输入你的 Tushare Token'
```

未配置 Token 不影响登录和已有数据查询，但无法使用需要凭据的 Tushare 同步。历史练习和 K 线需要先有对应股票、区间的行情数据。

### 4B. 启动后端：Nacos 模式

与 4A 二选一，不要在相同端口重复运行两套后端。先启动 MySQL 和 Nacos；所有后端不要启用 `local`，并准备一致的 JWT、内部服务密钥。

1. 若使用初始化脚本创建的 `admin` 账号，先以 `local` 启动 auth，登录后调用 `PUT /auth/password` 修改默认密码（请求头带 `Authorization: Bearer <登录返回的 token>`，JSON 请求体为 `{"oldPassword":"原密码","newPassword":"新的至少6位密码"}`）；当前账户页面没有改密入口。非 local 模式会拒绝带有已知默认管理员密码的数据库启动。
2. 生成并保存一组随机密钥（JWT 至少 32 字符、内部密钥至少 16 字符），每个服务使用同一组；安装了 OpenSSL 时可分别运行 `openssl rand -base64 64` 和 `openssl rand -base64 24`。不要每启动一个服务就重新生成。
3. 在每个后端终端中设置下面三个变量，再执行启动命令。`jwt.secret` 与 `fuli.internal-key`
   已在各服务 `application.yml` 里显式映射到 `JWT_SECRET` / `INTERNAL_KEY`
   （不再需要用 `SPRING_APPLICATION_JSON` 转一道）：

```powershell
$env:NACOS_SERVER = '127.0.0.1:8848'
$env:JWT_SECRET = Read-Host '输入已保存的共用 JWT 密钥'
$env:INTERNAL_KEY = Read-Host '输入已保存的共用内部服务密钥'
```

数据库账号不同或需要同步行情时，还需设置 4A 中的 `DB_USERNAME`、`DB_PASSWORD`、`TUSHARE_TOKEN`。

> **`.env` 现在会被自动加载**：各服务的 `application.yml` 里加了
> `spring.config.import: optional:file:./.env[.properties]`（`./` 与 `../` 都试一遍），
> 所以 `mvn spring-boot:run` 会直接读仓库根的 `.env`，文件不存在时静默跳过。
> 优先级：**IDEA / 系统环境变量 > `.env` > yml 默认值**。
> 两个注意点：① `.env` 必须是 **LF 换行**（CRLF 会让每个值多一个 `\r`，
> `OKX_CRED_KEK` 会变成 33 字节而启动失败）；② 如果不同服务分别用 IDEA 和 `mvn` 启动，
> 两边读到的 `JWT_SECRET` / `INTERNAL_KEY` 必须完全一致，否则会出现「令牌验不过」。

在五个独立终端中按顺序执行，每个终端只运行一条启动命令：

```powershell
# 终端 1
mvn -pl auth-service spring-boot:run
# 终端 2
mvn -pl data-service spring-boot:run
# 终端 3
mvn -pl trade-service spring-boot:run
# 终端 4
mvn -pl analysis-service spring-boot:run
# 终端 5（用 OKX / MCP 时）
mvn -pl ai-service spring-boot:run
# 终端 6
mvn -pl gateway-service spring-boot:run
```

确认没有遗留的 `SPRING_PROFILES_ACTIVE=local` 或 IDE local 启动参数。默认读取 Nacos `DEFAULT_GROUP` 中各自的 `auth-service.yaml`、`data-service.yaml`、`trade-service.yaml`、`analysis-service.yaml`、`ai-service.yaml`、`gateway-service.yaml`；导入为可选，未发布远程配置时保留仓库内配置，但服务注册与发现仍依赖 Nacos。可在注册服务列表中检查这六个服务是否正常上线。

从 Nacos 模式切回 4A 时要注意：4A 的 local profile 用 `${JWT_SECRET:开发值}` 兜底，
如果终端里还留着 4B 的 `JWT_SECRET` / `INTERNAL_KEY`，会优先用环境变量那一套。
两套密钥混用会出现「令牌验不过」，保持同一套即可（推荐统一交给 `.env`）。

### 5. 启动前端、登录并准备行情

在第六个终端从项目根目录进入前端目录：

```powershell
Set-Location frontend
npm run dev
```

浏览器打开 http://localhost:3000 。前端 `/api` 请求转发到本机 `8080` 网关，因此只启动前端无法登录或读取业务数据。没有账号时可在登录页点击“没有账号？去注册”，注册后登录。

首次使用行情功能时，先在“数据同步”页面同步股票基础信息和所需区间的日线，再使用 K 线或 `/practice` 历史交易练习。Tushare Token 的权限需覆盖所选同步接口。

### 6. AKTools / AKShare HTTP 服务（按需）

在单独终端安装依赖（首次）：

```powershell
python -m pip install aktools akshare
```

Windows 推荐使用已实测的直接启动命令，保持终端运行：

```powershell
python -m uvicorn aktools.main:app --host 127.0.0.1 --port 8090
```

也可使用 AKTools 启动器：

```powershell
python -m aktools --host 127.0.0.1 --port 8090
```

AKTools 默认使用 `8080`，与网关冲突，因此本文统一指定 `8090`。看到 `Uvicorn running on http://127.0.0.1:8090` 后，访问 http://127.0.0.1:8090/docs 查看接口，http://127.0.0.1:8090/version 查看版本信息。安装和默认启动方式见[官方文档](https://aktools.akfamily.xyz/)。

AKTools 0.0.91 的启动器未正确处理带空格的 Python 路径，出现 `'C:\Program' 不是内部或外部命令` 时，使用上面的 `python -m uvicorn ...` 命令即可绕过。仅打印“点击打开 HTTP API 主页”后就退出，表示服务尚未成功启动。`RequestsDependencyWarning` 是 Python HTTP 依赖版本兼容警告，可用 `python -m pip check` 检查；它与该路径错误、Nacos 连接失败分别处理。

### 7. 确认启动成功、常见问题与停止方式

完整功能启动后应监听 `3306`、`8080`～`8085`、`3000`；Nacos 模式另有 `8848/9848`。可选的 Redis、AKTools 分别使用 `6379`、`8090`。Windows 可检查：

```powershell
Get-NetTCPConnection -State Listen |
    Where-Object { $_.LocalPort -in 3306,6379,8848,9848,8080,8081,8082,8083,8084,8085,3000,8090 } |
    Select-Object LocalAddress,LocalPort,OwningProcess
```

- Java 服务应出现各自的 `Started ...Application`；不能只用 Maven 的 `BUILD SUCCESS` 判断应用已启动。
- 未登录访问 `http://localhost:8081/auth/profile` 返回 HTTP `401` 是预期鉴权行为，可确认 auth 已提供 HTTP 服务；数据库与完整业务仍需通过登录和页面操作验证。
- 出现 `Server check fail ... 127.0.0.1, port 9848`、`UNAVAILABLE: io exception`、`Connection refused`：检查 Nacos `8848/9848`，或确认所有后端都已启用 `local`。启动 AKTools 无法解决 Nacos 错误。
- 出现 `InternalServiceFilter.class ... does not exist` 或其他 `common-api` 类缺失：在项目根目录执行 `mvn clean install -DskipTests`，IDE 重新加载 Maven，再重启后端。
- 前端 API 报连接失败或网关 `503`：检查网关、对应后端是否运行，以及是否混用了 local 与 Nacos 模式；本地端口改动后，也需同步网关路由、Feign 地址和前端代理。
- 端口已占用：先用上面的命令确认已有服务，不要重复启动；AKTools 尤其不要占用网关的 `8080`。

停止时，先在前端、gateway、analysis、trade、data、auth、AKTools 各自的终端按 `Ctrl+C`，再停止本次启动的基础设施。Compose 服务可按需执行：

```powershell
docker compose stop mysql redis nacos
```

只停止需要停止的服务即可；手工启动的 Nacos 在其 `bin` 目录执行 `.\shutdown.cmd`，本机 MySQL 则从“服务”管理器停止。下次日常启动只需依次启动基础设施、五个后端和前端；无需重新建库、安装依赖或重新生成密钥。

## OKX 模拟盘接入（可选）

系统只接 **OKX 模拟盘**：实盘凭证不进 Java 服务，`OkxConfig.isSimulated()` 是常量，
所有私有请求恒定携带 `x-simulated-trading: 1`，提币永不开启。需要实盘成交时由人在 OKX 侧操作，
系统最多产出「下单建议单」。

### 需要的环境变量

| 变量 | 必填 | 说明 |
| --- | --- | --- |
| `JWT_SECRET` | 是 | 与网关/auth 一致，OKX 端点靠它验签 |
| `OKX_CRED_KEK` | **是** | 32 字节凭证加密主密钥（`openssl rand -base64 32`）；缺失时 data-service **启动失败** |
| `INTERNAL_KEY` | 是 | 服务间共享密钥：查用户交易权限、写建议单 |
| `OKX_PROXY` | 国内必需 | 如 `127.0.0.1:7897`（Clash 混合口，带不带 `http://` 都认） |
| `AI_SERVICE_CLIENT_SECRET` | 用 Agent 时 | auth-service 与 ai-service 两侧同值 |
| `OKX_AGENT_OWNER_USER_ID` / `OKX_WS_OWNER_USER_ID` / `OKX_MCP_OWNER_USER_ID` | 可选 | 服务账号/WS/MCP 用哪个 userId 的凭证；0 或不配 = 明确报错，不会借用别人账户 |
| `OKX_TRADE_ENABLED` | 下单时 | `true` 才开全局总闸（默认 false） |
| `OKX_WS_ENABLED` | 看实时行情时 | `true` 才连 WS（默认 false） |
| `OKX_WS_URL` | 同上 | 公共 `wss://wspap.okx.com/ws/v5/public`；私有 `/ws/v5/private` |
| `OKX_WS_SUBSCRIPTIONS` | 同上 | 逗号分隔，如 `tickers:BTC-USDT` 或 `account,positions:SWAP,orders:ANY` |

> **OKX 的 API Key / Secret / Passphrase 不放环境变量**。它们在「系统设置 → OKX 账户」里
> 按用户录入，AES-256-GCM 密文存 `data_db.okx_account`；旧的 `OKX_API_KEY` 等变量已删除。

### 四道闸（缺一不可）

1. 有效 JWT；
2. `sys_user.okx_trade_enabled = 1`（用户级，默认 0）；
3. `okx.trade-enabled = true`（全局总闸，默认 false）；
4. 该用户已录入 `enabled=1` 的模拟盘凭证。

读接口只需要第 1 条；同步/下单/撤单要 1+2+3+4。

### 上手步骤

1. **登录拿令牌**（`admin` 是初始化脚本账号，密码见 `sql/auth_db.sql` 注释）：

```bash
curl -s -X POST -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' http://127.0.0.1:8080/api/auth/login
```

   页面入口：登录后左侧「**OKX 账户**」（`http://localhost:3000/okx`），
   可录入/更新 Key、看凭证与总闸状态、看余额快照与 WS 状态。

2. **录入模拟盘 Key**（只需有效令牌，不需要交易权限）：

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"label":"模拟盘-主账户","apiKey":"...","secretKey":"...","passphrase":"..."}' \
  http://127.0.0.1:8080/api/data/okx/account
```

响应只回 `apiKeyMasked`（如 `abcd****wxyz`），永远不会回 secret / passphrase。

3. **授权交易**（一次 SQL，默认拒绝所有人）：

```sql
UPDATE auth_db.sys_user SET okx_trade_enabled = 1 WHERE id = 3;   -- 换成要交易的账号 id
```

生效有 30 秒缓存；要立即阻断就撤销令牌。

4. **确认链路**：

```bash
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/api/data/okx/status
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"instId":"BTC-USDT"}' http://127.0.0.1:8080/api/data/okx/capability/market_ticker
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/api/data/okx/trade/balance
```

5. **开总闸后下最小单**：在 data-service 的环境变量里加 `OKX_TRADE_ENABLED=true` 并重启，
再用 `POST /api/data/okx/trade/order`（`sz` 从小开始，如 `0.00001`）。

### 常见返回

| 返回 | 含义 | 处理 |
| --- | --- | --- |
| `401` | 无令牌/令牌过期 | 重新登录 |
| `403` + `未被授予 OKX 交易权限` | `okx_trade_enabled=0` | 执行第 3 步 SQL，等 30 秒 |
| `400` + `OKX 下单已关闭` | 全局总闸没开 | 设 `OKX_TRADE_ENABLED=true` 并重启 data-service |
| `400` + `尚未配置可用的 OKX 模拟盘凭证` | 该用户没录 Key | 走第 2 步 |
| `400` + `服务账号读取账户数据需要代理身份` | Agent 读私有数据但没配 `OKX_AGENT_OWNER_USER_ID` | 配上并重启 data-service |

### 实时行情与余额快照

```bash
# WS 是否连上、最近有没有收到帧（真机联调用它，比翻日志可靠）
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/api/data/okx/ws/status

# 最新余额（每个币种一条 + TOTAL），数据来源是 WS account 频道
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/api/data/okx/snapshots/latest
```

- 公共行情（`tickers`/`candle*`/`books*`）**不需要 Key**：把 `OKX_WS_URL` 指到 `/ws/v5/public`、
  订阅写成 `tickers:BTC-USDT` 就能看到实时推送。
- 私有频道（`account`/`positions`/`orders`）需要模拟盘 Key，并且 `OKX_WS_OWNER_USER_ID`
  指向该 Key 所属用户；登录成功后才会有余额快照。
- `okx_audit_log` 已改为**按月 RANGE 分区**，清理用 `DROP PARTITION`（秒级），
  每日 03:10 自动补齐未来 3 个月分区并丢弃 2 个月前的分区。

详细配置、限频、WS 与故障排查见 [OKX 接入手册](docs/okx-接入手册.md)，
前端字段契约见 [OKX 前端契约](docs/okx-前端契约.md)。

## 验证

```bash
# 项目根目录（使用 JDK 17+）
mvn -B test

# frontend 目录
npm test
npm run build
```

后端回归使用 H2 或隔离的 HTTP/过滤器测试，不需要本机 MySQL、Nacos 或行情凭据。前端单测使用真实 Vue 模板和模拟图表 API。练习页另有真实浏览器回归：在 `frontend` 下先构建，再运行 `node tests/practice-browser-smoke.mjs`，使用已安装的 Chrome/Edge（也可用 `CHROME_PATH` 指定路径）、真实生产包和固定接口数据验证标注、委托、推进、恢复、复盘及手机宽度；截图与结果保存到忽略目录 `.browser-smoke`。此检查与后端 HTTP 测试分开运行，尚未覆盖 MySQL 与全部微服务的真实行情联调。

历史行情录入的卖出价格按交易当天实际最低价、最高价校验；缺少当天完整行情时需先同步。

买入提交时会同步扣减可用资金，余额不足直接拒单（不写入交易记录）；卖出按 A 股 T+1 校验可卖数量，当日买入的部分需下一交易日才可卖出。

## 历史交易练习

已有数据库先在 MySQL 客户端执行 `source sql/practice.sql`，仅新增练习表，不删除原交易数据；新建库执行上面的初始化脚本已包含此表。

1. 在“数据同步”中同步所选 A 股的未复权日线（代码如 `600519.SH`），覆盖起点前的背景和整个练习区间。
2. 打开侧栏“历史交易练习”（`/practice`），输入股票、起止日期和初始资金（默认 10 万元）。起点是最初可见行情的截止日，之前最多 120 根日线作为分析背景。
3. 在当前决策日提交固定股数的买单或卖单；点“揭晓下一日”后，才按新一天的开盘价尝试成交。100 股整数倍，资金或可卖股数不足则整单拒绝；推进前可撤销或替换待成交单。
4. 可绘制练习专属画线、手工买卖点，浏览已揭晓历史；已成交交易不能撤回。重新打开练习可恢复进度，也可以另建练习重新开始。
5. 到达终点自动结束，也可提前结束。复盘展示完整所选走势、逐笔成交、账户/买入持有基准收益、费用和收盘最大回撤；指标冻结到实际结束日，剩余持仓只估值、不强制卖出。

练习只使用创建时冻结的服务端行情快照，与正式账户和普通图表标注独立。仅支持 A 股日线，采用固定训练费率（双向佣金万三、最低 5 元，卖出印花税万五）与简化 T+1 撮合；无记录日跳过，无量或一字线不成交，不模拟历史费率、分红送转、滑点或涨跌停排队。数据缺失或价格口径断层时需同步并核查数据后重试。详见[已确认规则](docs/superpowers/specs/2026-10-24-repairs-and-blind-replay-design.md)与[接口契约](docs/superpowers/specs/2026-10-24-practice-api.md)。

## 项目结构

```
fuli/
├── common-api/          # 公共模块
├── gateway-service/     # API 网关 (8080)
├── auth-service/        # 认证服务 (8081)
├── trade-service/       # 交易服务 (8082)
├── analysis-service/    # 分析服务 (8083)
├── data-service/        # 数据同步 + OKX 接入 (8084)
├── ai-service/          # Agent 编排 (8085)
├── frontend/            # 前端 (3000)
└── sql/                 # 数据库脚本
```

## 文档

- [修复回归记录（2026-10-24）](doc/Repair-Verification-2026-10-24.md) - 已修复行为、验证结果和剩余范围
- [开发进度](docs/开发进度.md) - 详细功能列表与启动方式
- [项目总结](doc/Project.md) - 架构总结与改进建议
