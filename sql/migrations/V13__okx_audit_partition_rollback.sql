-- ============================================
-- V13 回滚：审计表还原为普通表（保留全部数据）
--
-- 状态：**未执行**。
-- 回滚后主键恢复为 `id` 单列；分区维护任务会自动降级为「按时间分批 DELETE」。
-- ============================================

USE `data_db`;

DROP TABLE IF EXISTS `okx_audit_log_plain`;
CREATE TABLE `okx_audit_log_plain` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`     BIGINT UNSIGNED NULL,
  `action`      VARCHAR(64)   NOT NULL,
  `scope`       VARCHAR(32)   NOT NULL,
  `outcome`     VARCHAR(16)   NOT NULL,
  `inst_id`     VARCHAR(64)   NULL,
  `symbol`      VARCHAR(64)   NULL,
  `side`        VARCHAR(8)    NULL,
  `sz`          DECIMAL(28,8) NULL,
  `px`          DECIMAL(28,8) NULL,
  `ord_id`      VARCHAR(64)   NULL,
  `cl_ord_id`   VARCHAR(32)   NULL,
  `detail`      VARCHAR(512)  NULL,
  `trace_id`    VARCHAR(64)   NULL,
  `create_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_okx_audit_user_time` (`user_id`, `create_time`),
  KEY `idx_okx_audit_action_time` (`action`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='OKX 操作审计（普通表，保留 2 个月）';

INSERT INTO `okx_audit_log_plain`
  (`id`, `user_id`, `action`, `scope`, `outcome`, `inst_id`, `symbol`, `side`, `sz`, `px`,
   `ord_id`, `cl_ord_id`, `detail`, `trace_id`, `create_time`)
SELECT `id`, `user_id`, `action`, `scope`, `outcome`, `inst_id`, `symbol`, `side`, `sz`, `px`,
       `ord_id`, `cl_ord_id`, `detail`, `trace_id`, `create_time`
FROM `okx_audit_log`;

DROP TABLE `okx_audit_log`;
RENAME TABLE `okx_audit_log_plain` TO `okx_audit_log`;
