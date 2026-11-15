# 任务 3：V16 状态约束与前端 executing 报告

## 完成内容

- 在 `suggestionFormat` 测试中先增加 `executing` 的文案与颜色断言，确认红灯后实现映射：文案为“执行中/待核实”，颜色为 `gold`。
- 更新 `frontend/src/api/okx.ts`：新增 `OkxSuggestionStatus` 五态联合类型与 `OkxSuggestionFilter` 筛选类型，并补充 `executing` 状态注释及响应类型。
- 更新建议单页面：增加“执行中/待核实”筛选项，默认筛选仍为“待确认”；执行中记录不显示确认/拒绝按钮，待确认与已执行统计不计入执行中记录。
- 新增 `V16__okx_suggestion_executing.sql`，可重复删除旧 CHECK、更新字段注释并建立 `new/executing/executed/rejected/expired` 五态约束。
- 新增 `V16__okx_suggestion_executing_rollback.sql`，先将 `executing` 回退为 `new`，再恢复 V15 四态约束，并在注释中明确回滚风险。
- SQL 文件仅静态新增，未连接或执行数据库。

## TDD 记录

先修改 `frontend/src/views/okx/__tests__/suggestionFormat.test.ts`，首次运行目标测试时得到 2 个预期失败：`statusText('executing')` 返回原始状态，`statusColor('executing')` 返回 `default`。随后实现最小状态映射，目标测试转为通过。

## 验证结果

在 `frontend` 目录执行：

```text
npm test
```

结果：13 个测试文件、50 个测试全部通过。

```text
npm run build
```

结果：`vue-tsc -b && vite build` 成功；Vite 仅输出仓库原有的动态/静态导入拆包提示。

```text
git diff --check
```

结果：通过，未发现差异格式错误。

## 说明

- 构建前工作树没有 `frontend/node_modules`，先按 `package-lock.json` 执行 `npm ci` 后再完成测试与构建。
- 未修改 Java 文件或 `OkxClient`。
