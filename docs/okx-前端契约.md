# OKX 账户设置 · 前端契约

- 版本：v1（2026-11-10）
- 后端实现：`data-service`（`OkxAccountController` / `OkxCredentialService`）
- 决策依据：`docs/adr/0010-okx-multiuser-credentials-and-agent-readonly.md`
- 状态：**后端 + 页面均已完成**（页面 `frontend/src/views/okx/OkxSettingsPage.vue`，路由 `/okx`）。

## 0. 一句话

用户在「系统设置 → OKX 账户」里录入**自己的模拟盘 API Key**；后端加密存库、只回掩码；
**是否允许交易由管理员在数据侧授权**（`sys_user.okx_trade_enabled`），前端不提供这个开关。

## 1. 通用约定

- 全部经网关：`/api/data/okx/account`（网关 strip 前缀后落到 data-service `/data/okx/account`）。
- 必须带 `Authorization: Bearer <用户 JWT>`，无令牌 **401**。
- 响应统一 `Result<T>`：`{"code":200,"message":"操作成功","data":...,"timestamp":...}`。
- 失败时 `code` 为 HTTP 状态（400/401/403/503），`message` 是可直接展示的中文原因。
- **401/403 不会被包装成 400**：前端按状态码分支，不要只看 `code`。

## 2. 录入 / 更新凭证

```
POST /api/data/okx/account
Content-Type: application/json
```

```json
{
  "label": "模拟盘-主账户",
  "apiKey": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
  "secretKey": "********",
  "passphrase": "********",
  "enabled": true
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `label` | 否 | 默认 `默认`；同一用户下唯一，**更新就是靠它定位**。只允许中英文、数字、空格与 `.:_-`，≤64 字符 |
| `apiKey` | 是 | OKX API Key，≤128 字节 |
| `secretKey` | 是 | ≤256 字节；**更新时必须与 passphrase 一起重新填**，不允许只改一部分 |
| `passphrase` | 是 | 创建 API Key 时自设的口令，不是 secret |
| `enabled` | 否 | 默认 `true`。`false` 表示保留密文但停用（交易不会用它） |

成功 200：

```json
{
  "code": 200,
  "data": {
    "label": "模拟盘-主账户",
    "apiKeyMasked": "abcd****wxyz",
    "enabled": true,
    "keyVersion": 1,
    "createTime": "2026-11-10T11:16:44",
    "updateTime": "2026-11-10T11:16:44"
  }
}
```

> **响应里永远没有 `secretKey` / `passphrase` 字段**。前端不要尝试拼出来、不要缓存明文、
> 提交成功后立刻清空表单变量。

常见失败：

| 状态 | message 示例 | 前端处理 |
|---|---|---|
| 400 | `缺少参数：secretKey` / `label 只允许中英文、数字、空格与 .:_- ` | 表单校验提示 |
| 403 | `服务账号不允许访问凭证接口` | 正常用户不会遇到；说明用了服务令牌 |
| 503 | `OKX 鉴权未配置：请为 data-service 注入…JWT_SECRET` | 提示联系运维 |

## 3. 查询已配置账户

```
GET /api/data/okx/account
```

```json
{
  "code": 200,
  "data": [
    { "label": "模拟盘-主账户", "apiKeyMasked": "abcd****wxyz", "enabled": true,
      "keyVersion": 1, "createTime": "2026-11-10T11:16:44", "updateTime": "2026-11-10T11:16:44" }
  ]
}
```

- 只返回**自己的**账户；请求里没有、也不接受 `userId` 参数。
- 没有配置时返回 `data: []`（不是 404）。
- 掩码规则：长度 ≥12 时保留头尾各 4 位，否则整体 `****`。

## 4. 状态自检（推荐首屏调用）

```
GET /api/data/okx/status
```

关心的字段：

```json
{
  "enabled": false,                 // 全局总闸 okx.trade-enabled
  "simulated": true,                // 恒为 true：系统只接模拟盘
  "credentialSource": "per-user:data_db.okx_account",
  "credentialConfigured": true,     // 当前用户是否已录 Key
  "principal": "user:1",
  "accountUserId": 1
}
```

前端可以据此渲染三态：
1. `credentialConfigured=false` → 「去配置 API Key」；
2. `credentialConfigured=true && enabled=false` → 「已配置，等待管理员开通交易权限」；
3. `credentialConfigured=true && enabled=true` → 可交易（但仍需用户本人有 `okx_trade_enabled`，
   这个值前端拿不到，只能靠下单接口的 403 判断，见 §6）。

## 5. 授权不是前端功能

「是否允许这个用户交易」存在 `auth_db.sys_user.okx_trade_enabled`，**没有自助接口**，只能由
DBA/运维执行：

```sql
UPDATE auth_db.sys_user SET okx_trade_enabled = 1 WHERE id = <用户ID>;
```

原因：自助开通交易权限等于把风控开关交给被风控的人。前端不要做「申请开通」按钮，
最多显示文案「如需开通请联系管理员」。

生效延迟：data-service 侧缓存 30 秒；用户重新登录不能加速，撤销令牌可以立即阻断。

## 6. 下单接口的 403 语义（前端必须区分）

| 403 message 片段 | 含义 | 前端提示 |
|---|---|---|
| `未被授予 OKX 交易权限（sys_user.okx_trade_enabled=0）` | 缺用户级授权 | 联系管理员开通 |
| `服务账号不允许执行写操作` | 用了服务令牌 | 前端不可能遇到；属接入错误 |
| `缺少 orders:read scope` | 令牌 scope 不足 | 重新登录 |

400 才是「参数/开关/凭证」这类业务错误，例如：
`OKX 下单已关闭：把 okx.trade-enabled 打开后才允许交易`、
`当前用户尚未配置可用的 OKX 模拟盘凭证…`。

## 7. 安全红线（前端也要守）

1. **不要在 localStorage 里存 secretKey / passphrase**；提交后立即丢弃。
2. **不要展示完整 apiKey**，只用后端给的 `apiKeyMasked`。
3. 不要把 Key 拼进 URL / query，也不要在错误上报里带请求体。
4. 页面文案统一写「模拟盘」：系统不存在实盘路径，写「实盘」会误导用户。

## 7.5 余额快照与 WS 状态（已实现，页面底部使用）

```http
GET /api/data/okx/snapshots/latest
→ [{"ccy":"TOTAL","equity":12345.6,"uTime":1794283000000,"snapshotAt":"..."},
   {"ccy":"USDT","equity":10000,"cashBal":9000,"availBal":8000,"frozenBal":1000,"upl":0,...}]

GET /api/data/okx/snapshots?ccy=USDT&limit=60
→ 同一结构，按 u_time 倒序（画权益曲线用）

GET /api/data/okx/ws/status
→ {"enabled":true,"status":"LOGGED_IN","mode":"PUBLIC",
   "subscriptions":["tickers:BTC-USDT"],"droppedFrames":0,
   "lastFrameAt":"2026-11-10T05:00:15Z","lastFrameAgeSeconds":0,
   "lastFramePreview":"{...}"}
```

- `ccy='TOTAL'` 是合成行（OKX `totalEq`，折合 USD），画总权益用它。
- 权益字段叫 **`equity`**，不是 `eq`：数据库列名是 OKX 口径的 `eq`，但 `eq` 是 OGNL 运算符关键字，
  不能做 Java 字段名，序列化出来就叫 `equity`。
- 没有数据是**正常状态**（还没配 Key / WS 未登录私有频道），页面应显示空状态并说明原因，
  不要用 0 填充，避免被误读成「账户没钱」。
- `mode=PUBLIC` 表示只连了公共行情；私有余额要 `PRIVATE` + 登录成功。
- `lastFrameAgeSeconds` 超过 60 基本可以判定「连着但没数据」，页面可标红提示。

## 8. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-11-10 | v1：凭证录入/查询 + status 字段 + 403 语义 |
| 2026-11-10 | v1.1：页面落地（`/okx`）；新增余额快照与 WS 状态三个接口 |
