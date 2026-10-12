# Metric Platform

指标平台负责登录认证、构建与部署历史展示，以及固定 `buildId` 的目录、可用维度和查询。dbt-service 是构建事实、有效部署、目录和查询执行的唯一权威。平台与 Agent 不直接交互，不发起构建或部署。

## 模块边界

| 目录 | 职责 |
| --- | --- |
| `backend/metric-core` | 构建、查询、身份及历史投影端口和类型契约 |
| `backend/metric-engine` | dbt-service v3 公开接口的 HTTP 适配 |
| `backend/metric-persistence` | MyBatis Plus、PostgreSQL、Redis、Flyway 与原子历史投影 |
| `backend/metric-app` | 登录安全链、只读 BFF、查询归因与实时历史读取 |
| `frontend` | 部署历史筛选、固定版本目录与查询界面 |
| `api/openapi.yaml` | 平台公开接口的唯一契约，构建时同步打包 |

产品独立构建，不导入 Agent 或 dbt-service 的实现类。旧审计表、历史资源和已应用的迁移继续保留；这些记录不再驱动发布。

## 本地运行

需要 JDK 21、系统 Maven、Node.js/npm、PostgreSQL 和 Redis。仓库 Maven Wrapper 缺少配置，使用 `mvn`。

工作区共享基础设施从工作区根目录启动：

```powershell
docker compose -f .\infra\compose\compose.yml up -d
mvn -f .\metric-platform\pom.xml install -DskipTests
mvn -f .\metric-platform\backend\metric-app\pom.xml spring-boot:run
```

独立运行时先配置 `backend/metric-app/src/main/resources/application.yml` 的平台数据库与 Redis，再从本仓库启动：

```powershell
docker compose up -d postgres
mvn install -DskipTests
mvn -f .\backend\metric-app\pom.xml spring-boot:run
```

服务访问参数支持环境变量覆盖：

| 参数 | 用途 |
| --- | --- |
| `DBT_SERVICE_BASE_URI` | dbt-service 地址，默认 `http://127.0.0.1:8000` |
| `DBT_SERVICE_QUERY_TIMEOUT` | 同步选项与查询的有界 HTTP 等待上限，默认 `PT1860S`，需高于服务执行时限（默认 1800 秒）；外围代理等待时间须更长。其他服务读取仍限 30 秒 |
| `DBT_SERVICE_TOKEN` | 服务访问凭据，供 HTTP 适配使用，不发送到浏览器 |

前端在 `frontend/` 运行：

```powershell
npm ci
npm run dev
```

开发服务器将 `/api` 代理到 `http://localhost:8090`。平台部署只需匹配 dbt-service v3，不依赖 Agent 的运行状态。平台没有协议模式开关，也不会在新接口失败时访问旧业务路径。

## 固定构建与历史

浏览器接口使用 `/api/v3/dbt`。首页 `/dbt/projects` 展示部署版本，输入规范 Git 仓库地址、分支和环境后，通过服务现有部署历史接口分页读取所有部署记录。筛选写入 URL，可收藏或分享；例如 `/dbt/projects?repository=<URL编码的仓库地址>&branchName=main&environment=PRODUCTION`。服务要求明确仓库，不提供全局仓库发现，平台不再读取 Agent 项目或分支。

每条部署记录保留 generation、状态、时间及固定 buildId。已部署记录提供“查询此版本”和“查看指标目录”；当前指针只用于标记当前生效版本，读取失败不阻止浏览历史。目录、可用维度、查询与结果不会自动切换版本；物理对象清理或执行环境不兼容时，由服务返回查询不可用原因。已知构建仍可通过 `/dbt/builds/{buildId}/catalog` 或 `/dbt/builds/{buildId}/query` 打开。

固定 buildId 固定指标定义和构建身份，不等于历史数据快照：当前服务在同一 schema 覆盖同名表，历史版本可能读取当前物理数据。

可用维度与四种查询模式（QUERY、EXPLAIN、PREVIEW、DIMENSION_VALUES）均同步完成。平台保留 `/builds/{buildId}/available-dimensions` 与 `/builds/{buildId}/queries` 门面；适配层分别调用 `/v3/query/available-dimensions?build_id=…` 和 `/v3/query?build_id=…`。可用维度返回 `dimensions`；查询不携带幂等键，一次返回完整有界结果，不再返回 `queryId`、状态或分页字段，也不提供历史结果读取。失败通过非 2xx 业务错误返回，不使用任务状态轮询。平台保留 optionId、时间原值、结果精度及服务的业务错误码、phase、buildId、retryable。时间按构建固定的业务时区解释，由服务返回 `businessTimezone`、`normalizedTimeRange` 与边界策略。

`GET /api/v3/dbt/builds` 直接读取服务构建历史，`repository` 必填，`branchName` 可选。部署列表使用 `/api/v3/dbt/deployments`，固定 repository/environment/branchName 与服务游标。旧投影、变化游标和 Agent 分支缓存表保留供审计，不作为当前列表数据源；旧项目和 Agent 分支代理接口已移除。

构建成功、部署生效和可查询是独立状态。历史物理对象已清理时仍可查看服务封存目录；查询返回对应不可用原因。平台不自行重建物理表、切换部署或启动发布。

## 历史关联迁移

`V15` 新增构建/部署投影、游标、查询归因以及稳定映射表，保留原 `semantic_query.release_id` 和 `runtime_query_id`。没有可信映射的旧查询 `build_id` 保持为空，不能从旧字符串猜测新 UUID，也不能放入默认仓库。

迁移操作先获取并核对 dbt-service 的稳定身份映射报告和真实仓库归属，再在平台数据库调用 `import_legacy_build_mapping(old_release, new_build, source_repository, evidence)`。参数必须来自已核验的只读报告，`evidence` 保存报告位置及摘要。函数在同一事务保存映射并关联旧查询；相同输入重复导入保持稳定，任何身份、仓库或证据冲突均报错。未映射记录可通过 `semantic_release LEFT JOIN legacy_build_mapping` 列出，原封存证据不变。

`V16` 新增 `query_execution_audit` 保存同步查询成功后的本地执行 ID、固定构建与登录身份。它不是 dbt-service 查询身份，不对外提供结果检索；旧查询审计及其原始幂等键保持不变。

`GET /api/v3/dbt/contract` 公开只读协议元数据及打包的同一 OpenAPI，供联合部署前检查；业务接口继续使用现有登录安全链。

## 验证

本仓库运行后端完整验证，事务测试使用真实 Testcontainers PostgreSQL，需要 Docker：

```powershell
mvn -B verify
```

前端在 `frontend/` 执行：

```powershell
npm ci
npm test
npm run build
npm run test:e2e
npm run test:e2e -- --config=playwright.publication.config.ts
```

真实 PostgreSQL 浏览器验收需先具备匹配版本的三个服务、真实 Git 项目及已部署构建。测试使用 `postgres_platform` 隔离 fixture 的 `revenue` 指标与 2024 年 1、2 月数据。设置 `DBT_POSTGRES_E2E=1`、`DBT_POSTGRES_E2E_USER`、`DBT_POSTGRES_E2E_PASSWORD`、`DBT_POSTGRES_E2E_BUILD_ID`、`DBT_POSTGRES_E2E_PLATFORM_URL` 后，在 `frontend/` 运行 `npm run test:e2e -- --config=playwright.postgres.config.ts`。可用 `DBT_POSTGRES_E2E_FRONTEND_PORT` 指定独占前端端口（默认 4175），用 `DBT_POSTGRES_E2E_OUTPUT_DIR` 指定操作系统临时目录中的结果位置。专用配置禁止复用其他开发服务器，并关闭含真实认证请求的 trace 和视频。未准备真实环境时必须明确记录跳过，不将协议桩通过视为真实引擎验收。

