# 任务 1 报告：订单响应解析与交易链路

日期：2026-11-15

## 完成内容

- 新增 `OkxOrderResponse`，统一从 OKX 客户端返回的数组首项读取 `ordId`、`sCode`、`sMsg`，并覆盖 null、空数组、非对象首项和缺字段场景。
- 新增 `OkxOrderRejectedException`，明确表达 OKX 嵌套订单拒单结果。
- `OkxTradeService` 改为消费数组契约：明确拒单先调用 `markSubmitted` 再抛异常；只有 `sCode=0` 且 `ordId` 非空时调用风险累计；空数组、缺 `sCode`、缺 `ordId` 均不累计风险并抛明确异常；市价单从数组首项 `last` 计算风控金额，空 ticker 直接拒绝。
- `OkxCapabilityService` 和 `OkxController` 统一使用 `OkxOrderResponse`；能力写接口也先登记响应再抛明确拒单异常；幂等回包改为数组形状。
- 更新 `OkxTradeServiceWriteAheadTest`、`OkxTradeGuardTest`、`OkxCapabilityServiceTest` 的写响应与 ticker Mock 为数组契约，并新增订单响应契约、拒单、缺数据和市价风控测试。
- 未修改 `OkxClient` 契约、建议单、前端或 SQL。

## TDD 验证

先撤回已有生产实现，仅保留测试改动并运行专项测试，红灯原因为测试编译阶段缺少待实现的 `OkxOrderResponse` 与 `OkxOrderRejectedException`。

实现后运行：

```text
.\mvnw.cmd -pl data-service -am "-Dtest=OkxTradeServiceWriteAheadTest,OkxTradeGuardTest,OkxCapabilityServiceTest,OkxOrderResponseTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：`Tests run: 31, Failures: 0, Errors: 0, Skipped: 0`，构建成功。

另外运行完整 data-service 测试：

```text
.\mvnw.cmd -pl data-service -am test
```

结果：`Tests run: 239, Failures: 0, Errors: 0, Skipped: 0`，构建成功。

## 遗留疑问

无。传输异常仍直接向上抛出，不会自动重发。

## 审查补测

- `OkxOrderResponseTest` 新增数组首项为非对象时，`first`、`ordId`、`sCode`、`sMsg` 均为空的回归测试。
- `OkxCapabilityServiceTest` 新增 `sCode=51008` 时先 `markSubmitted`、再抛 `OkxOrderRejectedException` 的回归测试。
- `OkxAuthenticationTest` 新增控制器从数组回包提取 `ordId` 并写入成功审计事件的回归测试。

将三个旧解析路径临时恢复后运行补测，三项测试均按预期失败；恢复当前实现后运行：

```text
.\mvnw.cmd -pl data-service -am "-Dtest=OkxTradeServiceWriteAheadTest,OkxTradeGuardTest,OkxCapabilityServiceTest,OkxOrderResponseTest,OkxAuthenticationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：`Tests run: 51, Failures: 0, Errors: 0, Skipped: 0`，构建成功。
