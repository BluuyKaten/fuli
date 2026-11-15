# 任务 3：V16 状态约束与前端 executing

目标：让数据库约束、前端类型和建议单页面都能解释 `executing` 待核实状态。

必须先改前端测试并运行红灯，再改前端代码；SQL 只做静态文件，不执行：

1. 在 `frontend/src/views/okx/__tests__/suggestionFormat.test.ts` 新增 `executing` 文案为“执行中/待核实”、颜色为 `gold` 的断言。
2. 修改 `suggestionFormat.ts` 状态映射，加入 executing；保持现有 new/executed/rejected/expired 语义不变。
3. 修改 `frontend/src/api/okx.ts` 的建议单状态注释和筛选相关类型，包含 executing。
4. 修改 `OkxSuggestionsPage.vue`：执行中状态显示“执行中/待核实”及对应颜色；executing 不渲染确认/拒绝按钮；可在筛选中单独查看 executing，默认仍为 new；不要把 executing 计入“待确认”或“已执行”。
5. 新增 `sql/migrations/V16__okx_suggestion_executing.sql`：可重复删除旧 CHECK、修改注释并加入 `new/executing/executed/rejected/expired`；不得执行迁移。
6. 新增 `sql/migrations/V16__okx_suggestion_executing_rollback.sql`：先把 executing 转回 new，再恢复 V15 的四态 CHECK；脚本可重复执行并明确回滚风险。

运行：
`npm test`
`npm run build`
`git diff --check`

报告写入 `.superpowers/sdd/2026-11-15-okx-order-cas/task-3-report.md`，提交中文 commit，返回提交号和测试结果。代码注释、SQL 注释、文档使用中文。
