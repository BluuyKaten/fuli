F# 股票复盘系统 (fuli) - 项目总结与建议

## 一、项目概述

### 1.1 项目定位

**fuli（复利）** 是一个面向个人投资者的 **股票复盘系统**，核心理念是"复利"——通过系统化的复盘分析帮助投资者总结经验、提升交易水平。

项目目标是构建一个集 **行情查看、交易记录、持仓管理、数据分析、数据同步** 于一体的闭环投资复盘工具。用户可以：

- 在 K 线图上直观标注买卖点并保存为交易记录
- 查看持仓盈亏、资产曲线、月度收益等关键指标
- 从 Tushare 同步真实行情数据，支撑模拟复盘

### 1.2 当前版本状态

- **版本**：`1.0.0`
- **阶段**：**MVP（最小可行产品）** 已完成，核心闭环（注册 → 看 K 线 → 下单 → 看分析）已经打通
- **代码规模**：后端 6 个 Maven 模块、约 50 个 Java 类；前端 Vue 3 + TS，约 15 个组件
- **技术栈**：Spring Boot 4.1 + Spring Cloud 2025.1 + MyBatis-Plus 3.5 + MySQL + Redis + Vue 3 + Vite + ECharts

---

## 二、技术架构

### 2.1 整体架构

```
┌─────────────────────────────────────────────────────────┐
│                    前端 (Vue 3 + Vite)                   │
│              端口: 3000 → 代理到 8080                    │
└──────────────────────────┬──────────────────────────────┘
                           │ /api/**
                           ▼
┌─────────────────────────────────────────────────────────┐
│              API 网关 (gateway-service:8080)             │
│         JWT 校验 / 路由转发 / 用户 ID 透传               │
└──────┬──────────────┬──────────────┬──────────────┬─────┘
       │              │              │              │
       ▼              ▼              ▼              ▼
┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐
│  auth-     │ │  trade-    │ │ analysis-  │ │  data-     │
│  service   │ │  service   │ │  service   │ │  service   │
│  (8081)    │ │  (8082)    │ │  (8083)    │ │  (8084)    │
│ 用户/认证  │ │ 交易/行情  │ │ 统计分析   │ │ Tushare同步│
└──────┬─────┘ └─────┬──────┘ └──────┬─────┘ └────────────┘
       │             │               │
       ▼             ▼               ▼
   auth_db        trade_db      trade_db (只读)
                  (主库)
```

### 2.2 后端技术栈

| 类别 | 技术选型 | 版本 |
|------|---------|------|
| 框架 | Spring Boot | 4.1.0 |
| 微服务 | Spring Cloud | 2025.1.2 |
| 微服务 | Spring Cloud Alibaba | 2025.1.0.0 |
| ORM | MyBatis-Plus (Spring Boot 4 专用) | 3.5.17 |
| 数据库 | MySQL | 9.x |
| 缓存 | Redis | - |
| 认证 | JJWT (HMAC-SHA256) | 0.13.0 |
| 服务注册 | Nacos（可选，本地可不启动） | - |
| RPC | OpenFeign | - |
| 工具库 | Hutool + FastJSON2 + Lombok | - |

### 2.3 前端技术栈

| 类别 | 技术选型 | 版本 |
|------|---------|------|
| 框架 | Vue 3 | 3.5.13 |
| 构建 | Vite | 6.0.5 |
| 语言 | TypeScript | 5.7.2 |
| UI 库 | Ant Design Vue | 4.2.6 |
| 图表 | ECharts | 5.5.1 |
| 状态 | Pinia | 2.3.0 |
| 路由 | Vue Router | 4.5.0 |
| HTTP 客户端 | Axios | 1.7.9 |
| 日期处理 | Day.js | 1.11.13 |

---

## 三、模块详解

### 3.1 common-api（公共模块）

- 统一响应封装 `Result<T>`
- 业务异常 `BusinessException`
- 交易类型枚举 `TradeTypeEnum`
- 共享 DTO：`LoginDTO`、`TradeDTO`、`TradeQueryDTO`
- 共享 VO：`LoginVO`、`TradeVO`、`StatisticsVO`、`PositionVO`、`MonthlyProfitVO`
- Feign 客户端接口：`AuthFeignClient`、`TradeFeignClient`

### 3.2 gateway-service（网关）

- **端口**：8080
- 基于 Spring Cloud Gateway **WebFlux**（响应式）
- 路由：`/api/auth/**`、`/api/trade/**`、`/api/stock/**`、`/api/analysis/**`、`/api/data/**`
- `JwtGlobalFilter`：解析 JWT，白名单放行登录/注册，校验通过后注入 `X-User-Id` 和 `X-Username` 请求头
- 支持 Nacos 服务发现（`lb://` 负载均衡）

### 3.3 auth-service（认证服务）

- **端口**：8081，**数据库**：`auth_db`
- 用户表 `sys_user`：用户名、BCrypt 密码、昵称、邮箱、手机、头像、现金余额（默认 ¥200,000）
- 功能：
  - 注册 / 登录（返回 JWT）
  - 个人资料查看与修改、密码修改
  - 内部扣款 / 入账 / 重置现金接口（供 Feign 调用）
- 双保险 JWT 校验：网关层解析一次，服务内 `JwtAuthenticationFilter` 再用 Spring Security 解析一次
- 支持 Redis 令牌黑名单（登出场景）

### 3.4 trade-service（交易服务）

- **端口**：8082，**数据库**：`trade_db`
- 核心表：`trade_record`（交易记录）、`position_summary`（持仓汇总）、`stock_info`（股票基础信息）、`stock_daily_data`（日线行情）
- 功能：
  - 交易 CRUD（含分页、条件查询、统计）
  - 买入时 Feign 调用 `auth-service` 扣款；卖出时入账
  - 自动计算卖出盈亏、盈亏比率
  - 股票搜索、股票信息查询、日线数据查询
- 启动时执行 `StockDataInitializer` 初始化内置股票基础数据

### 3.5 analysis-service（分析服务）

- **端口**：8083，**数据源**：`trade_db`（只读）
- 通过 Feign 调用 `trade-service` 获取数据，再做聚合分析
- 功能：
  - 交易统计（胜率、盈亏比、平均盈亏、最大盈亏）
  - 月度盈亏汇总
  - 资产曲线（累计盈亏）
  - 仪表盘（总资产、总市值、现金、持仓列表、当日盈亏）

### 3.6 data-service（数据同步服务）

- **端口**：8084，**数据源**：`trade_db`
- 对接 **Tushare Pro** 行情接口，支持：
  - 同步股票基础信息（`stock_basic`）
  - 同步单只股票日线（`daily`）
  - 按交易日期同步全市场日线
  - 智能增量同步（自动检测最新日期，批量同步缺失数据）
- 内置定时任务：
  - 每天 18:00 同步当日行情
  - 工作日 19:00 同步股票基础信息
  - 工作日 19:30 增量同步所有股票

---

## 四、前端功能清单

| 页面 | 路由 | 功能 |
|------|------|------|
| 登录/注册 | `/login` | 账号登录、新用户注册（初始资金 ¥200,000） |
| 仪表盘 | `/dashboard` | 总资产、盈亏百分比、浮动盈亏、市值、现金、持仓列表、月度盈亏柱状图、资产曲线 |
| 交易记录 | `/trade` | 分页列表、按股票/类型/日期筛选、编辑、删除 |
| 新增交易 | `/trade/add` | 表单录入买卖交易 |
| K 线图 | `/kline` | 股票搜索、蜡烛图、MA5/10/20、成交量、MACD、KDJ、点击标注买卖点、保存交易 |
| 数据同步 | `/sync` | 智能增量同步、基础信息同步、按日期同步、同步状态检查 |
| 账户信息 | `/account` | 资料修改、现金重置（清空交易） |

---

## 五、已完成功能（✅）

- [x] 用户注册 / 登录 / JWT 认证
- [x] 网关路由转发 + 用户 ID 透传
- [x] 个人资料 / 密码修改 / 现金重置
- [x] K 线图（蜡烛图 + MA + 成交量 + MACD + KDJ）
- [x] K 线图点击标注买卖点
- [x] 交易录入（买入扣款 / 卖出入账 + 盈亏计算）
- [x] 交易记录分页查询、编辑、删除
- [x] 仪表盘（总资产、盈亏、持仓、月度、曲线）
- [x] Tushare 数据同步（全量 / 增量 / 定时）

---

## 六、待完成功能（⏳）

根据 `docs/开发进度.md` 与代码现状：

- [ ] 持仓列表实时刷新（仪表盘已有，但编辑交易后未联动）
- [ ] 交易历史高级筛选（按盈亏区间、股票多选）
- [ ] 股票自选股 / 收藏
- [ ] 实时行情（WebSocket 推送）
- [ ] 复盘报告生成（PDF / 图片导出）
- [ ] 更丰富的数据分析图表（行业分布、资金流向）

---

## 七、项目亮点

### 7.1 架构设计合理

- **微服务拆分清晰**：auth / trade / analysis / data 各司其职，数据库按服务独立（`auth_db` + `trade_db`）
- **遵循 Spring Boot 4.x 新规范**：使用 `spring-cloud-starter-gateway-server-webflux`、`mybatis-plus-spring-boot4-starter`
- **响应式网关 + 阻塞式业务服务**：性能与开发效率兼顾

### 7.2 业务闭环完整

从 **"看行情 → 标注买卖点 → 保存交易 → 看持仓与分析"** 的复盘流程已完整打通，真正可用。

### 7.3 数据同步设计巧妙

- **智能增量同步**：自动检测最新日期，避免重复拉取
- **批量分片**：按 30 天一个批次同步，避免单次请求过大
- **定时 + 手动**双通道：满足不同场景

### 7.4 前端 K 线图实现扎实

- 完整实现 **MA / EMA / MACD / KDJ** 四大技术指标的前端计算
- 多图联动（`echarts.connect`）+ 缩放同步
- 点击 K 线即可标注买卖点，交互体验好

---

## 八、现存问题与风险

### 8.1 🔴 高优先级（影响正确性/安全）

| 问题 | 位置 | 说明 |
|------|------|------|
| **JWT Secret 硬编码** | 各 `application.yml` | `your-256-bit-secret-key-here...` 占位符直接提交，存在安全风险 |
| **数据库密码明文** | `application.yml` | `root / 123456` 明文，且多个服务重复配置 |
| **交易盈亏计算逻辑偏差** | `TradeRecordServiceImpl` | `getAvgCost()` 用最后一笔买入价代替"均价"，不符合真实持仓成本计算（应使用加权平均） |
| **买卖跨服务事务一致性** | `createTrade` | 本地 `save` 成功但 Feign 扣款失败时，`@Transactional` 无法回滚远程调用，存在资金与交易不一致风险 |
| **卖出数量校验缺失** | `createTrade` | 未校验当前持仓是否足够，允许"卖空" |
| **内部接口暴露** | `auth-service` 的 `/auth/internal/**` | Spring Security 配置为 `permitAll()`，网关也未拦截，可能被外部直接调用 |

### 8.2 🟡 中优先级（影响可维护性/体验）

| 问题 | 位置 | 说明 |
|------|------|------|
| **analysis-service 与 trade-service 循环依赖风险** | pom 依赖 | `analysis-service` 依赖 `trade-service` 实体类，同时通过 Feign 调用它 |
| **重复的 Feign 接口** | `common-api` vs `trade-service` | `AuthFeignClient` 在两个模块各定义一份，签名略有差异 |
| **JWT 双重解析** | 网关 + 服务 | 网关已解析一次，`auth-service` 的 `JwtAuthenticationFilter` 又解析一次 |
| **Tushare Token 通过环境变量注入** | `application.yml` | `${TUSHARE_TOKEN}` 未设置时会启动失败，缺少降级处理 |
| **前端 `any` 类型泛滥** | 多个 `.vue` 文件 | 仪表盘、K线图大量使用 `any`，丧失了 TS 的类型保护 |
| **缺少全局错误处理** | 前端 | 网络异常、业务错误的提示不够统一 |

### 8.3 🟢 低优先级（优化项）

| 问题 | 位置 | 说明 |
|------|------|------|
| **SQL 脚本不完整** | `sql/` 目录 | 缺少 `analysis_db.sql`（虽然 analysis 走的是 trade_db） |
| **没有单元/集成测试** | 整个后端 | 测试目录为空 |
| **没有接口文档** | 后端 | 缺少 Swagger / OpenAPI 定义 |
| **没有 docker-compose** | 项目根目录 | 本地搭建依赖较多（MySQL + Redis + Nacos），新手不友好 |
| **端口与文档不一致** | `开发进度.md` | 文档写的是 8000/8001/8002/8003，实际是 8080/8081/8082/8083 |

---

## 九、改进建议

### 9.1 🔐 安全加固（必做）

1. **JWT Secret 外部化**：
   - 使用 `~/.fuli/` 本地密钥文件、环境变量、或 Nacos 配置中心
   - 生产环境至少使用 64 字节随机密钥，并定期轮换

2. **数据库凭证加密**：
   - 使用 Jasypt 或 Spring Cloud Config 加密
   - 或迁移到 Nacos 配置中心统一管理

3. **内部接口保护**：
   - 为 `/internal/**` 添加内部调用验证（如 `X-Internal-Key` 请求头）
   - 或使用网络层限制（仅内网可访问）

### 9.2 🧮 交易逻辑完善（必做）

1. **持仓成本使用加权平均**：
   ```java
   // 新均价 = (原持仓×原均价 + 新买入×成交价) / (原持仓 + 新买入)
   ```
   在 `createTrade` 买入时更新 `position_summary`，卖出时读取真实均价。

2. **卖出前校验持仓**：
   - 买入时增加持仓汇总记录
   - 卖出时校验 `holdingQuantity >= sellQuantity`
   - 不足时抛出 `BusinessException("持仓不足")`

3. **跨服务事务一致性**：
   - 短期：在 `createTrade` 中先调用 Feign 扣款/入账，成功后再本地保存
   - 中期：引入可靠消息（RocketMQ / RabbitMQ）+ 本地事务表
   - 长期：考虑 TCC / Seata AT 分布式事务

### 9.3 🏗 架构优化（建议）

1. **统一 Feign 接口**：
   - 将 `AuthFeignClient`、`TradeFeignClient` 统一到 `common-api`
   - 各服务实现或调用同一份接口，避免签名漂移

2. **合并 analysis-service 与 trade-service 的数据源**：
   - 当前 analysis 直连 trade_db 只读，又通过 Feign 调 trade-service
   - 建议统一走 Feign，保持服务边界清晰；或干脆合并两个服务

3. **引入接口文档**：
   - 添加 `springdoc-openapi`（Swagger UI）
   - 前端可用 OpenAPI Generator 生成 API Client

4. **提供 docker-compose**：
   ```yaml
   services:
     mysql: ...
     redis: ...
     nacos: ...
   ```
   一键拉起所有依赖

### 9.4 🧪 质量保障（建议）

1. **补充单元测试**：
   - Service 层核心逻辑（盈亏计算、持仓更新）至少 80% 覆盖
   - 使用 H2 内存库做集成测试

2. **引入 CI**：
   - GitHub Actions：`mvn test` + `npm run build`
   - 提交前自动检查

3. **统一异常处理与日志**：
   - 后端 `@RestControllerAdvice` 统一返回错误结构
   - 关键业务操作（买入/卖出/资金变动）留审计日志

### 9.5 🎨 前端优化（建议）

1. **类型定义完善**：
   - 把 `dashboardData`、`monthlyData` 等 `any` 替换为真实类型
   - 从后端 VO 类型自动生成（可用 `openapi-typescript`）

2. **抽取公共组件**：
   - 交易表单 `TradeForm.vue` 新增/编辑复用不充分
   - 账户信息、修改密码等可抽离

3. **K 线图性能**：
   - 当前 `downsample` 是空函数，大数据量（>2000 条）时需实现降采样（LTTB 算法）

### 9.6 📊 产品功能演进（规划）

1. **短期（1-2 周）**：
   - 修复上述高优先级问题
   - 持仓列表与交易编辑联动
   - 接口文档 + docker-compose

2. **中期（1-2 月）**：
   - 自选股功能
   - 复盘报告导出（PDF）
   - 策略回测框架（给定策略 → 历史数据回测）

3. **长期（3-6 月）**：
   - 实时行情 WebSocket
   - 多用户社区（分享复盘）
   - 移动端（UniApp / Flutter）

---

## 十、总结

**fuli 是一个完成度较高的个人股票复盘 MVP**。整体架构规范、技术选型紧跟 Spring 生态最新版本，业务闭环已经打通，尤其是 K 线图交互和数据同步设计颇具亮点。

当前最需要关注的是 **安全性**（硬编码密钥/密码）和 **交易逻辑正确性**（持仓成本计算、卖出校验、跨服务事务），这两类问题一旦进入真实使用会造成资金数据错误或安全漏洞。

在完善上述问题后，项目完全具备作为 **个人投资复盘工具** 持续使用，并可作为 **Spring Boot 4 + Spring Cloud 微服务** 的学习范例。

---

*文档生成日期：2026-08-05*
*基于代码版本：stock-parent 1.0.0*
