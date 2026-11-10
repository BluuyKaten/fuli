-- ============================================
-- V7 回滚：弃用多市场底座三张新表
-- 归属服务：data-service
-- 关联 ADR：docs/adr/0007-unified-bar-store-and-time-axis.md
--
-- ⚠️ 仅在「迁移校验未通过、尚未部署新代码」时使用。
--    此时旧链路（stock_daily_data + 旧端点）完全未动，DROP 新表即可干净回退。
--
-- ⚠️ 一旦新代码上线并写入 bar，本脚本会造成新数据丢失；
--    此时回滚方式是「回滚代码」，而不是 DROP 表（bar 留着无害）。
--
-- 手工执行：
--   mysql -uroot -p data_db < sql/migrations/V7__instrument_bar_adj_factor_rollback.sql
-- ============================================

USE `data_db`;

DROP TABLE IF EXISTS `adj_factor`;
DROP TABLE IF EXISTS `bar`;
DROP TABLE IF EXISTS `instrument`;
