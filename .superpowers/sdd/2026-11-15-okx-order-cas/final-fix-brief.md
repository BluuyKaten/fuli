# 终审修复任务：未知订单响应与顶层 OKX 业务错误

必须先写失败测试并运行红灯，再改生产代码。

1. OkxTradeService：明确非空非 0 sCode 才允许 markSubmitted 为 rejected 并抛 OkxOrderRejectedException；只有 sCode=0 且 ordId 非空才 markSubmitted 为 live 并累计风险；空数组、缺 sCode、sCode=0 缺 ordId 都不得调用 markSubmitted 或累计风险，抛明确异常并保留 pending 待核实。
2. OkxCapabilityService：订单写能力同样拒绝 malformed 响应，不得在空数组/缺 sCode/缺 ordId 时误登记或返回成功；明确非零 sCode 先登记再抛拒单。
3. OkxSuggestionExecutionService：捕获 `OkxClient.OkxApiException` 作为已知交易所业务失败，调用 resetExecuting 后重新抛出；增加测试。
4. 更新相关中文注释和文档状态口径：`OkxSuggestionController.java`、`frontend/src/api/okx.ts`、`docs/api.md`、`docs/okx-接入手册.md`、`docs/开发进度.md` 不再声称失败永远保持 new，也不再写 V15 四态；注明 executing 为待核实，已知失败回 new。
5. 不执行数据库迁移，不真实下单，不录入凭证。

必须运行：相关 Java 专项测试、data-service 全量、frontend npm test/build、git diff --check。提交中文 commit，并追加报告到 task-1-report.md 或新建终审修复报告。
