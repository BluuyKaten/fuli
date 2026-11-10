-- ============================================
-- V10：加密域独立表（ADR-0009 决策 5 / 实施计划 P2）
--
-- 状态：**已于 2026-11-09 在 data_db 执行**。
--       流程：结构备份 + 行数基线 → 独立副本库预演（8 张表全通过）→ 正式执行 →
--       存量三表 SHOW CREATE TABLE 逐字对比 + COUNT(*) 对比 + V7__verify_*.sql 全绿。
--       备份/基线在 sql/migrations/backup/，回滚用 V10__crypto_domain_rollback.sql（尚未执行）。
-- 归属服务：data-service
-- 关联 ADR：docs/adr/0009-okx-mcp-tools-and-full-api-scope.md（决策 5、6）
--           docs/adr/0007-unified-bar-store-and-time-axis.md（bar 只存已完结 OHLC）
--
-- 手工执行（项目未接入 Flyway）：
--   mysql -uroot -p data_db < sql/migrations/V10__crypto_domain.sql
--
-- 设计约束（任一条被破坏即视为 P2 验收失败）：
--   1) 只 CREATE 新表，**不 ALTER / 不 DROP 任何存量表**。
--      instrument / bar / adj_factor 的 DDL 与数据必须逐字不变（varchar(20) 零余量不碰）。
--   2) 符号列独立用 VARCHAR(64)，容纳 BTC-USDT-SWAP 与更长的期权 instId；
--      不复用 instrument.code，也不给加密行进 instrument（Q8 隔离方案）。
--   3) 全部 CREATE TABLE IF NOT EXISTS，可重入；字符集与 V7 保持一致。
--   4) 高频深度不进这里：crypto_book_snapshot 只存**降采样后**的稀疏帧（采样率 P8 定），
--      原始 10ms/100ms 流只在内存环形缓冲里，禁止直写 MySQL。
--   5) 时间字段一律**毫秒 epoch**（OKX 口径），与 bar.ts 的「秒」不同，勿混用。
--      in_time / out_time 在 OKX WS 文档里是微秒，字段名保留但语义见列注释。
--   6) 受控集合（inst_type / td_mode / ord_type 等）只在应用层校验，不设 CHECK：
--      ADR-0008 的教训是「加一个取值就要改表 + 改前端枚举」，不再制造隐性硬约束。
--      唯一例外是订单状态机（属于我们自己的模型，不随交易所扩展）。
--
-- 回滚：V10__crypto_domain_rollback.sql（DROP 这 8 张表，不动存量）
-- ============================================

USE `data_db`;

-- ---------- 1. 加密标的与衍生品元数据 ----------
-- 与 instrument 平行但独立：instrument 仍是 A 股/美股/加密现货 K 线的展示主表，
-- 这里承载合约面值、乘数、到期、行权价等 instrument 无法容纳的字段。
CREATE TABLE IF NOT EXISTS `crypto_instrument` (
  `inst_id`      VARCHAR(64)   NOT NULL COMMENT 'OKX 原始 instId，不带 .CRYPTO 后缀',
  `inst_type`    VARCHAR(16)   NOT NULL COMMENT 'SPOT/SWAP/FUTURES/OPTION',
  `inst_family`  VARCHAR(32)   NULL COMMENT '交易品种，如 BTC-USDT',
  `uly`          VARCHAR(32)   NULL COMMENT '期权标的，如 BTC-USD',
  `base_ccy`     VARCHAR(16)   NULL,
  `quote_ccy`    VARCHAR(16)   NULL,
  `settle_ccy`   VARCHAR(16)   NULL,
  `ct_val`       DECIMAL(28,8) NULL COMMENT '合约面值',
  `ct_mult`      DECIMAL(28,8) NULL COMMENT '合约乘数',
  `ct_type`      VARCHAR(16)   NULL COMMENT 'linear / inverse',
  `opt_type`     VARCHAR(4)    NULL COMMENT 'C / P',
  `strike`       DECIMAL(28,8) NULL COMMENT '行权价',
  `expiry_time`  BIGINT        NULL COMMENT '到期时间，毫秒 epoch',
  `lever`        DECIMAL(12,4) NULL COMMENT '杠杆倍数',
  `tick_sz`      DECIMAL(28,8) NULL COMMENT '最小变动价位',
  `lot_sz`       DECIMAL(28,8) NULL COMMENT '下单数量精度',
  `min_sz`       DECIMAL(28,8) NULL,
  `max_lmt_sz`   DECIMAL(28,8) NULL,
  `state`        VARCHAR(16)   NOT NULL DEFAULT 'live',
  `list_time`    BIGINT        NULL,
  `u_time`       BIGINT        NULL COMMENT 'OKX 更新时间，毫秒 epoch',
  `create_time`  DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_time`  DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`inst_id`),
  KEY `idx_crypto_inst_type` (`inst_type`),
  KEY `idx_crypto_inst_family` (`inst_family`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='加密标的与衍生品元数据（独立域）';

-- ---------- 2. 订单主表（先落库再发网络 / 写前日志） ----------
-- pending = 已落库但尚未发网络；断网重启后靠它识别孤儿单。
-- cl_ord_id 不设 UNIQUE：OKX 只保证「所有挂单」维度唯一，历史重复是合法场景，
-- 幂等由应用层按 (cl_ord_id, inst_id) + 未完成状态查询保证。
CREATE TABLE IF NOT EXISTS `crypto_order` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `cl_ord_id`      VARCHAR(32)   NULL COMMENT '客户自定义订单 ID，幂等键，最长 32 位',
  `ord_id`         VARCHAR(64)   NULL COMMENT '交易所订单 ID，全局唯一；下单被拒时可为空',
  `inst_id`        VARCHAR(64)   NOT NULL,
  `inst_type`      VARCHAR(16)   NOT NULL DEFAULT 'SPOT',
  `state`          VARCHAR(24)   NOT NULL COMMENT 'pending/live/partially_filled/filled/canceled/rejected',
  `side`           VARCHAR(8)    NOT NULL COMMENT 'buy / sell',
  `ord_type`       VARCHAR(24)   NOT NULL COMMENT 'market/limit/post_only/fok/ioc/...',
  `px`             DECIMAL(28,8) NULL,
  `sz`             DECIMAL(28,8) NOT NULL COMMENT 'SPOT 为基础币数量；合约为张数，按 inst_type 区分口径',
  `fill_sz`        DECIMAL(28,8) NULL COMMENT '本次推送成交量',
  `acc_fill_sz`    DECIMAL(28,8) NULL COMMENT '累计成交量；WS 推送中始终是累计值，不是增量',
  `avg_px`         DECIMAL(28,8) NULL,
  `fee`            DECIMAL(28,8) NULL,
  `fee_ccy`        VARCHAR(16)   NULL,
  `pnl`            DECIMAL(28,8) NULL,
  `category`       VARCHAR(16)   NULL,
  `lever`          DECIMAL(12,4) NULL,
  `td_mode`        VARCHAR(16)   NULL COMMENT 'cash / cross / isolated',
  `pos_side`       VARCHAR(16)   NULL COMMENT 'net / long / short',
  `trade_id`       VARCHAR(64)   NULL COMMENT '最新成交 ID，用于与持仓对账',
  `pos_id`         VARCHAR(64)   NULL,
  `cancel_source`  VARCHAR(32)   NULL COMMENT '撤单原因，区分系统撤单与自成交保护',
  `s_code`         VARCHAR(16)   NULL COMMENT '入口返回的 sCode，非 0 表示被拒（此时无 WS 推送）',
  `s_msg`          VARCHAR(255)  NULL,
  `c_time`         BIGINT        NULL COMMENT '订单创建时间，毫秒 epoch',
  `u_time`         BIGINT        NULL COMMENT '订单最后更新时间，毫秒 epoch',
  `fill_time`      BIGINT        NULL COMMENT '成交时间，毫秒 epoch',
  `in_time`        BIGINT        NULL COMMENT 'WS 网关接收时间，微秒 epoch（REST 口径见接入手册）',
  `out_time`       BIGINT        NULL COMMENT 'WS 网关响应时间，微秒 epoch',
  `create_time`    DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_time`    DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_crypto_order_ord_id` (`ord_id`),
  KEY `idx_crypto_order_cl_ord_id` (`cl_ord_id`),
  KEY `idx_crypto_order_inst_state` (`inst_id`, `state`),
  KEY `idx_crypto_order_u_time` (`u_time`),
  CONSTRAINT `chk_crypto_order_state` CHECK (`state` IN
      ('pending', 'live', 'partially_filled', 'filled', 'canceled', 'rejected'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='OKX 订单主表（先落库再发网络）';

-- ---------- 3. 成交明细 ----------
-- tradeId 只在单个 instId 维度唯一，故用复合主键；强平/ADL 场景 tradeId 为负值。
CREATE TABLE IF NOT EXISTS `crypto_fill` (
  `inst_id`     VARCHAR(64)   NOT NULL,
  `trade_id`    VARCHAR(64)   NOT NULL COMMENT '成交 ID，instId 维度唯一',
  `ord_id`      VARCHAR(64)   NULL,
  `cl_ord_id`   VARCHAR(32)   NULL,
  `side`        VARCHAR(8)    NULL,
  `fill_px`     DECIMAL(28,8) NULL,
  `fill_sz`     DECIMAL(28,8) NULL,
  `fee`         DECIMAL(28,8) NULL,
  `fee_ccy`     VARCHAR(16)   NULL,
  `pos_id`      VARCHAR(64)   NULL,
  `bill_id`     VARCHAR(64)   NULL,
  `fill_time`   BIGINT        NULL COMMENT '毫秒 epoch',
  `create_time` DATETIME      DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`inst_id`, `trade_id`),
  KEY `idx_crypto_fill_ord` (`ord_id`),
  KEY `idx_crypto_fill_time` (`fill_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='OKX 成交明细';

-- ---------- 4. 持仓快照 ----------
-- 对账注意（okx.txt）：多个持仓变化可能聚合成一条推送、只有最新 tradeId；
-- 强平/ADL 触发的持仓变化**不更新 tradeId**，必须同时比较 pos 或 u_time。
CREATE TABLE IF NOT EXISTS `crypto_position` (
  `pos_id`      VARCHAR(64)   NOT NULL COMMENT 'OKX 持仓 ID：mgnMode+posSide+instId+ccy 派生',
  `inst_id`     VARCHAR(64)   NOT NULL,
  `inst_type`   VARCHAR(16)   NOT NULL,
  `mgn_mode`    VARCHAR(16)   NULL,
  `pos_side`    VARCHAR(16)   NULL,
  `pos`         DECIMAL(28,8) NULL COMMENT '持仓数量，可为负',
  `avail_pos`   DECIMAL(28,8) NULL,
  `avg_px`      DECIMAL(28,8) NULL,
  `upl`         DECIMAL(28,8) NULL,
  `upl_ratio`   DECIMAL(28,8) NULL,
  `lever`       DECIMAL(12,4) NULL,
  `liq_px`      DECIMAL(28,8) NULL,
  `imr`         DECIMAL(28,8) NULL,
  `mmr`         DECIMAL(28,8) NULL,
  `mgn_ratio`   DECIMAL(28,8) NULL,
  `trade_id`    VARCHAR(64)   NULL COMMENT '最新成交 ID；对账时还需比较 pos 或 u_time',
  `c_time`      BIGINT        NULL,
  `u_time`      BIGINT        NULL,
  `create_time` DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`pos_id`),
  KEY `idx_crypto_position_inst` (`inst_id`, `inst_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='OKX 持仓快照';

-- ---------- 5. 盘口降采样快照 ----------
-- ⚠️ 不是原始深度流。books/books-l2-tbt 的 10ms/100ms 帧只进内存环形缓冲 + 降采样（P3/P8），
-- 这里只落降采样后的稀疏帧，采样率在 P8 定；P2 只建表、不写入。
CREATE TABLE IF NOT EXISTS `crypto_book_snapshot` (
  `inst_id`     VARCHAR(64) NOT NULL,
  `ts_ms`       BIGINT      NOT NULL COMMENT '快照时间，毫秒 epoch',
  `seq_id`      BIGINT      NULL COMMENT 'OKX 序号，用于校验连续性',
  `checksum`    VARCHAR(64) NULL COMMENT 'OKX checksum；-1 表示需要重新订阅全量',
  `bids`        JSON        NULL COMMENT '降采样后的买盘档位',
  `asks`        JSON        NULL COMMENT '降采样后的卖盘档位',
  `create_time` DATETIME    DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`inst_id`, `ts_ms`),
  KEY `idx_crypto_book_seq` (`inst_id`, `seq_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='盘口降采样快照（原始高频流只进内存）';

-- ---------- 6. 永续资金费率 ----------
CREATE TABLE IF NOT EXISTS `crypto_funding_rate` (
  `inst_id`           VARCHAR(64)    NOT NULL,
  `ts_ms`             BIGINT         NOT NULL,
  `funding_rate`      DECIMAL(28,12) NULL,
  `next_funding_rate` DECIMAL(28,12) NULL,
  `realized_rate`     DECIMAL(28,12) NULL,
  `funding_time`      BIGINT         NULL COMMENT '毫秒 epoch',
  `next_funding_time` BIGINT         NULL COMMENT '毫秒 epoch',
  `method`            VARCHAR(16)    NULL,
  PRIMARY KEY (`inst_id`, `ts_ms`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='永续合约资金费率时序';

-- ---------- 7. 标记价格 / 指数价格 ----------
CREATE TABLE IF NOT EXISTS `crypto_mark_price` (
  `inst_id`  VARCHAR(64)   NOT NULL,
  `ts_ms`    BIGINT        NOT NULL,
  `mark_px`  DECIMAL(28,8) NULL,
  `idx_px`   DECIMAL(28,8) NULL,
  `sod_utc0` DECIMAL(28,8) NULL,
  `sod_utc8` DECIMAL(28,8) NULL,
  PRIMARY KEY (`inst_id`, `ts_ms`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='衍生品标记价格与指数价格时序';

-- ---------- 8. 期权 Greeks ----------
-- PA/BS 两套口径由 account/set-greeks 切换，两套字段都留。
CREATE TABLE IF NOT EXISTS `crypto_option_greeks` (
  `inst_id`  VARCHAR(64)    NOT NULL,
  `ts_ms`    BIGINT         NOT NULL,
  `iv`       DECIMAL(28,12) NULL,
  `delta`    DECIMAL(28,12) NULL,
  `gamma`    DECIMAL(28,12) NULL,
  `theta`    DECIMAL(28,12) NULL,
  `vega`     DECIMAL(28,12) NULL,
  `delta_bs` DECIMAL(28,12) NULL,
  `gamma_bs` DECIMAL(28,12) NULL,
  `theta_bs` DECIMAL(28,12) NULL,
  `vega_bs`  DECIMAL(28,12) NULL,
  `mark_vol` DECIMAL(28,12) NULL,
  PRIMARY KEY (`inst_id`, `ts_ms`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='期权 Greeks 时序（PA / BS）';
