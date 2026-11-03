-- ============================================
-- V4: createTrade 幂等键（request_id）
-- 为交易记录表增加客户端幂等键列与唯一索引，用于重复提交去重，
-- 避免双击/网络重试导致重复扣款 + 重复落单。
-- 注意：request_id 全为 NULL 的历史数据不受唯一索引约束（NULL 不参与唯一性比较）。
-- ============================================
USE `trade_db`;

ALTER TABLE `trade_record`
    ADD COLUMN `request_id` VARCHAR(64) DEFAULT NULL COMMENT '客户端幂等键（重复提交去重，可为空）' AFTER `remark`;

ALTER TABLE `trade_record`
    ADD UNIQUE KEY `uk_user_request` (`user_id`, `request_id`);
