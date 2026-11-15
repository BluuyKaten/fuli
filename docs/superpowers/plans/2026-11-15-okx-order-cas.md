# OKX 下单结果与建议单并发修复实施计划

> **For agentic workers:** 使用子代理驱动开发按任务执行，并在每个任务后做范围审查。

**目标：** 修复 OKX 订单响应数组契约、明确拒单语义，并用数据库 CAS 防止建议单并发重复处置。

**架构：** `OkxOrderResponse` 作为唯一订单写响应解析边界；交易服务、通用能力和控制器都只消费该边界。建议单服务通过 Mapper 条件更新抢占和迁移状态，网络调用在 CAS 之后、数据库事务之外。

**技术栈：** Java 17、Spring Boot、MyBatis-Plus、JUnit 5、Mockito、Vue 3、TypeScript、Vitest、MySQL SQL 迁移脚本。

## 全局约束

- 保持 `OkxClient` 成功时只返回 root `data` 数组。
- 不执行真实 OKX 下单、不录入凭证、不执行数据库迁移、不部署、不删除旧行情表。
- 所有新增文档和代码注释使用中文。
- 未知网络结果不得自动重发，建议单保持 `executing`。

### 任务 1：订单响应解析与交易链路

**文件：** `OkxOrderResponse.java`、`OkxOrderRejectedException.java`、`OkxTradeService.java`、`OkxCapabilityService.java`、`OkxController.java`及其测试。

- 先把测试 Mock 改为客户端真实数组契约，并新增 ordId/sCode/sMsg、空数组、51008 拒单、风险累计和市价 last 测试。
- 运行专项测试确认按旧实现失败。
- 实现解析工具、拒单异常和调用方统一读取。
- 运行专项测试确认通过。

### 任务 2：建议单 CAS 状态机

**文件：** `OkxOrderSuggestionMapper.java`、`OkxSuggestionExecutionService.java`、`OkxSuggestionExecutionServiceTest.java`。

- 先新增 claimNew、完成、回退和拒绝的 CAS 测试，确认旧实现失败。
- 增加 `new → executing`、`executing → executed`、`executing → new`、`new → rejected` 条件更新。
- 明确拒单、已知业务失败和未知运行时异常的迁移策略。
- 运行建议单专项测试确认通过。

### 任务 3：数据库和前端状态

**文件：** `sql/migrations/V16__okx_suggestion_executing.sql`、回滚脚本、`suggestionFormat.ts`、`okx.ts`、`OkxSuggestionsPage.vue`及前端测试。

- 新增可重复执行的 V16 状态约束脚本和回滚脚本，不在本地执行。
- 增加 executing 中文文本、颜色、接口状态类型和按钮隐藏规则。
- 运行前端测试和构建。

### 任务 4：回归验证

按顺序运行专项 Maven 测试、全量后端测试、`npm test`、`npm run build` 和 `git diff --check`，检查工作树及变更内容不含凭证、真实订单参数或迁移执行记录。
