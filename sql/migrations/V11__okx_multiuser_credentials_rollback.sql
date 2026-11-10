-- ============================================
-- V11 回滚：多账号凭证 / 审计 / 建议单
--
-- 状态：**未执行**（仅在需要回到 V11 之前时手工执行）。
-- 只 DROP V11 新建的 3 张表，不触碰任何存量对象。
--
-- 注意：DROP 会丢失已保存的凭证密文与审计记录。回滚前先按
--   mysqldump --single-transaction data_db okx_account okx_audit_log okx_order_suggestion
-- 备份，否则无法恢复。
-- 执行后 data-service 会因缺少表而在写凭证/审计/建议时失败（读行情不受影响），
-- 因此回滚必须与代码回滚同步进行。
-- ============================================

USE `data_db`;

DROP TABLE IF EXISTS `okx_order_suggestion`;
DROP TABLE IF EXISTS `okx_audit_log`;
DROP TABLE IF EXISTS `okx_account`;
