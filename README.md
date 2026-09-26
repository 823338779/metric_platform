# Metric Platform

Metric Platform 控制 Git `main` 上 dbt 项目的全量构建和版本发布，保存已发布的只读目录，并将固定 `releaseId` 的类型化查询交给外部 dbt/MetricFlow 运行服务。平台不再维护数据源、数据集、指标或维度的独立定义，也不自行编译语义 SQL。

## 项目结构

| 路径 | 职责 |
| --- | --- |
| `metric-git-sync/` | 安全读取 Git `main` 和计算 dbt 项目输入摘要。 |
| `backend/` | 发布控制、版本目录、查询门面、身份认证与数据库迁移。 |
| `frontend/` | dbt 项目、发布历史、只读目录和查询界面。 |
| `api/openapi.yaml` | 公开 HTTP 契约。 |

## 本地运行

需要 JDK 21、Node.js、npm、PostgreSQL 和 Redis。位于 Agent 工作区时，从工作区根目录启动共享基础设施：

```powershell
docker compose -f .\infra\compose\compose.yml up -d
```

从工作区根目录启动后端：

```powershell
mvn -f .\metric-platform\pom.xml -pl metric-git-sync -am install -DskipTests
mvn -f .\metric-platform\backend\pom.xml spring-boot:run
```

独立运行时使用仓库内的 PostgreSQL 服务，并配置对应连接：

```powershell
docker compose up -d postgres
$env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://localhost:5433/metric_platform'
$env:SPRING_DATASOURCE_USERNAME = 'metric_platform'
$env:SPRING_DATASOURCE_PASSWORD = 'metric_platform'
mvn -pl metric-git-sync -am install -DskipTests
mvn -f .\backend\pom.xml spring-boot:run
```

Redis 通过标准 `SPRING_DATA_REDIS_*` 属性配置。前端从 `frontend/` 运行：

```powershell
npm ci
npm run dev
```

前端代理 `/api` 至 `http://localhost:8090`。

## 验证

```powershell
mvn -B verify
```

在 `frontend/` 目录执行：

```powershell
npm ci
npm test
npm run build
```

## 发布与契约

Git 订阅默认关闭，部署参数、发布语义和真实 StarRocks 验收门槛见 [dbt 发布运行说明](docs/dbt-publication.md)。已应用的旧版 Flyway 迁移与历史数据保留，但旧管理 API 不再暴露。公开接口以 [OpenAPI 契约](api/openapi.yaml) 为准。
