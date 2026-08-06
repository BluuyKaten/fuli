# 股票复盘系统 - 代码审查改进清单

> 生成日期:2026-08-07
> 审查范围:后端核心模块(trade-service / auth-service / data-service / analysis-service)、前端核心页面
> 说明:本文档基于代码现状梳理改进点,与 `doc/Project.md` 互补——**不重复已列出的建议**,只讲实际读代码后发现的问题。

---

## 🔴 高优先级(正确性 / 数据一致性)

### 1. 删除 / 编辑交易时,持仓与资金未回滚

- **位置**:`TradeRecordServiceImpl.updateTrade` / `deleteTrade`
- **现状**:现在只修改 `trade_record` 单表,`position_summary` 与 `auth-service` 的现金余额没有联动。
- **后果**:
  - 删掉一笔历史买入 → 持仓没恢复,现金也没退回。
  - 删掉一笔历史卖出 → 持仓没补回,资金也没扣回。
  - 数据会越对不上,是**目前最大的一致性隐患**。
- **建议方案**:
  - 删除时按"反向交易"回滚持仓(买入变卖出减仓,卖出变买入加仓)。
  - 若该持仓后续还有交易,加权均价会变,需明确策略:**推荐只允许从最后一笔倒删**,或提示用户"存在后续交易,不允许删除中间记录"。
  - 资金侧同样通过本地消息表发一条反向消息,与正向交易对称。
  - 编辑交易视为"先删后增",复用同一套回滚逻辑。

### 2. 本地消息表缺少幂等保护与重试策略细节

- **位置**:`LocalMessage` + `TradeCreatedEvent` + `LocalMessageRetryServiceImpl`
- **现状**:事件写在 `@Transactional` 内,监听器在 `AFTER_COMMIT` 触发——这一步 OK。但重试发送 `addCash / deductCash` 时,**消息侧没有携带 `msgId` 做幂等**。
- **后果**:Feign 超时重试时,`auth-service` 可能被扣 / 入两次。
- **建议**:
  - `auth-service` 的 `/internal/addCash`、`/internal/deductCash` 接口增加 `msgId` 参数。
  - `auth-service` 侧用 `msgId` 做幂等键(数据库唯一索引或 Redis SETNX),重复调用直接返回成功。
  - 超过最大重试次数进死信,并触发告警(日志 + 可选 webhook)。
  - 关键字段:`msgId`、`status`、`retryCount`、`nextRetryTime`、`lastError`。

### 3. `validateBuyCash` 失败时"降级跳过校验"

- **位置**:`TradeRecordServiceImpl.validateBuyCash`
- **现状**:Feign 异常只 `warn` 后直接放行。
- **后果**:`auth-service` 宕机时,用户可以无限透支买入。
- **建议**:对"资金查询失败"采用 **fail-closed** 策略(默认拒绝交易),并通过运维开关控制 fail-open / fail-close。至少不能让异常静默吞掉。

---

## 🟡 中优先级(体验 / 可维护性)

### 4. 卖出涨跌停校验依赖行情数据,缺数据时静默跳过

- **位置**:`TradeRecordServiceImpl.validateSellPrice`
- **现状**:拿不到前收盘价就直接 `return`,等于没校验。
- **后果**:复盘时可能乱填价格,失去涨跌停约束意义。
- **建议**:缺数据时阻塞并提示用户"请先同步行情数据",而不是静默放行。

### 5. 持仓更新的并发安全

- **位置**:`PositionSummaryServiceImpl.increasePosition / decreasePosition`
- **现状**:读 → 改 → 写,没有并发保护。
- **后果**:快速双击或并发下单时,`total_quantity` / `avg_cost` 可能脏读。
- **建议**:
  - 方案 A:加 `version` 字段,用乐观锁。
  - 方案 B:用原子 SQL,例如 `UPDATE position_summary SET total_quantity = total_quantity - #{qty} WHERE user_id = #{uid} AND stock_code = #{code} AND total_quantity >= #{qty}`。
  - 方案 C:对单用户持仓加分布式锁(Redis)。
  - 推荐 B,简单且无需引入 Redis。

### 6. 前端 K 线图买卖点保存的错误透传

- **位置**:`KlineChart.vue` 保存交易逻辑
- **现状**:后端抛的 `BusinessException`(持仓不足 / 资金不足 / 价格超涨跌停)没有明确区分。
- **建议**:前端根据后端返回的 `code` 或错误码字段,给出针对性 toast,而不是笼统的"保存失败"。

### 7. 统一 Feign 接口与 JWT 校验

- **位置**:`common-api`、`gateway-service`、`auth-service`
- **现状**:未提交改动已把 `trade-service/feign/AuthFeignClient.java` 删掉,改走 `common-api` 一份——**方向正确**。
- **建议**:
  - 一并清理网关层与服务层的双 JWT 解析,统一由网关校验,服务层只信任 `X-User-Id`。
  - 所有 Feign 接口收拢到 `common-api`,避免签名漂移。

---

## 🟢 低优先级(锦上添花)

### 8. `data-service` 同步失败的股票没补偿机制

- **位置**:`TushareSyncServiceImpl`
- **现状**:增量同步时某只股票 Tushare 报错会跳过,没有"失败列表 + 手动重试入口"。
- **建议**:记录失败列表,前端 `/sync` 页面提供"重试失败项"按钮。

### 9. 前端 DTO 类型缺失

- **位置**:`DashboardPage.vue`、`KlineChart.vue`
- **现状**:`dashboard` 接口返回还是 `any`。
- **建议**:把后端 VO 类型用 `openapi-typescript` 生成,而不是手写,避免字段漂移。

### 10. 缺少核心流程测试

- **位置**:`trade-service/src/test/`
- **现状**:测试目录基本为空。
- **建议**:`createTrade` 这条主路径(含持仓、资金、盈亏计算)是最该测的,补一个 H2 + Mockito 的集成测试,防止后续重构算错钱。

---

## 修复顺序建议

1. **先修正确性**:问题 1(删除回滚)、2(消息幂等)、3(fail-closed)
2. **再补机制**:问题 4(涨跌停阻塞)、5(并发安全)
3. **最后体验**:问题 6(错误透传)、7(统一 Feign/JWT)
4. **长期打磨**:问题 8(同步补偿)、9(类型生成)、10(测试覆盖)

每项可单独展开为设计与实现计划。
