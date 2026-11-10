# ADR-0010：多账号凭证隔离、用户级写权限、审计落表与 Agent 只读身份

- 状态：已接受（2026-11-10）
- 关联：ADR-0008（OKX 首个加密数据源）、ADR-0009（能力范围 / MCP / 实盘隔离）
- 取代：**ADR-0009 决策 8 中「写操作靠 `OKX_WRITE_USER_IDS` 环境变量白名单」的部分**；
  ADR-0009 决策 4（实盘凭证不进服务、模拟盘为常量、提币永不开启）**继续有效且被本 ADR 加强**
- 关闭实施计划未决项：§6 第 3 项（审计是否升格为表）、第 4 项（服务账号身份 / token 方案）

## 背景

ADR-0009 把「能调什么」定清楚了，但留下三个与「谁的账户」有关的问题，P0–P9 都无法解决：

1. **凭证是全局的**：`OkxConfig.apiKey/secretKey/passphrase` 从环境变量读一份，全服务共用。
   多用户系统里这等于「所有人的操作都打在同一个账户上」，而且一旦配错就是别人的钱。
2. **写权限是运维白名单**：`OKX_WRITE_USER_IDS` 是环境变量，改一次要重启，授权记录只在
   git/部署平台里，用户自己也看不见。它解决的是「谁能写」，不解决「谁被授权了」。
3. **审计只有日志**：`[OKX-AUDIT]` 日志要留存只能靠日志平台，检索、按用户/标的口径统计
   都做不了；而订单一旦真的发出去，追责恰恰需要结构化数据。

同时 P9 的 `ai-service` 要读受保护端点，但**它没有用户令牌**。三条路各自有硬伤：
复用用户令牌（token passthrough，MCP 规范禁止）、免鉴权内网直连（没有鉴权）、
给服务一个全局凭证（回到问题 1）。

## 决策

### 1. 凭证按用户存，密文落库，KEK 不进库

新增 `data_db.okx_account`（唯一键 `user_id + label`），每行存一套模拟盘凭证：

- `secret_key_enc` / `passphrase_enc` 为 **AES-256-GCM** 密文，格式 `iv(12) || ciphertext || tag(16)`；
- **每行每字段独立随机 IV**：同一明文两次加密结果不同，杜绝「密文相等 → 看出两人用同一把 Key」；
- **AAD 绑定 `userId + 字段名`**：把 A 用户的密文行整行搬到 B 名下会解密失败；
- KEK 只来自环境变量 `OKX_CRED_KEK`（32 字节，Base64 / Hex / 原始 ASCII 三种写法），
  **缺失或长度不对时启动失败**——静默降级成明文存会把整套设计变成摆设；
- `OkxConfig` 里**删除** `apiKey / secretKey / passphrase` 三个字段，
  `OkxClient` 的私有方法第一个参数就是 `OkxCredentials`，**代码里不存在全局凭证回退**。

`OkxClient` 因此从「单例 + 配置凭证」变成「单例 + 每次调用传入凭证」。
连接池与凭证无关，复用不受影响；公共接口（行情、盘口）仍然无凭证。

### 2. 写权限改为用户级数据开关，全局总闸保留

`auth_db.sys_user` 增加 `okx_trade_enabled TINYINT NOT NULL DEFAULT 0`。

- 写操作要求：有效 JWT + `okx_trade_enabled=1` + `okx.trade-enabled=true`（全局总闸）+ 该用户有可用凭证；
- **删除** `OKX_WRITE_USER_IDS` 与 `OkxAuthProperties.writeUserIds`；
- 权威数据在 auth-service，data-service 通过内部接口
  `GET /auth/internal/okxTradeEnabled?userId=` 查询，**不跨库直连**；
- 结果缓存默认 30 秒，**fail-closed**：查不到（auth-service 挂了、密钥没配、返回非 200）一律按无权限处理。
  理由：交易权限查询失败时放行，等于把可用性故障升级成资金风险。

### 3. 审计双写：日志 + `okx_audit_log`

- 与 `[OKX-AUDIT]` 日志**同时**写表；**落表失败只 WARN，绝不影响主流程**。
  审计是追责手段，不是业务正确性的前提——因为数据库抖动让一笔已发到交易所的订单返回 500，
  调用方重试才会真的产生重复下单。
- 表字段涵盖 `user_id / action / scope / outcome / inst_id / symbol / side / sz / px / ord_id /
  cl_ord_id / detail / trace_id / create_time`，索引 `(user_id, create_time)` 与 `(action, create_time)`；
- scope 分三个值：`orders:read` / `orders:write` / `orders:credential`。
  凭证操作单独一个 scope，否则「谁改过 Key」会混进交易审计，按 scope 统计就失真；
- **保留 2 个月**，由 `OkxAuditRetentionJob` 每日 03:30 分批删除（1000 行/批，最多 200 批）。
  不按月分区：分区列必须进主键，会把 `id` 单列主键改成复合主键，是最难回退的一类改动。
  长期归档方案（RANGE 分区 + 月度导出）写在 `V11__okx_multiuser_credentials.sql` 末尾。

### 4. Agent 用独立服务身份，只读，且永不下单

- auth-service 增加 `POST /auth/oauth/token`（`grant_type=client_credentials`），
  签发 `type=SERVICE`、`scope=orders:read`、`aud=fuli-data`、**15 分钟**过期的 JWT，
  没有 `userId` claim；
- ai-service 定时刷新（默认 10 分钟）并在取用时检查剩余寿命，**从不转发用户令牌**；
- data-service 对 SERVICE 主体的判定是**硬编码的**：读需要 `orders:read`，**写一律 403**，
  凭证接口一律 403。不是「工具列表里没注册写工具」这种约定；
- **aud 校验**是「禁止 token passthrough」的落地点：给别的受众签的票在这里不认；
- 服务账号没有 OKX 账户，读私有数据必须显式指定**代理账户身份**
  `okx.agent-owner-user-id`（未配置时返回可操作的 400，而不是给空数据）；
- 新增 `okx_order_suggestion` + `POST /ai/suggest-order`：**只写建议单**，
  data-service 侧的写入服务连 `OkxTradeService` 都不注入，结构上不可能顺手下单。

### 5. WS 与 MCP 的身份用「配置账户 ID」，不是配置凭证

WS 是后台长连接、MCP 工具没有请求上下文，都拿不到「当前 JWT」。二者分别用
`okx.ws-owner-user-id` / `okx.mcp-owner-user-id` 指定一个用户 ID，再从这个用户的
`okx_account` 行解密凭证。**这是身份选择，不是凭证回退**：配置里出现的只是 userId，
不是任何一枚 Key。未配置时私有频道不登录 / 私有工具返回明确错误，绝不换用别人的账户。

## 被否决的选项

| 选项 | 否决理由 |
|---|---|
| 全局凭证继续放环境变量，前端只做「切换」 | 多用户系统里等于所有人共用一个账户；配错即资金风险 |
| 凭证明文存库、靠数据库权限保护 | 备份、慢查询日志、只读副本都会泄露；一次 `mysqldump` 就全没了 |
| 用同一个 KEK 加密所有字段且不绑 AAD | 密文可跨用户搬运；相同明文密文相同 |
| 保留 `OKX_WRITE_USER_IDS` 作为「保险」 | 双写权限来源必然漂移；用户看不见自己有没有权限 |
| 审计只写日志 | 无法按用户/标的检索与统计；日志平台的保留期通常短于追责需要 |
| ai-service 复用用户令牌 | token passthrough，MCP 规范禁止；等于把用户权限借给后台进程 |
| ai-service 免鉴权内网直连 | 等于没有鉴权，任何能连 8084 的进程都能读账户 |
| 给服务账号发 `orders:write` 备用 | 一旦有写 scope，「Agent 不下单」就从协议约束退化成口头约定 |
| 审计表按月分区 | 分区列必须进主键，会把单列主键改复合主键，回退成本最高 |

## 后果

- **部署新增必填项**：`OKX_CRED_KEK`（32 字节）。缺失则 data-service 启动失败——这是刻意的。
- **配置项删除**：`OKX_API_KEY` / `OKX_SECRET_KEY` / `OKX_PASSPHRASE` / `OKX_WRITE_USER_IDS`。
  沿用旧变量不会有任何效果（`@ConfigurationProperties` 忽略未知字段），
  但会让人误以为「已配置」——手册与 `.env.example` 已同步删除。
- **首次使用流程变长**：录 Key（用户）→ 授权（DBA/运维改 `okx_trade_enabled`）→ 下单。
  这是把「配一个环境变量」换成「一次可审计的授权动作」，属于有意的摩擦。
- **权限收回有 30 秒延迟**（缓存）；要立即生效就撤销令牌。
- **审计表会涨**：按每秒 1 条估，2 个月约 500 万行/1.5GB。到 2000 万行切 RANGE 分区。
- `OkxMcpTools` 的 9 个工具仍在，但私有三个需要 `okx.mcp-owner-user-id`；未配置时返回
  `{"ok":false,"error":"...okx.mcp-owner-user-id..."}`，不会静默返回空数据。
- ADR-0009 决策 4 的物理隔离**未被削弱**：`OkxConfig.isSimulated()` 仍是常量、
  `OkxClient` 仍无条件携带 `x-simulated-trading: 1`、WS 仍强制 `wspap.okx.com`；
  ADR-0010 只解决「谁的凭证」，不改变「只能是模拟盘凭证」。
- **实施状态（2026-11-10）**：`V11` 两个脚本已在 `data_db` / `auth_db` 执行
  （副本库预演 → 正式执行 → 存量三表 DDL 与行数逐字未变 → 二次执行可重入）；
  data-service 163 个测试、auth-service 19 个、ai-service 9 个、gateway 23 个全绿；
  data-service 真实启动通过（Tomcat 8084，MCP 注册 9 个工具）。
  **P11 补齐（2026-11-10）**：前端「OKX 账户」页（`/okx`）已落地；
  审计表已切为按月 RANGE 分区（V13，`DROP PARTITION` 清理）；
  余额快照表 `crypto_account`（V12）+ `account` 频道落库 + 快照接口；
  WS 已接 `okx.proxy` 并完成公共频道真机联调（收到真实 ticker 帧）。
  **仍未做**：私有频道登录成功路径（需模拟盘 Key）、真实下单、LLM 接入。
