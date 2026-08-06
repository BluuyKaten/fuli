-- ============================================
-- auth_db 数据库 - 用户认证
-- ============================================

CREATE DATABASE IF NOT EXISTS `auth_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE `auth_db`;

-- 用户表
DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    `username` VARCHAR(64) NOT NULL COMMENT '用户名',
    `password` VARCHAR(255) NOT NULL COMMENT '密码（BCrypt加密）',
    `nickname` VARCHAR(64) DEFAULT NULL COMMENT '昵称',
    `email` VARCHAR(128) DEFAULT NULL COMMENT '邮箱',
    `phone` VARCHAR(32) DEFAULT NULL COMMENT '手机号',
    `avatar` VARCHAR(512) DEFAULT NULL COMMENT '头像URL',
    `cash` DECIMAL(16, 2) NOT NULL DEFAULT 200000.00 COMMENT '现金余额',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0-禁用 1-启用',
    `last_login_time` DATETIME DEFAULT NULL COMMENT '最后登录时间',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除 1-已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    KEY `idx_email` (`email`),
    KEY `idx_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统用户表';

-- 初始化管理员账号（密码: admin123，BCrypt加密，初始现金20万）
INSERT INTO `sys_user` (`username`, `password`, `nickname`, `email`, `cash`, `status`) VALUES
('admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVEFDa', '管理员', 'admin@fuli.com', 200000.00, 1);

-- 幂等消息表（防止 Feign 重试导致重复扣款/入账）
-- 状态：0-PROCESSING(处理中) 1-SUCCESS 2-FAILED
DROP TABLE IF EXISTS `idempotent_message`;
CREATE TABLE `idempotent_message` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `msg_id` VARCHAR(64) NOT NULL COMMENT '消息唯一 ID（与 trade_db.local_message.msg_id 一致）',
    `user_id` BIGINT UNSIGNED NOT NULL COMMENT '用户 ID',
    `amount` DECIMAL(16, 2) NOT NULL COMMENT '变动金额',
    `cash_direction` TINYINT NOT NULL COMMENT '1-扣款 2-入账',
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0-PROCESSING 1-SUCCESS 2-FAILED',
    `error_msg` VARCHAR(512) DEFAULT NULL COMMENT '失败原因',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_msg_id` (`msg_id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='幂等消息表（防止重复资金变动）';
