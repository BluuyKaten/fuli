# OKX 未知响应终审修复报告

日期：2026-11-15

## 修复内容

- `CryptoOrderService.markSubmitted` 只有在 `sCode=0` 且 `ordId` 非空时才登记 `live`；缺少 `sCode` 或成功响应缺少 `ordId` 时抛出明确异常并保留 `pending`。
- `OkxTradeService` 与 `OkxCapabilityService` 在解析订单数组后先校验响应完整性。空数组、缺 `sCode`、`sCode=0` 缺 `ordId` 均不调用 `markSubmitted`、不累计风控；明确非零 `sCode` 才登记 `rejected` 并抛出 `OkxOrderRejectedException`。
- `OkxSuggestionExecutionService` 将 `OkxClient.OkxApiException` 视为已知交易所业务失败，调用 `resetExecuting` 后重新抛出；未知网络异常仍保持 `executing` 待核实。
- 更新 `OkxSuggestionController.java`、`frontend/src/api/okx.ts`、`docs/api.md`、`docs/okx-接入手册.md`、`docs/开发进度.md` 的状态口径，明确 `executing` 是执行中/待核实，已知失败回到 `new`。

## TDD 证据

先增加 malformed 响应和顶层 OKX 业务错误的回归测试，再运行专项测试确认红灯：

```text
Tests run: 60, Failures: 9, Errors: 0
```

失败均对应修复前缺少的行为：能力写接口误报成功、订单域服务修改 malformed 的 pending、交易服务先调用 `markSubmitted`，以及顶层 `OkxApiException` 未回退建议单。

## 验证结果

- `mvn -pl data-service "-Dtest=CryptoOrderServiceTest,OkxTradeServiceWriteAheadTest,OkxCapabilityServiceTest,OkxSuggestionExecutionServiceTest" test`：60/60 通过。
- `mvn -pl data-service test`：258/258 通过。
- 在 `frontend` 目录执行 `npm test`：13 个测试文件、50 个测试通过。
- 在 `frontend` 目录执行 `npm run build`：构建成功；输出仅有既有的 npm/Node 版本提示和 Vite 动态导入分包提示。
- `git diff --check`：通过。

未执行数据库迁移、真实下单或凭证录入。
