# 阶段 0 执行手册 · 标的主表与 `bar` 切换（0.1～0.3，含 0.4–0.9 收尾）

前置阅读：`docs/adr/0006-instrument-identity-and-symbol-scheme.md`、`docs/adr/0007-unified-bar-store-and-time-axis.md`（均已修订）。术语见 `GLOSSARY.md`。

本文件是**可直接照抄执行的操作手册**：备份 → 副本库演练 → 生产切换 → 校验 → 回滚。原方案在以下几处有事实错误，执行时按本手册为准。

---

## 0. 复审结论：原方案的 10 处修正（均已落回 ADR / 阶段文档）

| # | 发现 | 修正 |
|---|---|---|
| 1 | `amount` 换算写反成「÷1000」 | Tushare `amount` 千元 → 元 **×1000**；漏掉 `vol` 手 → 股 **×100**。唯一可靠验收是 `SUM` 全表对账（比率必须精确 1000 / 100） |
| 2 | `bar` 没有 `pre_close` | 除权除息日前收盘 ≠ 前一根收盘（Tushare 给除权参考价），**无法从 OHLC 推算**。盲测练习的除权缺口守护、旧接口 `preClose` 字段都依赖它。已补入 `bar`（原样搬运，32 行 NULL 保留） |
| 3 | `period` 仅靠受控集合防 `1M`/`1m` 撞主键 | 再加 `CHECK (period IN (...))` 兜底，绕过应用写入也写不进非法周期 |
| 4 | `instrument` 缺 `exchange`；`lot_size` 若用整数则加密无法表达 0.0001 | 补 `exchange VARCHAR(16)`（仅 US 必填：NASDAQ/NYSE/AMEX，东财 `secid` 前缀用）；`lot_size` / `tick_size` 用 `DECIMAL(20,8)` |
| 5 | 备份/副本库是执行前提，原文只一笔带过 | 明确为**步骤门禁**：mysqldump → `data_db_verify` 全量校验全绿才碰生产 |
| 6 | 「`KlineQueryRepository` 改查 `bar`」漏掉 sparkline、`latest-prices`、`StockDataInitializer`、`/data/stock/info` 的 `market` 字段 | 全部纳入切换清单（§10.2）；漏掉 sparkline 会导致 K 线图缩略条迁移后静默空白 |
| 7 | 回滚口径不清 | 明确为：**旧表保留 + 过渡期双写 ≥2 周**，期满再 `DROP`；迁移与代码切换**同一窗口**（18:00 定时任务会写日线） |
| 8 | `/market/bars` 未定义响应细节 | 新契约必须带 `meta`（priceScale/currency/lotSize/market/name），否则前端无从取价格精度；`time`：日线及以上输出 `'yyyy-MM-dd'`（lightweight-charts business day），分钟类输出 UNIX 秒（UTC）——epoch 不外泄给前端（ADR-0007） |
| 9 | 缓存 key 未加版本、`evict` 接线点不明 | key 改为 `kline:v1:{period}:{symbol}:{adjust}`；`evict(symbol, period)` 删除 none/forward 两个 adjust（L1 按前缀删、L2 两键直删，无需 SCAN）；接线点 = Tushare 日线 upsert 完成后 |
| 10 | `trade_db` 还有两张行情陈旧副本未提及 | `trade_db.stock_daily_data`（约 676 万行，与 `data_db` 不一致）、`trade_db.stock_info`（5667 行），代码无引用 → 0.8 一并删除（先备份） |

另：

- **`chart_drawing.period` 存量值（`1D/1W/1M/60`…）与新受控集合不一致**。阶段 0 不动；随**阶段 1.5 前端周期下发**一起做数据迁移（`UPDATE chart_drawing SET period='1d' WHERE period='1D'` 等，存量仅 2 行），避免画线丢失。
- **`user_watchlist` 存量 7 行**全是裸 6 位代码。0.2 里顺手迁移补后缀（§10.6），否则新存 `600519.SH` 与旧存 `600519` 并存、自选去重失效。
- **Python 回测取 bar**：内网直连 `data-service:8084/market/bars`（data-service 无认证、仅限内网，与现有 Feign 现状一致）；`POST /strategy/fills` 的 HMAC 可直接复用现成的 `X-Internal-Key`（trade-service 已有 `/trade/internal/*` 模式），不必另发明签名方案。
- **裸代码只兼容 A 股 6 位**；美股/加密必须带后缀（`AAPL.US`），服务端不做代码猜测。

---

## 1. 目标 / 非目标

**目标（本手册覆盖）**

- 0.1 `instrument` 建表 + A 股回填；0.2 `StockCodeUtil` 收敛 + 前端推断函数删除；0.3 `bar` 建表 + A 股 7,663,187 行迁入；并附带 0.4（`adj_factor`）、0.5–0.9 的收尾与切换清单。

**非目标（本手册不碰）**

- 阶段 1（美股适配器/自选清单/美股日线同步/前端服务端下发周期）、阶段 2（加密回测）、阶段 3/4（实盘）。
- 盲测练习规则（`A_SHARE_DAILY_V1`、T+1、佣金印花税）——原样保留，仅随 §10 切换数据源。
- GLOSSARY、既有 API 路径（旧四端点路径不变，仅内部转调）。
- 前端 period 值迁移（`1D→1d` 等）——随阶段 1.5，见 §17.1。

---

## 2. 影响面清单（文件级）

**新增**

```
sql/migrations/V7__instrument_bar_adj_factor.sql   -- §6 DDL（不含历史数据迁移，迁移单独脚本）
sql/migrations/V7__migrate_a_share_bars.sql        -- §7/§8 回填 + 迁移（可重入）
sql/migrations/V7__backfill_adj_factor.md          -- adj_factor 回填步骤说明（按 trade_date 拉全市场）
sql/migrations/V7__verify_bars.sql                 -- §9 校验 V0–V8（PASS/FAIL，可反复跑）
sql/migrations/V7__verify_adj_factor.sql           -- 0.4 验收 A1–A7（含已知例外 A4b/A4c）
scripts/backfill_adj_factor.py                     -- 主回填：按交易日拉全市场，断点续跑
scripts/backfill_adj_factor_by_code.py             -- 补洞：按标的拉全历史（bar 有行但无因子）
scripts/backfill_adj_factor_bse_alias.py           -- 北交所新旧代码对照取数（当前源端无解，留作记录）
data-service/.../entity/{Instrument,Bar,AdjFactor}.java
data-service/.../mapper/{InstrumentMapper,BarMapper,AdjFactorMapper}.java
data-service/.../controller/MarketBarsController.java
data-service/.../service/MarketBarsService.java
```

**修改**

```
common-api  StockCodeUtil.java                        -- 收敛为 resolve/pureCode/marketOf
data-service StockDataController.java /sparkline      -- 改查 bar
             KlineQueryRepository.java / KlineQueryService.java
             DailyKlineCache.java                      -- key 加 v1 + period + adjust；evict 新签名
             TushareSyncServiceImpl.java / upsert      -- 写 bar（×1000/×100）+ 双写期兼容 + evict
             StockDataInitializer.java                 -- 种子改写 bar（可选但必须做，否则 dev 环境坏）
             /data/stock/info(s)                       -- 响应增加 marketCode（instrument.market）
trade-service KlineController.java                    -- 删 getSuffix()，统一走 StockCodeUtil
              EastMoneyDataSource.java                 -- secid 推导保留但收口到 util 的 pureCode
              StockServiceImpl#isAStock                -- 读 Feign info.marketCode，后缀兜底
gateway-service GatewayRouteConfig.java               -- 新增 /api/market/** → data-service
frontend       src/utils/stockCode.ts                  -- 整文件删除
               src/views/stock/KLinePage.vue           -- 不再 toPureCode 剥离
               src/views/stock/LightweightChart.vue    -- 直接用 props.stockCode；parseTime 留待阶段 1.5
               src/views/stock/{TradePanel,WatchlistPanel}.vue
               src/views/trade/{TradeForm,TradeList}.vue
auth_db        user_watchlist 存量补后缀               -- §10.6
trade_db       DROP 三死表 + 两张陈旧副本              -- §10.7（先备份）
```

**删除（阶段 0 末尾或 0.9 统一删）**

- `stock_minute_data` / `stock_weekly_data` / `stock_monthly_data`（0 行）及其 DDL 留档。
- 观察期满：`stock_daily_data`、`StockDailyData`、`StockDailyDataMapper`、双写兼容分支。

---

## 3. 前置检查

```powershell
# 1) MySQL 8.4 在跑，且是本机同机部署
mysql -uroot -p123456 -e "SELECT VERSION();"

# 2) 磁盘余量：datadir 在 C:（约 566 GB 可用）；bar+adj_factor 预计新增 < 2 GB

# 3) 源表规模（实测 2026-11-04）
mysql -uroot -p123456 -e "
SELECT table_name, table_rows FROM information_schema.tables
WHERE table_schema='data_db' AND table_name IN ('stock_daily_data','stock_info');"
# stock_daily_data  COUNT(*) = 7,663,187（information_schema 估算值不准，以 COUNT 为准）
# stock_info         5,596 行

# 4) 源表完整性：trade_date 全部可解析（实测 bad=0，范围 20050713~20261103）
mysql -uroot -p123456 -e "
SELECT COUNT(*) bad FROM data_db.stock_daily_data
WHERE STR_TO_DATE(CONCAT(trade_date,'093000'), '%Y%m%d%H%i%s') IS NULL
   OR trade_date NOT BETWEEN 19000101 AND 21001231;"   # 必须 0
# 5) OHLC 无 NULL（实测 open/high/low/close 全非空；pre_close 有 32 行 NULL，需原样保留）
# 6) 停掉 18:00 定时同步对窗口的干扰：先确认当前时刻，18:00 之后执行见 §14
```

⚠️ 全程只动 `data_db` / `trade_db`；本机还有其他项目的库（`mhms`、`hmms*`…），**不得触碰**。

---

## 4. 完整备份（门禁一）

```powershell
# F: 空闲 1.3 TB；明文约 2–3 GB（如需压缩：tar -czf 见下）
mysqldump -uroot -p123456 --single-transaction --quick --databases data_db trade_db > F:\backups\fuli-pre-bar-20261104.sql
# 可选压缩归档（bsdtar 自带 gzip 支持）
tar -czf F:\backups\fuli-pre-bar-20261104.tar.gz -C F:\backups fuli-pre-bar-20261104.sql
```

- `--single-transaction`：一致性快照，备份期间不影响读写。
- 备份文件不进仓库（`.gitignore` 已含 `*.log` 等；如需归档放 F: 自行留存）。
- **不要把整包备份直接导回本机**：dump 带 `USE data_db` / `USE trade_db`，直接 source 会覆盖生产表。要验证备份，请恢复到另一实例，或按 §5 用**不带 `--databases` 的单库 dump** 导入 scratch 库（dump 中没有 `USE` 语句，导入位置由命令行 `--database` 决定）。

---

## 5. 副本库演练 `data_db_verify`（门禁二）

```powershell
mysql -uroot -p123456 -e "DROP DATABASE IF EXISTS data_db_verify; CREATE DATABASE data_db_verify DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
# 只导出 data_db：不带 --databases 时 dump 里没有 `USE` 语句，导入到哪个库由命令行决定
mysqldump -uroot -p123456 --single-transaction --quick data_db > F:\backups\fuli-data_db-only.sql
mysql -uroot -p123456 data_db_verify < F:\backups\fuli-data_db-only.sql
```

在 `data_db_verify` 上依次执行 **§6 DDL → §7 回填 → §8 迁移 → §9 全部校验**。全绿后：

```sql
DROP DATABASE data_db_verify;
```

未在 verify 全绿之前，**不对生产执行任何 DDL**。

---

## 6. DDL：`sql/migrations/V7__instrument_bar_adj_factor.sql`

```sql
USE `data_db`;

-- ---------- 标的主表 ----------
CREATE TABLE IF NOT EXISTS `instrument` (
  `code`        VARCHAR(20)   NOT NULL COMMENT '标的代码，如 600519.SH / AAPL.US / BTCUSDT.CRYPTO',
  `market`      VARCHAR(8)    NOT NULL COMMENT 'SH/SZ/BJ/US/CRYPTO',
  `name`        VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '名称',
  `currency`    VARCHAR(8)    NOT NULL COMMENT '计价币种：CNY/USD/USDT…',
  `price_scale` INT           NOT NULL COMMENT '小数位（东财 decimal 字段可直接填），前端显示精度',
  `lot_size`    DECIMAL(20,8) NOT NULL COMMENT '交易单位：A股100，美股1，加密小数（如 0.0001）',
  `tick_size`   DECIMAL(20,8) NOT NULL COMMENT '最小变动价位',
  `tz`          VARCHAR(32)   NOT NULL COMMENT 'IANA 时区，如 Asia/Shanghai / America/New_York / UTC',
  `session`     VARCHAR(16)   NOT NULL COMMENT 'DAY / 24H',
  `status`      TINYINT       NOT NULL DEFAULT 1 COMMENT '1 正常 0 停牌/摘牌',
  `exchange`    VARCHAR(16)   NULL COMMENT 'NASDAQ/NYSE/AMEX（US 必填）；A股/加密 可空',
  `create_time` DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`code`),
  KEY `idx_instrument_market` (`market`),
  CONSTRAINT `chk_instrument_market` CHECK (`market` IN ('SH','SZ','BJ','US','CRYPTO'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='标的主表';

-- ---------- 行情 K 线（原始价） ----------
CREATE TABLE IF NOT EXISTS `bar` (
  `instrument_code` VARCHAR(20)   NOT NULL COMMENT '标的代码',
  `period`          VARCHAR(4)    NOT NULL COMMENT '1m/5m/15m/30m/1h/4h/1d/1w/1mo',
  `ts`              BIGINT        NOT NULL COMMENT 'bar 开盘时刻，UNIX 秒，UTC',
  `open_price`      DECIMAL(20,8) NULL,
  `high_price`      DECIMAL(20,8) NULL,
  `low_price`       DECIMAL(20,8) NULL,
  `close_price`     DECIMAL(20,8) NULL,
  `pre_close`       DECIMAL(20,8) NULL COMMENT '前收盘（除权口径，原样搬运，不换算）',
  `volume`          DECIMAL(28,8) NULL COMMENT '股/币',
  `amount`          DECIMAL(28,8) NULL COMMENT '计价货币元',
  PRIMARY KEY (`instrument_code`, `period`, `ts`),
  CONSTRAINT `chk_bar_period` CHECK (`period` IN ('1m','5m','15m','30m','1h','4h','1d','1w','1mo'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='行情 K 线';

-- ---------- 复权因子（每交易日一行，全量存储） ----------
CREATE TABLE IF NOT EXISTS `adj_factor` (
  `instrument_code` VARCHAR(20)   NOT NULL,
  `ts`              BIGINT        NOT NULL COMMENT '与 1d bar 同口径：交易日 01:30 UTC（A股）/ 00:00 UTC（加密）',
  `factor`          DECIMAL(20,8) NOT NULL COMMENT 'Tushare 累积复权因子，恒 >0',
  PRIMARY KEY (`instrument_code`, `ts`),
  CONSTRAINT `chk_adj_factor_positive` CHECK (`factor` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='复权因子';
```

说明：

- **不建外键**：`bar.instrument_code → instrument.code` 一致性由应用层保证（与现状一致、避免导入开销）；instrument 缺失会在同步任务中显式报错，不会静默脏写。
- **不分表、不分区**：7.66M 行 + 索引约 1.5 GB，InnoDB 无压力；按周期分表已被 ADR-0007 否决。
- `period` 只有 `1mo`，没有 `1M`；周期大小写敏感问题靠 `CHECK` 兜底。
- `adj_factor` 按全量存储（实测 A 股 **16,818,420 行 / 5,179 天 / 5,889 个代码，约 950 MB**；行数比 `bar` 多是因为因子按「当日全部在市标的」落库，因子是 bar 的超集）；前复权公式 `price × factor / factor_latest`，其中 `factor_latest` = 窗口末端最新一根 bar 的因子；加密无因子，`adjust=forward` 等价于 `none`；美股因子为派生数据（fqt=0/fqt=1 之比），阶段 1 落地，可随时重建。
  > **两处实测修正**（详见 `sql/migrations/V7__backfill_adj_factor.md`）：
  > ① `AdjFactorMapper.selectRange` 的 `toTs` 是开区间，取 `factor_latest` 必须传 `lastTs + 1`，
  > 否则末端 bar 被排除、窗口止于除权日时前段全不缩放（不报错的静默错误，已有 `ForwardAdjustTest` 护栏）；
  > ② 北交所 920 号段 2023 年前的 711 行 bar 无因子可用（Tushare 源端对新旧代码都不供数），
  > `adjust=forward` 在该段退化为原价，属**已知例外**，验收脚本 A4c 单独门禁。

---

## 7. 回填 `instrument`（A 股，来自 `stock_info`）

```sql
-- sql/migrations/V7__backfill_instrument_a_share.sql（可重入：ON DUPLICATE KEY UPDATE）
INSERT INTO `instrument` (`code`, `market`, `name`, `currency`, `price_scale`, `lot_size`,
                          `tick_size`, `tz`, `session`, `status`, `exchange`)
SELECT `stock_code`,
       SUBSTRING_INDEX(`stock_code`, '.', -1)          AS market,
       COALESCE(`stock_name`, '')                     AS name,
       'CNY'                                          AS currency,
       2                                              AS price_scale,
       100                                            AS lot_size,
       0.01                                           AS tick_size,
       'Asia/Shanghai'                                AS tz,
       'DAY'                                          AS session,
       IFNULL(`status`, 1)                            AS status,
       NULL                                           AS exchange
FROM `stock_info`
WHERE `stock_code` REGEXP '^[0-9]{6}\\.(SH|SZ|BJ)$'
ON DUPLICATE KEY UPDATE
  `market`=VALUES(`market`), `name`=VALUES(`name`), `status`=VALUES(`status`),
  `update_time`=NOW();
SELECT COUNT(*) FROM `instrument`;   -- 期望 5,596
```

注意：

- `stock_info.market` 里是 Tushare 板块（主板/创业板…），**不是**交易所——本回填不读取它；市场后缀来自符号本身。
- 美股/加密 instrument 在阶段 1/2 录入（含 `exchange`、`price_scale` 取自东财 `decimal` 字段）。

---

## 8. `bar` 迁移（`sql/migrations/V7__migrate_a_share_bars.sql`）

```sql
-- 可重入：已迁移的行会被 ON DUPLICATE KEY UPDATE 覆盖为同源值（幂等）
--
-- ⚠️ 必须先固定会话时区：UNIX_TIMESTAMP() 按「会话时区」解释入参。
-- 本机 MySQL 默认 time_zone=SYSTEM（+08:00），若直接跑下面这条，
-- '2026-11-03 09:30' 会被当成北京时间 → 已经是 01:30 UTC → 再减 28800 就早了一天。
-- 更隐蔽的是：用 FROM_UNIXTIME 在同一时区回看，仍然显示 09:30，肉眼完全看不出错。
SET SESSION time_zone = '+00:00';

INSERT INTO `bar` (`instrument_code`, `period`, `ts`,
                   `open_price`, `high_price`, `low_price`, `close_price`,
                   `pre_close`, `volume`, `amount`)
SELECT `stock_code`, '1d',
       UNIX_TIMESTAMP(STR_TO_DATE(CONCAT(`trade_date`, ' 093000'), '%Y%m%d %H%i%s')) - 28800,
       `open_price`, `high_price`, `low_price`, `close_price`,
       `pre_close`,
       `vol` * 100,          -- 手 → 股
       `amount` * 1000       -- 千元 → 元
FROM `stock_daily_data`
ON DUPLICATE KEY UPDATE
  `open_price`=VALUES(`open_price`), `high_price`=VALUES(`high_price`),
  `low_price`=VALUES(`low_price`),   `close_price`=VALUES(`close_price`),
  `pre_close`=VALUES(`pre_close`),
  `volume`=VALUES(`volume`),         `amount`=VALUES(`amount`);
```

- `trade_date`(INT) → 交易日 01:30 UTC：`STR_TO_DATE` 按 `%Y%m%d` 解析后减 28800 秒（中国 1991 年后无夏令时，2005 年以后数据无影响）。
- **必须配合上面的 `SET SESSION time_zone='+00:00'`**。等价写法是 `TO_DAYS(...)` 直接算（与时区无关），但既然 §9 校验也用 UTC 会话，统一设 UTC 更不容易错。
- `vol`/`amount` 是精确十进制乘法，无浮点误差；与源表行数一致。
- 7.66M 行单条 `INSERT ... SELECT`，预计 1–5 分钟；`bar` 是新表，不锁旧表、不影响在线读写。
- **迁移完成后不要立即删 `stock_daily_data`**（见 §13 双写回滚）。

---

## 9. 校验（全部必须通过，跑在 verify，绿了再在生产跑一遍）

> **执行版脚本**：`sql/migrations/V7__verify_bars.sql`（V0–V8 逐条带 PASS/FAIL，可反复跑）。
> 下面保留手册原文，脚本在其基础上做了四处工程修正，均已实测：
>
> 1. **V5 必须 `FORCE INDEX(uk_code_trade_date)`**。默认计划会按 `idx_trade_date` 驱动（按日期取全市场），
>    730 万次主键探测在 5,596 个标的之间来回跳，实测 7 分钟仍未结束；强制按 `(stock_code, trade_date)`
>    顺序驱动后，探测变成「同一标的内连续」，**41 秒**跑完。
> 2. **V4 不能用 `IN (子查询带 LIMIT)`**：MySQL 8.4 直接报 `ERROR 1235`。用派生表 `JOIN` 代替。
> 3. **每个指标只算一次**。原文把同一个聚合在「显示列」和「PASS/FAIL 列」各写一遍，
>    V2/V3/V5 因此跑两趟，整体耗时翻倍。
> 4. **V2 的成交额对账不能写 `SUM(bar.amount) = SUM(src.amount) × 1000`**（2026-11-05 实测暴露）。
>    `bar` 存 Tushare 原始精度（千元 5 位小数 ×1000，单位元），旧表 `amount` 是 `DECIMAL(20,4)`，
>    把千元截到 4 位（颗粒度 0.1 元）。迁移行是 SQL 直接 ×1000 能精确相等，但**每日同步行走 Java**，
>    保留更高精度，SUM 于是差几十元（11-05 差 **10.47 元 / 5,571 行**，逐行差异恒在 ±0.05 内）。
>    这是 bar 更精确，不是换算错了；**不能为凑等式去砍精度**。正确不变量是
>    `ROUND(bar.amount/1000, 4) = src.amount` 逐行相等，聚合用
>    `SUM(ROUND(bar.amount/1000,4)) = SUM(src.amount)`，脚本里另加 V2b 做 20 只逐行抽检。
>
> 另两条：
> - V1 的期望值不要写死 7,663,187。源表每天 18:00 后都会 +1 个交易日（实测 2026-11-05 已是 **7,674,330**），判断依据只能是 `src = dst`。
> - 脚本头要 `SET NAMES utf8mb4`：Windows 控制台默认 gbk，V8 含中文常量，不统一会报 `Illegal mix of collations`。
> - **多市场作用域**（2026-11-07 OKX 接入后发现）：`bar` 现在同时装 A 股与加密，
>   V1/V2/V3/V6/V7 这些全表聚合必须限定 `instrument_code REGEXP '^[0-9]{6}\\.(SH|SZ|BJ)$'`，
>   否则加密的 00:00 UTC 时间轴、USDT 金额、恒 NULL 的 pre_close 会让五项一起假红。
>   同一处理也落在 `V7__verify_adj_factor.sql` 的 A4/A4b/A4c。每接一个新市场都要复查这两份脚本。

```sql
SET SESSION time_zone = '+00:00';

-- [V1] 行数一致
SELECT (SELECT COUNT(*) FROM stock_daily_data) AS src,
       (SELECT COUNT(*) FROM bar WHERE period='1d') AS dst;          -- 必须相等（7,663,187）

-- [V2] 成交额全表对账：必须精确相等
SELECT ROUND(SUM(amount), 4) * 1000 AS src_amount,
       (SELECT ROUND(SUM(amount), 4) FROM bar) AS dst_amount;        -- 必须相等

-- [V3] 成交量全表对账：必须精确相等
SELECT ROUND(SUM(vol), 4) * 100 AS src_vol,
       (SELECT ROUND(SUM(volume), 4) FROM bar) AS dst_volume;        -- 必须相等

-- [V4] OHLC + pre_close 抽样逐行比对（随机 20 只，全历史）——必须 0 行
SET @sample := '600519.SH,000001.SZ,300750.SZ,688981.SH,920000.BJ';  -- 自行补足 20 只
SELECT COUNT(*) AS mismatch FROM (
  SELECT s.stock_code c, s.trade_date d,
         UNIX_TIMESTAMP(STR_TO_DATE(CONCAT(s.trade_date,' 093000'), '%Y%m%d %H%i%s')) - 28800 AS ts,
         s.open_price o, s.high_price h, s.low_price l, s.close_price cl, s.pre_close pc
  FROM stock_daily_data s
  WHERE FIND_IN_SET(s.stock_code, @sample)
) src
JOIN bar b ON b.instrument_code = src.c AND b.period='1d' AND b.ts = src.ts
WHERE NOT (b.open_price  <=> src.o AND b.high_price <=> src.h
       AND b.low_price   <=> src.l AND b.close_price <=> src.cl
       AND b.pre_close   <=> src.pc);                                  -- 必须 0

-- [V5] 缺行检测：源表有的 bar 没有（如停牌日本来就没有源行，故此处直接对比全集）——必须 0 行
SELECT COUNT(*) AS missing FROM stock_daily_data s
WHERE NOT EXISTS (
  SELECT 1 FROM bar b
  WHERE b.instrument_code = s.stock_code AND b.period='1d'
    AND b.ts = UNIX_TIMESTAMP(STR_TO_DATE(CONCAT(s.trade_date,' 093000'), '%Y%m%d %H%i%s')) - 28800);

-- [V6] 时间范围：必须是 2005-07-13 01:30 UTC ~ 2026-11-03 01:30 UTC
SELECT FROM_UNIXTIME(MIN(ts), '%Y-%m-%d %H:%i:%s') AS first_bar,
       FROM_UNIXTIME(MAX(ts), '%Y-%m-%d %H:%i:%s') AS last_bar
FROM bar WHERE period='1d';

-- [V7] NULL 率一致（pre_close 源 32 行 NULL，迁移后 bar 同样 32 行 NULL）
SELECT (SELECT COUNT(*) FROM stock_daily_data WHERE pre_close IS NULL) AS src_null_pc,
       (SELECT COUNT(*) FROM bar WHERE pre_close IS NULL)               AS dst_null_pc;   -- 相等

-- [V8] 抽样单根比价（人工核对茅台 2020-08-31、宁德时代 IPO 首日等关键日）
SELECT instrument_code, FROM_UNIXTIME(ts, '%Y-%m-%d') d, open_price, close_price, pre_close
FROM bar WHERE instrument_code='600519.SH' ORDER BY ts DESC LIMIT 5;
```

**应用层校验**（verify 库 + 新代码 jar）

- 部署后调 `GET /api/data/stock/daily?stockCode=600519.SH&limit=5`，与切换前响应逐字段比对（`tradeDate`/`openPrice`/`closePrice`/`preClose`/`vol`；`vol` 会变为原来的 100 倍——这是**预期**）。
- sparkline 一致：切换前后各调 `/api/data/stock/daily/sparkline?stockCode=600519.SH&points=240`，输出数组逐元素相等（LTTB 在同一输入上结果确定）。
- 盲测练习：`mvn -pl trade-service -am test -Dtest='Practice*Test'` 全绿 + 手动建一个练习、推进两根日线。
- 前端冒烟：K 线首屏 620 根渲染、成交量柱、划线保存/回读、练习页时间轴。

---

## 10. 代码改动清单

### 10.1 common-api：`StockCodeUtil` 收敛为唯一解析器

新增 API（保留 `withSuffix` 一个版本周期后删除，或直接替换所有调用点——本仓库内调用点仅 4 处，建议直接删）：

```java
public final class StockCodeUtil {
    /** 解析输入为规范符号：完整符号原样（校验市场后缀）；裸 6 位仅按 A 股规则推断；其余 IllegalArgumentException。 */
    public static String resolve(String input) { ... }
    /** 去掉后缀，仅按字符串操作（不推断）。 */
    public static String pureCode(String symbol) { ... }
    /** SH/SZ/BJ/US/CRYPTO；非法符号抛异常。 */
    public static String marketOf(String symbol) { ... }
}
```

规则：

1. `null`/空白 → 异常；trim + 大写。
2. 含 `.`：必须 exactly 两段、市场 ∈ {SH,SZ,BJ,US,CRYPTO}、代码段非空且 ≤ 15 字符（`BTCUSDT` 7 位，留余量）→ 返回原样。
3. 不含 `.`：必须是 6 位纯数字 → A 股推断（**与现实现一致**：920/43/83/87/4/8 开头 → BJ；60/68/900 开头 → SH；00/20/30 开头 → SZ；其余 → 异常。注意当前实现把 `4`/`8` 全部归 BJ、`00/20/30` 归 SZ，维持不变）。
4. 其他（如 `AAPL`、`BTCUSDT`）→ 异常并提示「美股/加密必须带市场后缀」。

所有推断只此一份；删除/替换四处分裂实现（`StockCodeUtil.withSuffix`、`frontend/src/utils/stockCode.ts`、`KlineController#getSuffix`、`EastMoneyDataSource` 的首位判断——EM 的 `secid` 前缀映射是**适配器内部协议**，保留但收口为调 `pureCode` + 内部 switch，并注明「EM 对北交所用 `0` 前缀，阶段 1 实测校准，勿擅自改为 1」）。

### 10.2 data-service

| 文件 | 改动 |
|---|---|
| `entity/Bar.java` 等 3 个新实体 + Mapper | `BarMapper.upsert`：`INSERT ... ON DUPLICATE KEY UPDATE`，参数**已是股/元口径**（单一换算落点在 `TushareSyncServiceImpl.parseDailyData`）；`selectCloseSeries` 改查 `bar`（`SELECT ts, close_price ... ORDER BY ts`）|
| `KlineQueryRepository` | 查 `bar`：`selectRecent` 降序取再反转（语义不变）；`selectRange(symbol, fromTs, toTs, limit)`；`from/to` 由调用方按 Asia/Shanghai 自然日换算成 `[00:00, 24:00)` 的 UTC 秒闭开区间 |
| `KlineQueryService` | 返回值 `List<Bar>`；`DEFAULT_LIMIT=620`、`MAX_LIMIT=5000` 不变；有区间 → 区间内最近 `limit` 根（与原语义一致），升序返回 |
| `StockDataController`（Feign 旧端点） | 查询改走 repository；响应 keys **不变**：`tradeDate`=`yyyyMMdd`（`LocalDate.ofInstant(ts, Asia/Shanghai)` 格式化）、`openPrice/highPrice/lowPrice/closePrice/preClose`、`vol`（现在是股，前端量柱自动 100×，正常）、`amount`（现在是元）；`changeAmount`/`pctChg` 由 close−preClose 计算（4 位小数 HALF_UP）。裸码兼容逻辑保留（`withSuffixIfBare` → `StockCodeUtil.resolve` 兜底） |
| `/data/stock/info(s)` | 响应**新增** `marketCode`（`instrument.market`）；旧 `market`（板块）原样保留 |
| `StockDataController / sparkline` | LTTB 逻辑不动，输入改从 `BarMapper.selectCloseSeries` 取；剔除 `close_price IS NULL` 行 |
| `StockDataInitializer` | dev 种子改写 `instrument` + `bar`（先查后写，幂等），否则本地启动初始化会插进不存在的地方 |
| `TushareSyncServiceImpl` | `parseDailyData` 出口处做单位换算：`vol×100`、`amount×1000`，`pre_close` 原样；upsert 改写 `bar`；**过渡期双写**：同一事务内再 upsert 旧表 `stock_daily_data`（反向换算 ÷100、÷1000，`trade_date` 由 ts 在 Asia/Shanghai 取日期）；全部受影响标的 `cache.evict(symbol, "1d")` |
| `InstrumentSync`（可并入 `syncStockBasic`） | 每周 stock_basic 同步后，按 `stock_info` upsert `instrument`（覆盖 name/status）；只加这一段，不动 Tushare 调用 |
| `KlineQueryService#query` / sparkline | 裸码兼容：先 `resolve`，非法 → 400 |
| `MarketBarsService` / `MarketBarsController` | 见 §11 |
| `DailyKlineCache` | 见 §12 |

### 10.3 trade-service

| 文件 | 改动 |
|---|---|
| `controller/KlineController` | 删除私有 `getSuffix()`；`/stock/minute|weekly|monthly` 用 `StockCodeUtil.pureCode(code)` 传给路由 service（EM 要纯代码）；`/stock/quote` 的 `tushareCode` 拼接改 `resolve(code)` |
| `datasource/EastMoneyDataSource` | `getMarketPrefix` 仅服务 EM `secid`：SH→1、SZ→0、BJ→0（EM 现状，注释说明）、US→`105`/`106`（阶段 1 按 `instrument.exchange` 选）、CRYPTO 不适用（EM 加密 rc:100，不可用）；其余代码删除 |
| `service/StockDataRouterService` | 分钟/周/月代理逻辑不变（仍东财实时抓，不落库）；空实现 → 保持现状 |
| `service/impl/StockServiceImpl#isAStock` | 改为：`marketCode`（Feign info 返回）∈ {SH,SZ,BJ} → true；`marketCode` 缺失时按符号后缀 `.SH/.SZ/.BJ` 判断；**不再匹配六位数字正则**。其余调用点不变 |

### 10.4 gateway-service

`GatewayRouteConfig` 增加一条（复用 `fuli.routes.data`，本地直连、生产 lb）：

```java
.route("market", r -> r.path("/api/market/**")
        .filters(f -> f.stripPrefix(1))
        .uri(environment.getProperty("fuli.routes.data", "lb://data-service")))
```

`/api/data/**` 保持不变（同步管理页继续用）；补 `GatewayRouteConfigTest` 期望路由断言。

### 10.5 frontend（删除推断，传完整符号）

`src/utils/stockCode.ts` **整个删除**。调用点：

| 文件 | 原代码 | 改为 |
|---|---|---|
| `views/stock/KLinePage.vue` L204 | `stockCode: toPureCode(stock.stockCode)` | `stockCode: stock.stockCode`（后端原值，完整符号） |
| `views/stock/KLinePage.vue` L112 | import 删除 | — |
| `views/stock/LightweightChart.vue` | `toTushareCode(props.stockCode)` ×5、`loadDrawings(tushareCode.value, …)` | 直接 `props.stockCode`；`tushareCode` computed 删除 |
| `views/stock/WatchlistPanel.vue` | 搜索结果 `split('.')[0]` 入库、`select-stock` 传 `split('.')[0]` | 全部保留后端原值（完整符号）入库与传递；`addToWatchlist` 传 `600519.SH`；显示可 `code.split('.')[0]` 内联（纯展示） |
| `views/stock/TradePanel.vue` | `toPureCode(s.stockCode)` 回填表单、`toTushareCode(...)` 提交 | 表单直接展示完整符号；提交原值 |
| `views/trade/TradeForm.vue` / `TradeList.vue` | `toTushareCode(formState.stockCode)` | 原值提交（后端 `resolve` 兼容裸码与完整符号） |
| `composables/__tests__` 等 | 无引用（已 grep 确认 `codeMatch`/`normalizeCode` 无外部调用方） | — |

其他注意：

- `lastViewedStock` localStorage：旧值裸码仍可被后端解析，兼容无需迁移。
- 成交量柱数值会变为 100 倍：纵轴会自动缩放，无需改动；若图例显示「手」需改文案为「股」（检索未见该文案，可忽略）。
- **不做**：period 值迁移（`1D→1d`）、`parseTime` 去 `+08:00` 硬编码——两者随阶段 1.5（前端周期列表改由服务端下发时一并处理，`chart_drawing.period` 存量 2 行同窗口迁移）。

### 10.6 `auth_db.user_watchlist` 存量补后缀（全裸 6 位，共 7 行）

```sql
UPDATE `auth_db`.`user_watchlist`
SET `stock_code` = CONCAT(`stock_code`, CASE
    WHEN `stock_code` LIKE '920%' OR SUBSTRING(`stock_code`,1,2) IN ('43','83','87')
      OR `stock_code` LIKE '4%' OR `stock_code` LIKE '8%' THEN '.BJ'
    WHEN `stock_code` LIKE '60%' OR `stock_code` LIKE '68%' OR `stock_code` LIKE '900%' THEN '.SH'
    ELSE '.SZ' END)
WHERE `stock_code` REGEXP '^[0-9]{6}$';
SELECT stock_code FROM `auth_db`.`user_watchlist`;
```

顺序：先补库，再发前端（否则新旧并存两条）。回滚 = 再 `UPDATE ... SET stock_code = SUBSTRING(stock_code,1,6)`。

### 10.7 `trade_db` 删除（先备份，见 §4）

```sql
DROP TABLE IF EXISTS `trade_db`.`stock_minute_data`;   -- 0 行
DROP TABLE IF EXISTS `trade_db`.`stock_weekly_data`;   -- 0 行
DROP TABLE IF EXISTS `trade_db`.`stock_monthly_data`;  -- 0 行
DROP TABLE IF EXISTS `trade_db`.`stock_daily_data`;    -- 约 676 万行，data_db 之前的陈旧副本
DROP TABLE IF EXISTS `trade_db`.`stock_info`;          -- 5,667 行，陈旧副本
```

`trade_db.stock_daily_data` 与 `data_db` 不一致（676 万 vs 766 万），代码零引用（唯一引用方：`sql/migrate_trade_db_to_data_db.sql`，保留作历史档案）。删除前 trade_db 已在 §4 备份中。

---

## 11. 新契约：`GET /market/bars`

**位置**：`data-service`（`MarketBarsController`），gateway 新路由 `/api/market/**`（§10.4）。Python 回测进程内网直连 `http://data-service:8084/market/bars`（无认证，仅限内网）。

**请求参数**

| 参数 | 必填 | 规则 |
|---|---|---|
| `symbol` | 是 | 完整符号 `600519.SH`（原样解析，大写校验）；裸 6 位按 A 股规则推断；两段式市场非法 / 代码为空 / 未知标的 → `400` / `404` |
| `period` | 是 | 受控集合 `1m/5m/15m/30m/1h/4h/1d/1w/1mo`；非法值 → `400` |
| `from` / `to` | 否 | `yyyyMMdd` 或 `yyyy-MM-dd`，闭区间、按 Asia/Shanghai 自然日解释；只传一端 = 开放端；格式非法 → `400` |
| `limit` | 否 | 仅当不传 `from`/`to` 时生效：默认 620、上限 5000、传 ≤0 → 默认 620；传区间时 limit 表示「区间内最近 N 根」，升序返回 |
| `adjust` | 否 | `none`（默认）/ `forward`；其他值 → `400`。`forward` = 原始价 × `factor / factor_latest`；crypto 恒等原样返回；无因子数据时等价 `none` |

**响应**（`Result` 包装，与全站一致）：

```json
{
  "code": 200, "message": "ok",
  "data": {
    "symbol": "600519.SH",
    "period": "1d",
    "adjust": "none",
    "meta": { "name": "贵州茅台", "market": "SH", "currency": "CNY",
              "priceScale": 2, "lotSize": 100, "tickSize": 0.01, "tz": "Asia/Shanghai" },
    "bars": [
      { "time": "2026-11-03",
        "open": 1510.0, "high": 1525.5, "low": 1500.0, "close": 1518.0,
        "volume": 3456700, "amount": 52411230000.00 }
    ]
  }
}
```

- `time`：日线及以上（`1d/1w/1mo`）= `'yyyy-MM-dd'`（lightweight-charts business day，原生支持、无时区坑）；分钟类（`1m…4h`）= UNIX 秒（UTC，`UTCTimestamp`）。epoch 不进日线载荷，符合 ADR-0007。
- `volume` 恒为股/币；`amount` 恒为计价货币元。
- 前端改用本契约 + `meta.priceScale` 渲染精度（阶段 1.5）；旧端点输出维持不变。
- 错误统一 `Result`：业务异常 `400` + 中文消息；未知标的 `404`；服务内部错误 `500`。
- 缓存：L1/L2 两级、key = `kline:v1:{period}:{symbol}:{adjust}`（固定窗口 800 根语义沿用 `DailyKlineCache`）；命中的是「最近 800 根」；`from/to` 带区间时不走缓存。

**旧端点映射**（行为不变，仅换数据源）

| 旧端点 | 转调 | 输出保持 |
|---|---|---|
| `GET /data/stock/daily`（Feign） | `MarketBarsService.range/recent` | `tradeDate`(yyyyMMdd) / OHLC / `preClose` / `vol` / `amount` |
| `GET /data/stock/daily/sparkline` | `selectCloseSeries(bar)` + LTTB | `{d, c[]}` |
| `GET /trade/stock/daily` 等（trade-service 薄适配） | Feign 旧端点 | `Result` 包装 |
| `GET /stock/minute\|weekly\|monthly` | 仍东财代理，不落库 | `time`=UNIX 秒，`period` 原语义 |

---

## 12. 缓存 `DailyKlineCache` 改造与 `evict` 接线

1. key：`kline:v1:{period}:{symbol}:{adjust}`（加版本与维度）。旧 `kline:daily:*` key 全部废弃（TTL 内自然失效；Redis 里旧 key 数量级无碍，无需清理脚本）。
2. `getRecent(symbol, period, adjust, want, loader)`；`CACHE_SIZE=800`、L1 600s、L2 1800s、L1 上限 500 不变。
3. `evict(String symbol, String period)` 新签名：
   - L1：`l1.keySet().removeIf(k -> k.startsWith(PREFIX) && k.contains(":" + period + ":" + symbol + ":"))`
   - L2：直删 `kline:v1:{period}:{symbol}:none` 与 `:forward` 两键（adjust 只有两个值，无需 SCAN）
4. 接线点：`TushareSyncServiceImpl` 日线 upsert 完成后、**同一事务提交之后**，对本轮受影响标的集合逐个 `cache.evict(symbol, "1d")`；双写分支走同一出口。
5. 单测：`DailyKlineCacheTest`——evict 后两 adjust 都必须未命中；旧格式 JSON 反序列化失败时按未命中处理（现有容错保留）。

---

## 13. 双写回滚（过渡期 ≥ 2 周）

- **0.3 完成 → 部署前**：`bar` 与 `stock_daily_data` 完全一致（迁移脚本幂等重跑一遍补当天增量）。
- **过渡期**（上线后至少 2 周）：新日线同步**双写**（bar 正向换算、旧表反向换算）。任何时刻回滚旧代码，旧表仍是最新，图表不断档。
- **观察期结束后（0.9）**：停止反向换算写入；删除 `StockDailyData`/`StockDailyDataMapper`/双写分支；`DROP TABLE stock_daily_data`（或保留归档：先 `RENAME TABLE stock_daily_data TO stock_daily_data_archive_202611`，一个月后再 DROP）。`instrument` 一并接管 `syncStockBasic` 的名称/状态刷新。
- **回滚决策表**：
  - 校验不过（V1–V8 任一红）→ 不部署；修正脚本/数据后重跑；`bar` 是新表，直接 `DROP` 重建，旧链路零影响。
  - 部署后发现功能异常 → 回滚代码即可（bar 保留无害）。
  - 部署后发现 bar 数据错（如换算错）→ 回滚代码 + 双写期旧表仍是对的 → 修正 `bar`（重跑迁移或单日 upsert）。
- 任何一步都不删除 `stock_daily_data`，直到双写两周 + 全量复核通过。

### 双写期每日对账（过渡期照这条查，别用 `src × 1000`）

```sql
SET SESSION time_zone = '+00:00';
-- 换掉日期即可；两边的「行数」与「反算金额」必须相等
SET @d = 20261105;
SELECT
  (SELECT COUNT(*) FROM data_db.bar WHERE period='1d'
     AND ts = UNIX_TIMESTAMP(STR_TO_DATE(CONCAT(@d,' 093000'),'%Y%m%d %H%i%s')) - 28800) AS bar_rows,
  (SELECT COUNT(*) FROM data_db.stock_daily_data WHERE trade_date = @d)                    AS src_rows,
  -- 金额：反算回千元 4 位再比，不要写 src*1000（bar 精度更高，见 §9 第 4 条）
  (SELECT ROUND(SUM(ROUND(amount/1000,4)),4) FROM data_db.bar WHERE period='1d'
     AND ts = UNIX_TIMESTAMP(STR_TO_DATE(CONCAT(@d,' 093000'),'%Y%m%d %H%i%s')) - 28800)  AS bar_qianyuan,
  (SELECT ROUND(SUM(amount),4) FROM data_db.stock_daily_data WHERE trade_date = @d)        AS src_qianyuan,
  -- 量：手 → 股是整数倍，可以精确比
  (SELECT SUM(volume) FROM data_db.bar WHERE period='1d'
     AND ts = UNIX_TIMESTAMP(STR_TO_DATE(CONCAT(@d,' 093000'),'%Y%m%d %H%i%s')) - 28800)  AS bar_shares,
  (SELECT SUM(vol)*100 FROM data_db.stock_daily_data WHERE trade_date = @d)                AS src_shares;
```

### 0.8 执行记录（2026-11-05 已执行）

`sql/migrations/V8__drop_legacy_stock_tables.sql`，已删 `trade_db` 的 `stock_daily_data`（7,352,155 行 / 1,416 MB）、
`stock_info`（5,552 行）、`stock_minute_data` / `stock_weekly_data` / `stock_monthly_data`（均 0 行）。
删前核对过：trade-service 全部改走 data-service Feign，`StockInfo`/`StockInfoMapper` 与三周期实体
均无 import、无注入、无原生 SQL；`trade-service` 侧 6 个死代码文件一并删除。
备份：`F:\backups\v8-drop\trade_db-stock_info.sql`（659 KB）+ `trade_db-dead-tables-schema.sql`，
以及 `F:\backups\fuli-pre-bar-20261104.sql`（两库全量）。
`data_db.stock_info` **未动**（`StockDataController#info(s)` 仍在读），随 0.9 一起处理。

---

## 14. 生产执行时间线（同一窗口完成）

| 时点 | 动作 |
|---|---|
| T-1（白天） | 全部代码合入 main；`mvn -pl common-api,data-service,trade-service -am test` 绿；verify 副本已全流程跑通并 DROP |
| 当日 15:00 收盘后 | 确认 18:00 同步任务状态；若已执行，迁移脚本可幂等重跑 |
| 18:30（18:00 任务结束后） | §4 备份 → §6 DDL → §7 回填 instrument → §8 迁移 → §9 校验（**任一不过立即中止，回滚 = 仅弃用新表**） |
| 19:00 前 | 部署 gateway / common-api / data-service / trade-service / frontend；**此时新代码读 bar（已最新），旧表双写开始** |
| 部署后立即 | 手动 `POST /api/data/tushare/sync/all-incremental`（当天若有更新）；再 `GET /api/data/stock/daily?stockCode=600519.SH&limit=5` 与备份前对比 |
| 冒烟（15 分钟） | 日线窗口 620 根 / 成交量柱 / 划线保存回读 / 练习推进两根 / 最新价 / sparkline |
| +24h | 检查 18:00 任务日志：bar upsert 行数 = 旧表行数，evict 计数 > 0 |
| +2 周 | 全量复核（V1–V8）→ 停双写 → `DROP stock_daily_data`（或归档）→ 清理兼容代码 |

---

## 15. 验收清单（签字前逐项核）

- [x] `instrument` 5,596 行；市值抽样（茅台/平安银行/920 开头北交所标的）字段正确
- [x] `bar` 行数 = 源表行数（实测 2026-11-05 为 7,674,330，随每日同步增长）；V2/V2b/V3 精确相等；V4/V5 = 0；V6 范围正确；V7 相等
- [x] 新接口 `/api/market/bars`：200 / 400（非法 period）/ 404（未知标的）/ 默认 limit=620 / `adjust=forward` 序列末端 = 原始价（5 只 × 21 根逐根对账，偏差 0）
- [x] 旧端点响应 keys 与切换前一致（`tradeDate` 字符串格式、`preClose` 存在）
- [x] sparkline 输出逐元素相等（3 只股票对比）
- [x] 盲测练习全流程（既存 session 推进两根日线）；`Practice*Test` 全绿
- [ ] K 线图：首屏渲染、成交量、画线保存回读、复制导出 CSV（**待部署后冒烟**）
- [x] `trade_db` 三死表 + 两张陈旧副本已删；备份文件可验证可恢复
- [x] 双写行数一致：11-05 实测 `bar` 5,571 = `stock_daily_data` 5,571
- [x] 缓存 evict 语义已用单测钉死（`DailyKlineCacheTest` 5 项：key 带 period/adjust 不串味、
      L1 命中不回源、evictAll 只清目标标的该周期的两个 adjust、Redis 挂掉降级查库、超长请求不进缓存）
      —— **本机未安装 Redis**，真实 Redis 上的 `[K线缓存]` 日志仍需在部署环境复核一次
- [x] Redis 中 `kline:daily:*` 旧 key 无人读（新代码不认），TTL 自然过期即可（key 前缀已换 `kline:v1:`）
- [x] 前端 43 项 `vitest` 全绿 + `vue-tsc` 无错（原 9 项失败已修：mock 契约缺 `markers`、
      用例传裸码 `600519` 与阶段 0「完整符号」约定不符；顺带修掉组件自身视窗回调未退订的真实泄漏）

---

## 16. 风险登记

| 风险 | 等级 | 缓解 |
|---|---|---|
| 单位换算错误（×1000/×100 写错方向或遗漏） | **致命-静默** | 单一换算落点（parse 出口）+ V2/V3 SUM 对账 + 单测断言（构造 1 根样本 bar：vol 输入 123 手 → 断言 12300；amount 1.5 千元 → 1500） |
| 迁移漏当天增量（18:00 任务先于迁移执行） | 高 | 迁移在 18:00 后执行；幂等重跑；部署后再手动增量一次 |
| `pre_close` 丢失（OHLC 不可推算） | 高 | 已入 DDL 与校验 V4/V7 |
| 副本库被导入写回生产（dump 带 `USE`） | 高 | §5 用不带 `--databases` 的单库 dump；verify 与生产分开执行 |
| `user_watchlist` 裸码与完整符号并存 | 中 | §10.6 迁移先于发版 |
| 旧 Redis 缓存反序列化失败 | 低 | 现有容错按未命中处理；key 已加 `v1` 前缀 |
| EM `secid` 前缀（北交所归 0）与文档不符 | 低 | 适配器内注释 + 阶段 1 美股落地时一并实测校准，不擅自改 |
| 删除 `trade_db.stock_daily_data` 后才发现有人读 | 低 | 代码零引用已 grep 确认；即使误删，§4 备份可恢复 |
| `isAStock` 判错导致美股 T+0 卖出未冻结/A股被当 T+0 | 中 | 改读 `marketCode`（instrument.market），后缀兜底；单测：AAPL.US→T+0、600519.SH→T+1、裸 `AAPL`→异常 |
| 停机窗口拖长 | 低 | verify 已预演；生产只剩 §14 四步，每步可单独中止 |

---

## 17. 与后续阶段的衔接（本阶段不实施，但接口要对齐）

1. **阶段 1.5（前端周期下发）**：服务端「市场 → 周期列表」端点（建议 `GET /market/periods?symbol=`，返回 `["1d"]` 等）；前端期值从 `1D/1W/1M` 迁到 `1d/1w/1mo/1m/5m/15m/30m/1h`，同窗口迁移 `chart_drawing.period`（存量 2 行：`1D→1d`、`1W→1w`、`1M→1mo`、`'60'→'1h'`、`'15'→'15m'`、`'5'→'5m'`、`'1'→'1m'`）。
2. **阶段 1.1**：EM 美股 `secid = 105.<纯代码>`（NASDAQ）/ `106.<纯代码>`（NYSE）；`instrument.exchange` 决定前缀；`decimal` → `price_scale`；只同步自选清单内标的（不做全市场）。
3. **阶段 1.4**：美股复权因子 = fqt=0 与 fqt=1 两序列之比，拆股日按整数比校准；落 `adj_factor`（派生、可重建）。
4. **阶段 2.x**：Python 回测 `GET /market/bars`（内网）→ 撮合器 → 净值；`Strategy` 接口回测实盘共用；`adjust` 必须与图表一致靠缓存 key 保证。
5. **阶段 3.x**：~~`trade_record` 加 `source VARCHAR(8) NOT NULL DEFAULT 'MANUAL'`（MANUAL/STRATEGY/PAPER），存量回填 MANUAL~~ —— **此方案已废弃（2026-11-09，ADR-0009 决策 5）**：改用独立订单表 + 所有订单先落库再发网络，以承载订单状态机、`clOrdId` 幂等与 `tradeId` 对账。`POST /strategy/fills` 复用 `X-Internal-Key` 模式的约定仍有效。
6. **GLOSSARY**：如需补「复权因子」「标的元数据」，在对应阶段 PR 里补，本文不动。

---

## 附：估算与分工

| 块 | 工作量 | 依赖 |
|---|---|---|
| 文档修订（本次） | 0.5 d（已完成） | — |
| V7 DDL + 回填脚本 + verify 演练 | 0.5 d | 备份 |
| common-api / data-service / trade-service / gateway 改造 + 单测 | 2–3 d | — |
| frontend 推断删除 + watchlist 迁移 | 0.5 d | 后端先合 |
| 生产切换（§14）+ 观察两周 + 0.9 清理 | 0.5 d + 等待 | 全绿 |

**合计约 4–5 人日**（不含两周观察期）。

---

## 附录：原文与修订对照（速查）

- `amount` ÷1000 → **×1000**；补 `vol` **×100**（ADR-0007 已改）
- `bar` 补 `pre_close`；补 `CHECK(period IN …)`（ADR-0007 已改）
- 备份 → 副本库 verify → 生产，三道门禁（本文 §3–§5）
- sparkline / latest-prices / initializer / info.marketCode 纳入切换（§10.2）
- `/market/bars`：data-service + gateway 新路由 + 内网直连 Python；`meta` 必须返回（§11）
- 缓存 `kline:v1:...`；`evict` 同步后接线（§12）
- 双写 ≥2 周回滚；期满再 DROP（§13）
- `trade_db` 陈旧副本删除（§10.7）
- `user_watchlist` 存量补后缀（§10.6）
- `chart_drawing.period` 迁移挪到阶段 1.5（§17.1）
- Python 侧鉴权复用 `X-Internal-Key`，不新造 HMAC 轮子（见 §0「另」）
