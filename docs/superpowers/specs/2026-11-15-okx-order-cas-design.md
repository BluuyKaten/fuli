# OKX 下单结果与建议单并发修复设计

## 目标

修复 OKX 客户端成功响应只返回 `root.data` 数组后，调用方仍按完整 envelope 读取的问题；同时为建议单增加跨实例可用的数据库条件抢占，避免确认和拒绝并发进入交易链路。

## 设计

保留 `OkxClient.privateGet/privatePost` 成功返回 root `data` 数组的契约。新增 `OkxOrderResponse`，统一从数组首项提取 `ordId`、`sCode`、`sMsg`，对 null、空数组和缺字段返回明确的空值。新增 `OkxOrderRejectedException` 表示交易所返回的嵌套拒单。

`OkxTradeService.placeOrder` 在写前日志和网络调用后先登记入口结果；只有 `sCode == "0"` 且 `ordId` 非空才记录风险累计并返回成功。明确拒单先由本地订单落为 rejected，再抛业务异常；网络异常保持写前日志的待核实语义。市价风控从公共接口数组首项读取 `last`。

建议单使用 `new → executing` 的用户归属条件更新抢占。只有抢占成功的请求调用交易所；成功时 `executing → executed`，已知参数、权限、风控或交易所拒单时 `executing → new`，未知网络结果保持 `executing`。拒绝操作使用 `new → rejected` 条件更新。数据库更新不包住网络调用。

新增可重复执行的 V16 迁移及回滚脚本，允许 `executing`。前端展示“执行中/待核实”，执行中不显示处置按钮。只修改 OKX 交易链路，不执行真实下单、凭证录入、迁移、部署或旧表删除。

## 验证

先运行数组契约、拒单、风险累计和 CAS 失败测试确认红灯，再实现。完成后运行专项 Maven 测试、全量后端测试、前端测试、前端构建和 `git diff --check`。
