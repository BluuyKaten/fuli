-- ============================================
-- V11：多账号 OKX 凭证隔离 + 审计持久化 + Agent 建议单
--
-- 状态：**待执行**（执行门控见 docs/okx-实施计划.md §3 P10）。
-- 归属服务：data-service（三张表都在 data_db）
-- 关联 ADR：docs/adr/0010-okx-multiuser-credentials-and-agent-readonly.md
--
-- 手工执行（项目未接入 Flyway）：
--   mysql -uroot -p data_db < sql/migrations/V11__okx_multiuser_credentials.sql
--
-- 设计约束（任一条被破坏即视为本次验收失败）：
--   1) 只 CREATE 新表，**不 ALTER / 不 DROP 任何存量表**。
--      instrument / bar / adj_factor / crypto_* 的 DDL 与数据必须逐字不变。
--   2) 凭证列只存密文：AES-256-GCM，**每行每字段独立随机 IV**，
--      存储格式 iv(12) || ciphertext || tag(16)。KEK 来自环境变量 OKX_CRED_KEK，
--      不进数据库、不进代码、不进日志。api_key 不是机密但同样只回掩码。
--   3) 时间字段 DATETIME（与 V10 的毫秒 epoch 列区分：这两张表是「我们自己的」运维数据，
--      用数据库时钟便于按自然月清理）。
--   4) okx_audit_log 保留最近 2 个月：由 `OkxAuditRetentionJob` 每日分批删除。
--      **不按月分区**——分区表的主键必须包含分区列，会给将来演进加约束；
--      量级到千万级再改 RANGE 分区（DROP PARTITION 秒级），方案写在表注释与本文件末尾。
--   5) okx_order_suggestion 只记录建议，**不代表已下单**；status 仅表示人工处置结果。
--
-- 回滚：V11__okx_multiuser_credentials_rollback.sql（DROP 这 3 张表，不动存量）
-- ============================================

USE `data_db`;

-- ---------- 1. 多账号 OKX 凭证 ----------
-- 一个用户可存多套凭证（模拟盘主账户 / 子账户 / 测试账户），用 label 区分。
-- 交易时取 enabled=1 的第一条；label 是将来「按账户切换」的预留维度。
-- 唯一键 (user_id, label) 保证同一用户下标签不重复，也天然阻止跨用户覆盖。
CREATE TABLE IF NOT EXISTS `okx_account` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`         BIGINT UNSIGNED NOT NULL COMMENT '归属用户（auth_db.sys_user.id），凭证按用户物理隔离',
  `label`           VARCHAR(64)   NOT NULL COMMENT '用户自定义标签，如「模拟盘-主账户」',
  `api_key`         VARCHAR(128)  NOT NULL COMMENT 'OKX API Key；非机密，但对外只返回掩码',
  `secret_key_enc`  VARBINARY(512) NOT NULL COMMENT 'AES-256-GCM：iv(12)||ciphertext||tag(16)，随机 IV',
  `passphrase_enc`  VARBINARY(512) NOT NULL COMMENT '同上，与 secret 使用各自独立的随机 IV',
  `key_version`     SMALLINT UNSIGNED NOT NULL DEFAULT 1 COMMENT 'KEK 版本号，密钥轮换时递增',
  `enabled`         TINYINT       NOT NULL DEFAULT 1 COMMENT '1-可用于交易 0-停用（停用不删除密文）',
  `create_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_okx_account_user_label` (`user_id`, `label`),
  KEY `idx_okx_account_user_enabled` (`user_id`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='OKX 凭证（每用户多套，密文存储）';

-- ---------- 2. 审计日志 ----------
-- 与 [OKX-AUDIT] 结构化日志双写：日志用于实时排障，这张表用于事后追责与统计。
-- 落表失败**绝不影响主流程**（OkxAuditService 内 try/catch，只 WARN）。
-- 只记录白名单字段，detail 里也绝不出现 secret / passphrase / 完整请求体。
CREATE TABLE IF NOT EXISTS `okx_audit_log` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`     BIGINT UNSIGNED NULL COMMENT 'NULL 表示服务账号（type=SERVICE，如 ai-service）',
  `action`      VARCHAR(64)   NOT NULL COMMENT '动作名，如 placeOrder / capability.spot_place_order',
  `scope`       VARCHAR(32)   NOT NULL COMMENT 'orders:read / orders:write / orders:credential',
  `outcome`     VARCHAR(16)   NOT NULL COMMENT 'ALLOWED / DENIED / SUCCESS / FAILED',
  `inst_id`     VARCHAR(64)   NULL COMMENT 'OKX instId，不带 .CRYPTO 后缀',
  `symbol`      VARCHAR(64)   NULL COMMENT '调用方传入的原始符号',
  `side`        VARCHAR(8)    NULL COMMENT 'buy / sell',
  `sz`          DECIMAL(28,8) NULL,
  `px`          DECIMAL(28,8) NULL,
  `ord_id`      VARCHAR(64)   NULL,
  `cl_ord_id`   VARCHAR(32)   NULL,
  `detail`      VARCHAR(512)  NULL COMMENT '已清洗的精简参数；禁止写入凭证与完整请求体',
  `trace_id`    VARCHAR(64)   NULL COMMENT '与日志 MDC 的 traceId 对齐，便于日志↔表互相跳转',
  `create_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_okx_audit_user_time` (`user_id`, `create_time`),
  KEY `idx_okx_audit_action_time` (`action`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='OKX 操作审计（保留 2 个月，每日清理）';

-- ---------- 3. Agent 下单建议单 ----------
-- ai-service 只能写这里，**永远不调下单接口**（ADR-0010 决策 4）。
-- 人工看到建议后自己决定是否手动下单；这张表不是订单表，不进 crypto_order 状态机。
CREATE TABLE IF NOT EXISTS `okx_order_suggestion` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`     BIGINT UNSIGNED NOT NULL COMMENT '建议归属用户',
  `inst_id`     VARCHAR(64)   NOT NULL,
  `side`        VARCHAR(8)    NOT NULL COMMENT 'buy / sell',
  `ord_type`    VARCHAR(24)   NOT NULL DEFAULT 'limit' COMMENT 'limit / market',
  `sz`          DECIMAL(28,8) NOT NULL,
  `px`          DECIMAL(28,8) NULL COMMENT '市价建议可为空',
  `td_mode`     VARCHAR(16)   NOT NULL DEFAULT 'cash' COMMENT '本期只允许 cash（现货）',
  `reason`      VARCHAR(512)  NULL COMMENT '给建议的理由，便于复盘',
  `confidence`  DECIMAL(5,4)  NULL COMMENT '0~1，规则版可为空',
  `status`      VARCHAR(16)   NOT NULL DEFAULT 'new' COMMENT 'new/accepted/rejected/expired，仅表示人工处置',
  `source`      VARCHAR(32)   NOT NULL DEFAULT 'agent' COMMENT '来源：agent / manual',
  `trace_id`    VARCHAR(64)   NULL,
  `create_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_okx_suggestion_user_time` (`user_id`, `create_time`),
  CONSTRAINT `chk_okx_suggestion_status` CHECK (`status` IN ('new', 'accepted', 'rejected', 'expired'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Agent 下单建议（不触发任何下单）';

-- ============================================
-- 长期归档方案（当前不实现，写清楚是为了将来不用重新设计）
--
-- 审计表体积估算：按每秒 1 条、每条 300B 计，2 个月约 520 万行 / 1.5GB。
-- 现状（每日 DELETE 分批清理）在该量级可用，代价是删除期间有间隙锁与 undo 增长。
-- 演进路径（任一触发即执行）：
--   A. 行数 > 2000 万：改为 `PARTITION BY RANGE (TO_DAYS(create_time))` 按月分区，
--      主键改成 (id, create_time) 以满足「分区列必须进主键」；清理变成
--      `ALTER TABLE okx_audit_log DROP PARTITION p202601;`，秒级且不产生 undo。
--   B. 需要合规留存 > 1 年：每月 1 日把上上月分区导出为
--      `sql/archive/okx_audit_log_YYYYMM.sql.gz`（mysqldump --single-transaction），
--      落冷存储后再 DROP PARTITION；表内始终只留热数据。
--   C. 需要审计不可篡改：追加 `chain_hash`（前一行 hash + 本行内容）并把每日尾哈希
--      写入独立介质；当前未做，因为这会改变写入路径的失败语义（写不动就不能放行）。
-- ============================================
