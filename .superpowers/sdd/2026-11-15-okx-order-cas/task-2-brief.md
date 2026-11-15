# 任务 2：建议单原子状态机

目标：让建议单确认和拒绝在数据库层原子抢占，避免并发请求同时进入交易链路。

必须先改测试并运行红灯，再改生产代码：

1. 在 `OkxSuggestionExecutionServiceTest` 中把交易响应 Mock 改为客户端真实数组，或使用任务 1 的 `OkxOrderResponse` 读取契约。
2. 新增 CAS 测试：`claimNew` 返回 0 时不调用交易服务；成功确认只允许 `executing → executed`；拒绝只允许 `new → rejected`；确认和拒绝的失败 CAS 都不调用交易服务；明确 `OkxOrderRejectedException`、参数、凭证或风控异常时 `executing → new`；未知运行时/网络异常保持 `executing`。
3. 在 `OkxOrderSuggestionMapper` 增加条件更新方法，方法签名固定为：
   - `int claimNew(long id, long userId)`：匹配 `id/user_id/status='new'`，更新 `status='executing'`；
   - `int markExecuted(long id, long userId)`：匹配 `status='executing'`，更新 `status='executed'`；
   - `int resetExecuting(long id, long userId)`：匹配 `status='executing'`，更新 `status='new'`；
   - `int rejectNew(long id, long userId)`：匹配 `status='new'`，更新 `status='rejected'`。
   每个更新都同步 `update_time=CURRENT_TIMESTAMP`。
4. `OkxSuggestionExecutionService.execute` 先验证存在和归属，再调用 `claimNew`；返回 0 时抛出“已被其他请求处理”，不得调用交易服务。下单成功后调用 `markExecuted` 并返回数组首项 ordId。明确业务失败回退 executing→new；未知异常直接抛出并保持 executing。
5. `reject` 先验证存在和归属，再调用 `rejectNew`；返回 0 时抛出并不重复更新。
6. 更新实体中文状态注释；不使用数据库事务包住网络调用。

只修改建议单实体、Mapper、执行服务和对应测试。不要修改前端、SQL 或 OkxClient。

完成后运行：
`./mvnw.cmd -pl data-service -am "-Dtest=OkxSuggestionExecutionServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

报告写入：`.superpowers/sdd/2026-11-15-okx-order-cas/task-2-report.md`，提交代码并返回提交号、测试结果和遗留疑问。
