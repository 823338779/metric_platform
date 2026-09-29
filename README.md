# Metric Platform

Metric Platform 负责语义项目的版本发布、只读目录、查询和身份认证。平台控制 Git `main` 上项目的全量构建与发布，并将固定 `releaseId` 的类型化查询交给计算引擎。当前引擎实现为 dbt/MetricFlow，指标、维度等定义来自项目源码。

## 项目结构

| 路径 | 职责 |
| --- | --- |
| `backend/` | Java 聚合模块，统一管理下面四个子模块的构建。 |
| `backend/metric-core/` | 目录、发布、查询、身份契约与发布规则；声明计算引擎、项目源码和资源展示端口。 |
| `backend/metric-persistence/` | 平台数据的 MyBatis Plus 实体、Mapper、`IService`、仓储、Redis 令牌存储、MyBatis 配置及 Flyway 迁移。 |
| `backend/metric-engine/` | dbt/MetricFlow 引擎适配：运行服务 HTTP 客户端、dbt 产物展示解析、受控 Git 项目读取与输入摘要。 |
| `backend/metric-app/` | 可执行 Spring Boot 应用，负责 Web、发布调度、认证流程、安全配置和模块装配。 |
| `frontend/` | 项目、发布历史、只读目录和查询界面。 |
| `api/openapi.yaml` | 公开 HTTP 契约。 |

Java 包统一以 `com.metricplatform` 为根，按平台职责组织：

- `metric-core`：`catalog`、`publication`、`query`、`identity` 和 `engine`。业务类使用 `ReleaseWorkflow`、`CatalogRepository`、`QueryDtos` 等平台名称。
- `metric-persistence`：所有持久化实现统一放在 `persistence` 下，包括 `entity`、`mapper`、`service`、`store`、`catalog`、`publication`、`identity`、`mapping`、`redis`、`typehandler` 和 `config`。
- `metric-engine`：具体实现放在 `engine.dbt.runtime`、`engine.dbt.catalog` 和 `engine.dbt.git`。`HttpDbtRuntimeClient`、`DbtArtifactPresenter`、`DbtProjectReader` 分别实现核心模块中的 `EngineClient`、`ResourcePresenter`、`ProjectSourceReader`。
- `metric-app`：目录、发布与查询分别放在 `catalog`、`publication`、`query`，控制器放在各自的 `api` 子包；身份、安全、Git 调度及全局异常处理分别位于 `identity`、`security`、`gitsync`、`api`。

`metric-app` 装配引擎与持久化实现，业务服务通过 `metric-core` 的端口调用它们。`metric-engine` 与 `metric-persistence` 只依赖 `metric-core`，两者没有相互依赖。MyBatis Plus 查询条件、实体映射和发布清理的数据库事务保留在 `metric-persistence`。

兼容边界：现有 HTTP 路由、OpenAPI schema 名称与错误码、`metric-platform.dbt-runtime` / `dbt-query` / `dbt-cleanup` 部署配置、现役数据库表名和已应用的 Flyway 迁移保持兼容。dbt 原生文件名与产物 schema 标识属于引擎协议。前端源码命名本次未调整。

`V14` 清理不再使用的人工目录表和旧 Git 同步表。升级前应检查并备份这些表中的历史数据；只要其中任意一张非空，迁移就会停止，避免自动丢失数据。当前发布、查询、身份和审计使用的表继续保留。

## 本地运行

需要 JDK 21、Node.js、npm、PostgreSQL 和 Redis。位于 Agent 工作区时，从工作区根目录启动共享基础设施：

```powershell
docker compose -f .\infra\compose\compose.yml up -d
```

从工作区根目录启动后端：

```powershell
mvn -f .\metric-platform\pom.xml install -DskipTests
mvn -f .\metric-platform\backend\metric-app\pom.xml spring-boot:run
```

独立运行时使用仓库内的 PostgreSQL 服务，并配置对应连接：

```powershell
docker compose up -d postgres
$env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://localhost:5433/metric_platform'
$env:SPRING_DATASOURCE_USERNAME = 'metric_platform'
$env:SPRING_DATASOURCE_PASSWORD = 'metric_platform'
mvn install -DskipTests
mvn -f .\backend\metric-app\pom.xml spring-boot:run
```

Redis 通过标准 `SPRING_DATA_REDIS_*` 属性配置。前端从 `frontend/` 运行：

```powershell
npm ci
npm run dev
```

前端代理 `/api` 至 `http://localhost:8090`。

项目页从已发布版本进入只读目录；目录按资源类型展示指标、维度、语义模型、表和视图。点击资源可查看编译产物生成的结构化详情、血缘和固定构建 SHA 的 YAML 源码。查询工作台从固定 `releaseId` 获取共同维度、`metric_time` 粒度和筛选能力，支持维度值查询及保留高精度原值的结果表格。运行服务没有查询能力时，页面会显示原因并禁用提交。

## 验证

```powershell
mvn -B verify
```

在 `frontend/` 目录执行：

```powershell
npm ci
npm test
npm run build
npm run test:e2e
```

真实 PostgreSQL 浏览器验收需先启动 PostgreSQL profile 的 dbt 运行服务和已启用 Git 发布的平台，等待 fixture 项目出现 `PUBLISHED` 版本。随后在 `frontend/` 设置 `DBT_POSTGRES_E2E=1`、`DBT_POSTGRES_E2E_USER`、`DBT_POSTGRES_E2E_PASSWORD`，可选设置 `DBT_POSTGRES_E2E_PROJECT`，运行 `npm run test:e2e -- dbt-postgres-integration.spec.ts`。该测试通过页面登录，使用真实 API 验证发布目录、YAML 源码、月粒度指标查询与维度值；未设置开关时跳过真实集成场景。

## 发布与契约

Git 订阅默认关闭，部署参数、发布语义和真实 StarRocks 验收门槛见 [dbt 发布运行说明](docs/dbt-publication.md)。旧版 Flyway 迁移文件保留以支持从空库初始化；旧管理 API 不再暴露，旧目录表由 `V14` 清理。公开接口以 [OpenAPI 契约](api/openapi.yaml) 为准。
