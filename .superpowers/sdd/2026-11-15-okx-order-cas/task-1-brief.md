# 任务 1：订单响应解析与交易链路

目标：修复 OKX 成功响应为 data 数组时的读取路径，并明确拒单与风控累计语义。

必须先改测试并运行红灯，再改生产代码：

1. 将 `OkxTradeServiceWriteAheadTest`、`OkxTradeGuardTest`、`OkxCapabilityServiceTest` 的写响应和市价 ticker Mock 改成客户端真实契约：直接返回数组节点 `[{...}]`，不带 code/data envelope。
2. 新增 `OkxOrderResponse` 契约测试，覆盖 `first`、`ordId`、`sCode`、`sMsg` 对 null、空数组、缺字段的行为。
3. 新增/调整交易测试：sCode=0 且 ordId 非空时登记并累计风险；sCode=51008 时本地 markSubmitted 被调用、抛 `OkxOrderRejectedException`、不累计风险；空数组/缺 ordId 不累计风险并抛明确异常。
4. 覆盖市价单从数组首项 last 做金额风控，空数组明确拒绝。
5. 实现 `OkxOrderResponse.java` 和 `OkxOrderRejectedException.java`，并在 `OkxTradeService`、`OkxCapabilityService`、`OkxController` 统一使用。`OkxClient` 契约不得修改。
6. 对明确拒单先调用 `orderLog.markSubmitted` 再抛异常；风险累计只发生在 sCode=0 且 ordId 非空时。传输异常不自动重发。

约束：只改本任务涉及的 Java 生产代码和测试；代码注释中文；不录入凭证、不真实下单、不执行迁移。

完成后运行：
`./mvnw -pl data-service -am -Dtest=OkxTradeServiceWriteAheadTest,OkxTradeGuardTest,OkxCapabilityServiceTest,OkxOrderResponseTest -Dsurefire.failIfNoSpecifiedTests=false test`

报告写入：`.superpowers/sdd/2026-11-15-okx-order-cas/task-1-report.md`，提交代码并返回提交号、测试结果和遗留疑问。
