# 交接提示词：阶段 0 · 多市场底座（「复利股票复盘」）

项目根目录 `H:\allCode\fuli`。技术栈：Spring Cloud Alibaba（gateway / auth-service / data-service / trade-service / analysis-service / common-api）+ MySQL 8.4（本机同机，`C:\ProgramData\MySQL\...`，`data_db` / `trade_db`）+ Vue3 + lightweight-charts v5。Git 主干 `main`，**不要 commit，除非用户明确要求**；工作区里已有大量未提交改动，不许动无关文件。

## 0. 你的任务

按 **`docs/multi-market-stage0.md`**（本次评审后已修订的执行手册，**一切以它为准**）从 §3 前置检查开始，完成**阶段 0（0.1～0.9）**：

1. 门禁一：`mysqldump --single-transaction` 完整备份到 `F:\backups\`（§4）。
2. 门禁二：副本库 `data_db_verify` 演练 §6 DDL → §7 instrument 回填 → §8 bar 迁移 → §9 全部校验（V1–V8）**全绿**才碰生产（§5）。
3. 生产：同一窗口内完成迁移 + 部署新代码（§14 时间线，18:00 定时任务之后）。
4. 校验通过后再继续 0.4（`adj_factor`）→ 0.5（repository/service/sparkline/initializer 切 bar）→ 0.6（`/market/bars` + gateway 路由）→ 0.7（缓存 v1 key + `evict` 接到同步后）→ 0.8/0.9（删除死表与陈旧副本、双写观察期）。

**范围已锁定**：从 0.1–0.3 开始，verify 全绿后继续 0.4–0.9；**任何一道门禁红，立即停止并报告**。0.8 的 `DROP`（三死表 + `trade_db.stock_daily_data` / `trade_db.stock_info` 两个陈旧副本）、0.9 停双写与 `DROP stock_daily_data`，必须先把待删除清单列出请用户确认，不得擅自执行。

## 1. 必读（按顺序）

1. `docs/multi-market-stage0.md` ← **执行手册，含全部 SQL、命令、校验、时间线**
2. `docs/adr/0007-unified-bar-store-and-time-axis.md`（已修订：×1000/×100、`pre_close`、`CHECK`、双写回滚）
3. `docs/adr/0006-instrument-identity-and-symbol-scheme.md`（已修订：`exchange`、`lot_size DECIMAL(20,8)`、裸码仅 A 股）
4. `docs/multi-market-rollout.md`（0.1–0.9 清单与验收口径）
5. `GLOSSARY.md`（术语：标的 / 周期 / 原始价 / 复权；禁用 股票代码 / ts_code / secid / 频率 / 粒度）

## 2. 已实测事实（不要重复探测、不要重新论证方向）

- `data_db.stock_daily_data`：**COUNT = 7,663,187 行**，5,596 只，交易日 20050713–20261103；OHLC 无 NULL；`pre_close` 有 32 行 NULL（原样保留）；`trade_date` 全部可解析（`STR_TO_DATE` 校验 0 行）。
- `trade_db` 中 `stock_minute_data` / `stock_weekly_data` / `stock_monthly_data` 均 **0 行**；另有两份**陈旧行情副本**：`trade_db.stock_daily_data`（≈676 万行，与 data_db 不一致）、`trade_db.stock_info`（5,667 行），代码零引用，0.8 备份后删除。
- `stock_info`：5,596 行，`market` 列存的是 Tushare 板块（主板/创业板…），**不是**交易所；`instrument` 回填只认符号后缀。
- `user_watchlist`（auth_db）：7 行，**全是裸 6 位代码**，需补后缀（runbook §10.6）。
- `trade_record` / `position_summary` / `chart_drawing`：均已存完整符号（`002636.SZ` 等）。
- 本机 MySQL 8.4 在跑；`C:` 约 566 GB、`F:` 约 1.3 TB 空闲；datadir 在 `C:\ProgramData\MySQL\MySQL Server 8.4\Data\`。
- 本机**没有 gzip**，有 `tar.exe` / `python.exe` / `node.exe`；dump 用明文 `.sql`，压缩用 `tar -czf`。
- 前端 `src/api` 直接调 `/data/**`（gateway 已有 `/api/data/**` → data-service 路由）；data-service **无认证**（仅内网），Feign 端点返回裸 JSON，`/data/tushare/*` 返回 `Result`，全站 `Result` 包装是主流约定。

## 3. 硬约束（违反 = 静默出错，最严重的是单位）

1. **单位换算只有一处落点**：`TushareSyncServiceImpl.parseDailyData` 出口 + 迁移脚本 §8。A 股 `amount` **×1000**（千元→元），`vol` **×100**（手→股）。**严禁**把原文「÷1000」写回任何地方；严禁历史迁移和日常同步走两套换算。
2. **`SUM` 全表对账是唯一验收**：迁移后 `SUM(amount) == SUM(src.amount)*1000`、`SUM(volume) == SUM(src.vol)*100`，必须**精确相等**（DECIMAL 精确十进制），差一分钱都是红。
3. **`bar.pre_close` 原样搬运**（不换算、不丢）；除权日前收盘 ≠ 前一根收盘；盲测练习除权缺口守护依赖它。`changeAmount`/`pctChg` 不存，适配层用 close−preClose 算。
4. **门禁不可跳过**：无完整备份 → 禁止任何 DDL；verify 未全绿 → 禁止碰生产；生产任何一步红 → 中止、报告、旧链路不动。
5. **同一窗口**：迁移与代码切换在 18:00 定时同步之后、同一次停机内完成；之后手动触发一次增量同步补齐并逐标的 `cache.evict(..., "1d")`。
6. **旧表不许早删**：`current: stock_daily_data` 保留 ≥ 2 周（过渡期双写），0.9 之后才 DROP；`trade_db` 三死表 + 两陈旧副本先备份再删。
7. **裸代码只兼容 A 股 6 位**；美股/加密必须带后缀（`AAPL.US`）——服务端 `StockCodeUtil.resolve/pureCode/marketOf` 是唯一解析器；前端 `toTushareCode/toPureCode` 全部删除（清单见 runbook §10.5）。
8. **以 `isAStock()` 替换方向**：改读 `marketCode`（Feign `info` 新增，来自 `instrument.market`），后缀 `.SH/.SZ/.BJ` 兜底；**禁止六位数字正则**。
9. **不动用户可见功能**：旧端点路径与响应 keys 不变（`tradeDate`=yyyyMMdd、`preClose`、`vol`、`amount`、sparkline `{d,c}`）；`chart_drawing.period`（`1D/1W/1M`）与前端 period 迁移**挪进阶段 1.5**，阶段 0 不碰；GLOSSARY 不改。
10. **缓存**：key 改 `kline:v1:{period}:{symbol}:{adjust}`；`evict(symbol, period)` 删 none/forward 两个 adjust（L1 按前缀 + L2 两键直删）；接到同步任务**提交后**。
11. **`/market/bars`**：落在 data-service；gateway 新路由 `/api/market/**`；返回 `Result`；带 `meta`（priceScale/currency/lotSize/market/name）；`time`：日线类 `'yyyy-MM-dd'`、分钟类 UNIX 秒（UTC）——epoch 不进日线载荷。Python 回测内网直连 `http://data-service:8084/market/bars`。
12. **只动 `data_db` / `trade_db`**。本机还有 `mhms*`、`hmms*` 等其他项目的库，**一律不碰**。
13. **不重新论证方向、不重复探测数据源**（本 shell 可达性不等于部署环境可达性，阶段 3 前才需在部署目标环境复测 Gate/OKX）。EM `secid` 对北交所用 `0` 是现状注释，**勿擅自改为 1**。

## 4. 执行顺序（照 runbook 走，不跳步）

`§3 前置检查 → §4 备份 → §5 data_db_verify 演练（§6 DDL → §7 instrument 回填 → §8 bar 迁移 → §9 校验）→ §10 代码改动（common-api → data-service → trade-service → gateway → frontend）→ §10.6 user_watchlist 补后缀 → §11 /market/bars → §12 缓存 → §13 双写 → §14 生产时间线 → §15 验收 → §16 风险项逐项确认 → 0.4 adj_factor → … → 0.8/0.9（DROP 前报用户）`。

0.1–0.3 完成且生产校验绿之后，每推进一个大项（0.4/0.5/0.6/0.7…）向用户简要汇报一次（做了什么、验证结果、下一项）。发现 runbook 之外的坑，**先报告、再修方案文档**，不要闷头改。

## 5. 验收（对照 runbook §15 逐项；核心三条）

- 迁移前后 OHLC 逐根相等（`adjust=none`）；`bar` 行数 = 7,663,187；V2/V3 `SUM` **精确**相等。
- sparkline 输出逐元素相等（切换前后对比）；盲测练习全流程通过（含既存 session 推进两根）。
- A 股功能全程行为不变（图表 620 根首屏、画线保存回读、最新价、交易下单预览）。

## 6. 交付

- 代码 + `sql/migrations/V7__*.sql`（DDL/回填/迁移，可重入）落地；`docs/` 相关文档若需同步修正可直接改。
- 最终报告：备份文件路径与校验结果、verify 全绿截图/日志摘要、生产校验（V1–V8）逐条结果、双写状态、遗留项（含待用户确认的 DROP 清单）。
- 不 commit / 不 push；等用户指示。
