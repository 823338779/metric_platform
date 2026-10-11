# Metric Platform

指标平台负责登录认证、构建与部署历史展示，以及固定 `buildId` 的目录、可用维度和查询。Agent 是项目配置及真实 Git 分支的来源；dbt-service 是构建事实、有效部署、目录和查询执行的唯一权威。保存到 Git、构建部署、合并并发布均进入 Agent。

## 模块边界

| 目录 | 职责 |
| --- | --- |
| `backend/metric-core` | 构建、查询、身份及历史投影端口和类型契约 |
| `backend/metric-engine` | dbt-service v3 与 Agent 公开接口的 HTTP 适配 |
| `backend/metric-persistence` | MyBatis Plus、PostgreSQL、Redis、Flyway 与原子历史投影 |
| `backend/metric-app` | 登录安全链、只读 BFF、查询归因与实时历史读取 |
| `frontend` | 项目/分支选择、历史构建、固定版本目录与查询界面 |
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
| `AGENT_BASE_URI` | Agent 地址，默认 `http://127.0.0.1:8080` |
| `AGENT_SERVICE_TOKEN` | Agent 已有服务访问边界需要的凭据 |

前端在 `frontend/` 运行：

```powershell
npm ci
npm run dev
```

开发服务器将 `/api` 代理到 `http://localhost:8090`。部署必须同时使用匹配的 Agent、平台和 dbt-service v3 版本。平台没有协议模式开关，也不会在新接口失败时访问旧业务路径。

## 固定构建与历史

浏览器接口使用 `/api/v3/dbt`。项目和分支由 Agent 提供；页面上下文记录项目、规范仓库、分支、环境和 `buildId`。未部署分支显示空态；新部署出现只提示显式刷新，目录、可用维度、查询与结果不会自动切换版本。历史构建可通过 `/dbt/builds/{buildId}/catalog` 打开。

可用维度与四种查询模式（QUERY、EXPLAIN、PREVIEW、DIMENSION_VALUES）均同步完成。平台保留 `/builds/{buildId}/available-dimensions` 与 `/builds/{buildId}/queries` 门面；适配层分别调用 `/v3/query/available-dimensions?build_id=…` 和 `/v3/query?build_id=…`。可用维度返回 `dimensions`；查询不携带幂等键，一次返回完整有界结果，不再返回 `queryId`、状态或分页字段，也不提供历史结果读取。失败通过非 2xx 业务错误返回，不使用任务状态轮询。平台保留 optionId、时间原值、结果精度及服务的业务错误码、phase、buildId、retryable。时间按构建固定的业务时区解释，由服务返回 `businessTimezone`、`normalizedTimeRange` 与边界策略。

`GET /api/v3/dbt/builds` 直接读取服务构建历史，`repository` 必填，`branchName` 可选。首页先读取 Agent 项目，由所选项目仓库加载历史；分页沿用同一仓库与筛选。已知构建链接可直接读取详情，不依赖 Agent 项目发现。平台不再消费已删除的 `/v3/changes`；旧投影与变化游标保留供审计，不作为当前构建列表数据源。Agent 失联时分支缓存仍明确标记 `cached=true`、来源和观察时间。

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

前端可配置 `VITE_AGENT_URL` 作为返回 Agent 的开发入口，只接受 HTTP/HTTPS 地址并仅传递 `projectId`、`branchName`。
