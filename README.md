# fuli（复利）

股票复盘系统 - 帮助投资者系统化复盘、总结经验、提升交易水平。

## 功能

- 📈 K 线图（蜡烛图 + MA/MACD/KDJ）
- 💰 交易记录与持仓管理
- 📊 盈亏分析、资产曲线、月度收益
- 🔄 Tushare 行情数据同步

## 技术栈

- **后端**: Spring Boot 4.1 + Spring Cloud 2025.1 + MyBatis-Plus 3.5
- **前端**: Vue 3 + Vite + TypeScript + Ant Design Vue + ECharts
- **数据库**: MySQL 8.0+
- **缓存**: Redis（可选）

## 快速开始

### 前置条件

- JDK 17+
- Maven 3.8+
- Node.js 18+
- MySQL 8.0+（必须）

### 启动步骤

```bash
# 1. 初始化数据库（在 MySQL 中执行）
source sql/auth_db.sql
source sql/trade_db.sql

# 2. 设置环境变量
cp .env.example .env
# 编辑 .env 设置 JWT_SECRET、DB_PASSWORD 等

# 3. 启动后端（按顺序）
mvn -pl auth-service spring-boot:run
mvn -pl trade-service spring-boot:run
mvn -pl analysis-service spring-boot:run
mvn -pl data-service spring-boot:run
mvn -pl gateway-service spring-boot:run

# 4. 启动前端
cd frontend && npm install && npm run dev
```

访问 http://localhost:3000

详细文档见 [docs/开发进度.md](docs/开发进度.md)。

## 项目结构

```
fuli/
├── common-api/          # 公共模块
├── gateway-service/     # API 网关 (8080)
├── auth-service/        # 认证服务 (8081)
├── trade-service/       # 交易服务 (8082)
├── analysis-service/    # 分析服务 (8083)
├── data-service/        # 数据同步 (8084)
├── frontend/            # 前端 (3000)
└── sql/                 # 数据库脚本
```

## 文档

- [开发进度](docs/开发进度.md) - 详细功能列表与启动方式
- [项目总结](doc/Project.md) - 架构总结与改进建议
