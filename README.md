# fuli
复利

## 股票复盘系统（微服务版）项目结构

- `/home/runner/work/fuli/fuli/stock-parent/pom.xml`：父工程（Spring Cloud BOM 管理）
- `/home/runner/work/fuli/fuli/stock-parent/common-api`：公共 DTO、统一响应体、Feign 接口
- `/home/runner/work/fuli/fuli/stock-parent/auth-service`：登录/注册、JWT、Spring Security、Redis 黑名单
- `/home/runner/work/fuli/fuli/stock-parent/trade-service`：交易记录 CRUD 与分页筛选
- `/home/runner/work/fuli/fuli/stock-parent/analysis-service`：统计聚合接口（Feign 调 trade-service）
- `/home/runner/work/fuli/fuli/stock-parent/gateway-service`：Gateway 路由 + JWT 全局过滤器
- `/home/runner/work/fuli/fuli/stock-parent/nacos-config`：Nacos Config 样例配置（各服务 `application.yml`）
- `/home/runner/work/fuli/fuli/stock-parent/sql`：数据库 DDL（`auth_db` 与 `trade_db`）
- `/home/runner/work/fuli/fuli/frontend`：Vue 3 + Vite + TS + Pinia + Ant Design Vue 前端骨架
