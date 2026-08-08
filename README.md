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

- JDK 21+
- Maven 3.8+
- Node.js 18+
- MySQL 8.0+（必须）
- Redis（可选）
- Nacos（可选，不启动不影响本地开发）

### 启动步骤

```bash
# 1. 初始化数据库（在 MySQL 中执行）
mysql -u root -p < sql/auth_db.sql
mysql -u root -p < sql/trade_db.sql

# 2. 构建并安装所有模块
mvn clean install -DskipTests

# 3. 启动后端（每个服务一个终端，均使用 local profile）
mvn -pl auth-service spring-boot:run -Dspring-boot.run.profiles=local
mvn -pl trade-service spring-boot:run -Dspring-boot.run.profiles=local
mvn -pl analysis-service spring-boot:run -Dspring-boot.run.profiles=local
mvn -pl data-service spring-boot:run -Dspring-boot.run.profiles=local
mvn -pl gateway-service spring-boot:run -Dspring-boot.run.profiles=local

# 4. 启动前端
cd frontend && npm install && npm run dev
```

访问 http://localhost:3000

### 本地开发说明

- **local profile**：各服务均提供 `application-local.yml`，配置了开发用密钥与 localhost 直连路由，**无需配置环境变量即可启动**。
- **生产环境**：不激活 local profile，必须通过环境变量注入强密钥：
  ```bash
  export JWT_SECRET=$(openssl rand -base64 64)      # >= 32 字节
  export INTERNAL_KEY=$(openssl rand -base64 24)   # >= 16 字节
  ```
  启动时网关会校验密钥强度，弱默认值将导致启动失败。
- **服务发现**：默认使用 `lb://` 负载均衡（需 Nacos）；local profile 直连 localhost 各端口。

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
