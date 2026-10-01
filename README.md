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

后端服务配置统一维护在 `backend/metric-app/src/main/resources/application.yml`，Spring Boot 在 IDEA、Maven 和可执行 JAR 启动时自动加载。数据库连接与密码、Redis 连接与密码、HTTP 端口、Git 固定绑定与缓存目录、Webhook 密钥、dbt 运行服务地址以及认证和初始管理员参数均直接写在该文件中，无需在 IDEA 启动配置中重复填写。当前数据库为 `metric_platform`、端口为 `8090`、dbt 运行服务地址为 `http://127.0.0.1:8000`；Git 缓存使用当前工作区的绝对路径，移动工作区时需修改该配置。Webhook 密钥在手动模式下为空，启用 Webhook 时直接填写 `metric-platform.git-sync.webhook-secret`。

`application-swarm.yml` 保留 Swarm profile 专用的优雅停机配置。Spring Boot 的标准环境变量和命令行覆盖规则仍然生效；日常配置修改以服务配置文件为准。

```powershell
docker compose -f .\infra\compose\compose.yml up -d
```

从工作区根目录启动后端：

```powershell
mvn -f .\metric-platform\pom.xml install -DskipTests
mvn -f .\metric-platform\backend\metric-app\pom.xml spring-boot:run
```

独立运行时使用仓库内的 PostgreSQL 服务，先将 `application.yml` 的 `spring.datasource.url` 改为 `jdbc:postgresql://localhost:5433/metric_platform`，并将 `username`、`password` 均改为 `metric_platform`；Redis 连接也直接修改该文件的 `spring.data.redis` 配置，然后启动：

```powershell
docker compose up -d postgres
mvn install -DskipTests
mvn -f .\backend\metric-app\pom.xml spring-boot:run
```

前端从 `frontend/` 运行：

```powershell
npm ci
npm run dev
```

前端代理 `/api` 至 `http://localhost:8090`。

项目页从已发布版本进入只读目录；目录按资源类型展示指标、维度、语义模型、表和视图。点击资源可查看编译产物生成的结构化详情、血缘和固定构建 SHA 的 YAML 源码。查询工作台从固定 `releaseId` 获取共同维度、`metric_time` 粒度和筛选能力，支持维度值查询及保留高精度原值的结果表格。运行服务没有查询能力时，页面会显示原因并禁用提交。

查询工作台统一使用 `Asia/Shanghai`（北京时间），日期输入和快捷范围不随浏览器时区改变。HTTP API 仍传输带时区的 ISO 时间；平台 HTTP 适配层将其转换为北京时间的无时区日历值，以兼容 MetricFlow。例如页面选择 `2026-09-25 00:00`，请求为 `2026-09-24T16:00:00Z`，引擎收到 `2026-09-25T00:00:00`。时间边界会按查询粒度对齐，按天查询包含起止日期的整天。该口径适用于当前电商项目的 `DATE` 下单日期以及按北京时间存储的无时区时间字段；接入 UTC 存储或其他业务时区的数据源时，必须先在语义模型中统一时间口径，不能直接套用此约定。平台不修改数据库会话时区或已有数据。

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

## dbt-service 代理模式迁移

新模式由 dbt-service 统一持有发布指针、类型化目录和查询身份。平台只认证、代理 HTTP 和展示，读取 `/api/v2/semantic-projects`；刷新页面只重新读取发布结果。目录不经过平台业务导入，也不写入指标、维度或数据集镜像表。发布成功后上游不可用仍可能导致页面读取失败，此时返回明确错误，不伪造空目录或把发布改成失败。

迁移开关为后端 `metric.publication.mode=service-proxy`，前端构建参数 `VITE_PUBLICATION_MODE=service-proxy`。默认 `legacy` 仅保留迁移前行为。两个开关在同一部署窗口切换；代理模式停用平台发布协调器及本地查询受理/轮询，旧 refresh/webhook 发布入口返回 409 `publication_managed_by_service`。不得同时运行新旧发布者。

部署顺序：

1. 暂停旧平台 Git 发布写入，等待在途发布结束；保留数据库与已应用 Flyway，不删除旧表。
2. 只读导出旧 release→run、query→runtimeQueryId 的对应关系，核对项目归属。通过 dbt-service 管理命令执行身份映射 dry-run。
3. 只有同一 run 已具备服务发布证明时才导入旧身份。无法验证的旧 run 应保留历史，先由服务完成一次新发布，不把旧 ID 指向新内容。
4. 核对服务活动版本、目录、选项与查询，再同步切换后端和前端开关，禁用旧发布配置。
5. 验证新版目录、旧版新请求 410、已受理查询读取以及断网恢复。应用回退不得重启旧发布者来覆盖服务指针。

代理模式下 v1 目录和新查询也转接服务；平台仅对固定展示字段做旧响应形状适配，原生名称到查询资源身份的转换由服务执行。旧发布元数据作为只读历史补充，迁移前查询按原 runtimeQueryId 读取原结果，不重新提交或补写平台任务。新页面显示服务生成的 `attributes`，`nativeDetails` 仅用于追溯。

类型化快照、接口字段和错误行为见 [OpenAPI](api/openapi.yaml) 与 [发布设计](docs/superpowers/specs/2026-09-30-artifact-driven-platform-catalog-design.md)。后端仍独立构建；服务发布不等待平台在线或回调确认。

代理页面浏览器验收使用独立配置启动 `service-proxy` 前端，避免改变迁移前页面的既有验收：

```powershell
npm run test:e2e -- --config playwright.publication.config.ts
```

此命令在 `frontend/` 执行，验证真实浏览器与 HTTP 协议桩；真实 dbt/MetricFlow 与 PostgreSQL 流程在 dbt-service 仓库单独验证。

## 发布与契约

Git 订阅默认关闭，部署参数、发布语义和真实 StarRocks 验收门槛见 [dbt 发布运行说明](docs/dbt-publication.md)。旧版 Flyway 迁移文件保留以支持从空库初始化；旧管理 API 不再暴露，旧目录表由 `V14` 清理。公开接口以 [OpenAPI 契约](api/openapi.yaml) 为准。
