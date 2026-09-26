# 指标平台 Java 模块、MyBatis Plus 与 dbt 目录 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Metric Platform 后端拆为职责清晰的五模块，并以 MyBatis Plus 持久化固定版本发布状态，提供按 dbt 原生产物临时生成的展示与查询 API。

**Architecture:** `metric-core` 定义用例和端口，`metric-persistence` 实现 MyBatis Plus 仓储，`metric-runtime-client` 实现 Python HTTP 契约，现有 `metric-git-sync` 读固定 SHA，`backend` 负责 Web、认证、调度和装配。保留原发布原子性和全量构建语义。

**Tech Stack:** Java 21、Spring Boot 4.1、MyBatis Plus 3.5.16、PostgreSQL、Flyway、MapStruct、JUnit、Testcontainers。

**Spec:** `../../../../docs/superpowers/specs/2026-09-26-dbt-platform-modules-and-visual-catalog-design.md`

## Global Constraints

- 只修改 `metric-platform/`；不实现 Agent 交互和 StarRocks MetricFlow adapter，不恢复平台可编辑的指标、维度和数据源。
- Git `main` 项目输入变化始终全量构建独立 schema；READY 后平台事务切换，查询固定 `releaseId`，失败保持旧版。
- 生产 dbt 业务服务不注入 `JdbcTemplate`，优先使用 MyBatis Plus 的实体映射、`BaseMapper`、Lambda Wrapper 和分页插件；行锁等并发边界允许少量参数化 Mapper SQL。
- MapStruct 仅生成重复字段映射；页面展示 DTO 和维度值不落库。现有 `semantic_resource.definition_json` 的原生投影与 `semantic_release.validation_json` 的产物版本元数据继续保留。
- 不能改写已应用 Flyway 迁移；新增查询模式用新迁移。`api/openapi.yaml` 必须与公开路由、类型和错误一致。
- 按工作区 `AGENTS.md` 为新增字段和关键代码块写注释，代码字符串使用静态常量；使用已有认证模型，不新增租户或角色。
- 每个 Superpowers plan 执行完毕后才创建一个代码 commit，计划编写阶段不创建提交。

## Review Focus

1. `main` 在 A 构建期间更新 B，或 README 变更复用 A：只允许已观测最新目标发布，目标 SHA 与构建来源 SHA 均正确；Task 3/4 测试覆盖。
2. 查询受理与 schema 清理并发：不能在查询占用后清理其 run；Task 5 测试覆盖。
3. JSONB、复合键及 UUID 映射：目录批量导入、读取和删除不能丢失原生 JSON 或串 release；Task 4/5 测试覆盖。
4. 不认识的 dbt artifact schema 或资源类型：报兼容性错误或显示原生详情，不能猜测指标查询能力；Task 6 测试覆盖。
5. 旧 release 已清理或与项目不符：目录、源码、选项和查询均返回 410/404，不回退到当前指针；Task 6/7 测试覆盖。

---

## File Map

| 文件或目录 | 职责 |
| --- | --- |
| `pom.xml`、`backend/pom.xml`、`metric-core/pom.xml`、`metric-persistence/pom.xml`、`metric-runtime-client/pom.xml` | 单向 Maven 依赖与编译插件 |
| `metric-core/src/main/java/com/metricplatform/core/dbt/` | 发布、查询、目录用例、端口、DTO、临时产物转换 |
| `metric-persistence/src/main/java/com/metricplatform/persistence/dbt/` | 五张表的实体、Mapper、JSONB TypeHandler、MapStruct、仓储适配 |
| `backend/src/main/java/com/metricplatform/persistence/MybatisConfiguration.java` | 现有 MyBatis 装配，补 PostgreSQL 分页插件与 dbt Mapper 扫描 |
| `metric-runtime-client/src/main/java/com/metricplatform/runtime/dbt/` | 固定 run、目录、查询选项和任务的 HTTP 客户端 |
| `metric-git-sync/src/main/java/com/metricplatform/gitsync/GitSnapshotReader.java` | 按固定 SHA 安全读取 YAML 来源文件 |
| `backend/src/main/java/com/metricplatform/dbt/` | 控制器、`DbtReleaseScheduler`、查询/清理调度与配置装配；迁移后删除旧 JDBC Store 实现 |
| `backend/src/main/resources/db/migration/V13__dbt_dimension_values_query.sql` | 扩展 `semantic_query.mode` 的检查约束及字段注释 |
| `api/openapi.yaml`、`README.md` | 公开契约及模块运行说明 |

### Task 1: Maven 边界与核心接口

**Files:** Modify `pom.xml`, `backend/pom.xml`; create `metric-core/pom.xml`, `metric-persistence/pom.xml`, `metric-runtime-client/pom.xml`、`metric-core/src/main/java/com/metricplatform/core/dbt/DbtReleaseRepository.java`, `DbtCatalogRepository.java`, `DbtQueryRepository.java`、`backend/src/test/java/com/metricplatform/dbt/DbtModuleBoundaryTest.java`；move `DbtRuntimeDtos.java`, `DbtRuntimeClient.java`, `DbtQueryDtos.java` to `metric-core/src/main/java/com/metricplatform/core/dbt/`。

**Interfaces:** `DbtReleaseRepository`、`DbtCatalogRepository`、`DbtQueryRepository` 保留现有 Store 的发布/目录/查询能力签名；`DbtRuntimeGateway` 对应 `submitRun/getRun/getRunByKey/getCatalog/submitQuery/getQuery/getQueryByKey/cleanup`。先以现有实现适配端口，再逐 Task 替换内部实现，避免中间阶段失去可编译状态。

- [ ] **Step 1: 写失败架构测试。** `DbtModuleBoundaryTest.moduleBoundariesCompileWithoutBackendReference` 通过模块编译与依赖检查断言 core、persistence、runtime-client 不依赖 `backend`；旧控制器 API 测试保持通过。
- [ ] **Step 2: 验证测试失败。** 运行 `mvn -B -pl backend -am test -Dtest=DbtCatalogControllerTest -Dsurefire.failIfNoSpecifiedTests=false`，新模块尚不存在时架构检查失败。
- [ ] **Step 3: 建模块和端口。** 依赖方向按设计；搬移 DTO、Runtime gateway 与相关测试，`backend` 仍是唯一可执行 Spring Boot 模块；保持 API JSON 字段名不变。
- [ ] **Step 4: 验证通过。** 运行 `mvn -B -pl backend -am test -Dtest=DbtCatalogControllerTest -Dsurefire.failIfNoSpecifiedTests=false`，并检查 `mvn -B -pl metric-core,metric-persistence,metric-runtime-client -am compile`。

### Task 2: HTTP 运行客户端独立成模块

**Files:** Move `backend/.../dbt/HttpDbtRuntimeClient.java`, `DbtRuntimeProperties.java` to `metric-runtime-client/src/main/java/com/metricplatform/runtime/dbt/`；move `HttpDbtRuntimeClientTest.java`；modify backend wiring。

**Interfaces:** `HttpDbtRuntimeClient implements DbtRuntimeGateway`；继续使用已有 `/v1/project-runs`、`/v1/query-jobs` 和幂等键路径，新增 `queryOptions(UUID runId, List<String> metrics)`；目录响应增加 artifact schema 版本。

- [ ] **Step 1: 写失败测试。** `queryOptionsUsesFixedRunAndEncodedMetricNames`、`catalogRejectsMissingArtifactVersions`、`non2xxResponseDoesNotLeakBody`；保留原超时/响应大小测试。
- [ ] **Step 2: 验证测试失败。** 运行 `mvn -B -pl metric-runtime-client -am -Dtest=HttpDbtRuntimeClientTest -Dsurefire.failIfNoSpecifiedTests=false test`。
- [ ] **Step 3: 搬移并扩展客户端。** 只从配置构造 base URI，调用 Python 平台专用 API；字段名按外部契约对齐，不改变 core 的发布决策。
- [ ] **Step 4: 验证通过。** 重跑客户端测试及 `backend` 的控制器测试。

### Task 3: 发布状态仓储改用 MyBatis Plus

**Files:** Create `metric-persistence/.../dbt/entity/SemanticProjectEntity.java`, `SemanticReleaseEntity.java`、对应 Mapper、`DbtReleaseRepositoryAdapter.java`、`DbtEntityMapper.java`、`JsonbTypeHandler.java`；modify `metric-persistence/pom.xml` 加 MapStruct 编译处理器及 MyBatis Plus 分页插件依赖、`backend/src/main/java/com/metricplatform/persistence/MybatisConfiguration.java` 配置 PostgreSQL 分页和 Mapper 扫描；remove 旧 `DbtReleaseStore.java` JDBC 实现；move/adjust `DbtPersistenceTest.java`。

**Interfaces:** `DbtReleaseRepository.observe(projectId, DbtProjectRevision, configVersion, toolchainVersion)` 返回 `ObservationResult`；`work(UUID)`、`active(projectId)`、`pending(projectId)` 与现有协调器语义一致。实体由 `@TableName/@TableId/@TableField` 映射，MapStruct 转为 core record。

- [ ] **Step 1: 写失败测试。** `readmeOnlyCommitReusesBuildSourceSha`、`differentDigestSupersedesOldCandidate`、`concurrentObservationHasSingleSequence`、`jsonbValidationRoundTrips`；现有发布测试仍须保留。
- [ ] **Step 2: 验证测试失败。** 运行 `mvn -B -pl backend -am -Dtest=DbtPersistenceTest -Dsurefire.failIfNoSpecifiedTests=false test`，预期新增仓储测试失败。
- [ ] **Step 3: 实现仓储。** 普通操作用 `BaseMapper` 和 Lambda Wrapper；项目行 `SELECT FOR UPDATE` 集中为一个参数化 Mapper 方法；增减重试计数和发布 CAS 检查影响行数。MapStruct 配置编译期未映射字段错误，JSONB 使用 PostgreSQL TypeHandler。
- [ ] **Step 4: 验证通过。** 重跑持久化及 `DbtReleaseCoordinatorTest`；检查生产 `DbtReleaseRepositoryAdapter` 没有 `JdbcTemplate`。

### Task 4: 目录与原子发布迁移

**Files:** Create `metric-persistence/.../dbt/entity/SemanticResourceEntity.java`, `SemanticDependencyEntity.java`、Mapper、`DbtCatalogRepositoryAdapter.java`；move `DbtPublicationService.java`、`DbtCatalogService.java` 到 core 并改用端口；split `DbtReleaseCoordinator.java` into core `DbtReleaseWorkflow.java` and backend `DbtReleaseScheduler.java`；update `DbtPublicationServiceTest.java`, `DbtPublicationFlowTest.java`, `DbtReleaseCoordinatorTest.java`。

**Interfaces:** `DbtCatalogRepository.replace(releaseId, resources, dependencies)`、`resources(releaseId)`、`dependencies(releaseId)`；`DbtPublicationService.publishReady(releaseId, runSnapshot, catalogSnapshot)` 在短事务里校验并切换指针，事务外完成远端读取。

- [ ] **Step 1: 写失败测试。** `candidateCatalogInvisibleBeforePointerSwitch`、`supersededAReadyCannotPublishAfterBObserved`、`rawDefinitionJsonRoundTripsWithCompositeKey`、`failedCatalogImportKeepsOldRelease`。
- [ ] **Step 2: 验证测试失败。** 运行 `mvn -B -pl backend -am -Dtest=DbtPublicationServiceTest,DbtPublicationFlowTest -Dsurefire.failIfNoSpecifiedTests=false test`。
- [ ] **Step 3: 实现目录仓储和发布用例。** 复合键以 release/resource 条件 Wrapper 操作；保留数据库 FK 和短事务，外部 HTTP 不进入事务；产物版本写入 `validation_json`。将原协调器的 Git 观察与候选推进移到 core workflow，线程及启动/轮询留在 backend scheduler；core 抛领域错误，由控制器映射 HTTP 状态。
- [ ] **Step 4: 验证通过。** 重跑发布、目录持久化和旧版发布回归测试。

### Task 5: 查询引用与清理迁移

**Files:** Create `metric-persistence/.../dbt/entity/SemanticQueryEntity.java`、Mapper、`DbtQueryRepositoryAdapter.java`；move `DbtQueryService.java`、`DbtReleaseCleanupService.java` 到 core；在 backend 保留薄调度触发；update `DbtReleaseCleanupServiceTest.java`, `DbtQueryControllerTest.java`。

**Interfaces:** `DbtQueryRepository.reserve(queryId, projectId, releaseId, key, mode)` 在 release 行锁下占用版本；`DbtReleaseRepository.claimCleanup(releaseId)` 在同一行锁下核实无活动查询；查询状态与运行服务任务 ID 仍可重启恢复。

- [ ] **Step 1: 写失败测试。** `queryReservationAndCleanupClaimAreMutuallyExclusive`、`reusedRunWithLiveReleaseIsNotCleaned`、`pendingQuerySurvivesRestart`、`wrongProjectQueryIdIsNotVisible`。
- [ ] **Step 2: 验证测试失败。** 运行 `mvn -B -pl backend -am -Dtest=DbtReleaseCleanupServiceTest,DbtQueryControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`。
- [ ] **Step 3: 实现查询仓储与清理。** 普通更新用 Wrapper；仅 release 行锁保留参数化 Mapper SQL；后台查询和清理触发留在 backend，业务判断留在 core。
- [ ] **Step 4: 验证通过。** 重跑查询/清理测试及 `DbtPublicationFlowTest`。

### Task 6: 临时展示 DTO 与固定 SHA YAML 详情

**Files:** Create `metric-core/.../dbt/DbtPresentationService.java`, `DbtPresentationDtos.java`, `DbtArtifactPresenter.java`；modify `metric-git-sync/.../GitSnapshotReader.java`, backend `DbtCatalogController.java`；update `DbtCatalogControllerTest.java`, `metric-git-sync/.../GitSnapshotReaderTest.java`。

**Interfaces:** `present(projectId, releaseId, resourceId) -> ResourcePresentation(releaseId, resourceId, kind, title, sections, nativeDetails, queryable)`；`source(projectId, releaseId, resourceId) -> SourceDocument(releaseId, buildSourceSha, sourcePath, content)`；平台路由 `GET /resources/{resourceId}/presentation` 与 `/source`。输入来自 `definition_json` 及 release 的 artifact schema 版本，YAML 读取 `buildSourceSha`。

- [ ] **Step 1: 写失败测试。** `metricDimensionSemanticModelTableViewHaveTypedPresentation`、`unknownFieldRemainsVisibleButNotQueryable`、`unsupportedArtifactVersionFailsClosed`、`sourceUsesBuildShaNotCurrentMain`、`cleanedReleaseReturns410`。
- [ ] **Step 2: 验证测试失败。** 运行 `mvn -B -pl backend -am -Dtest=DbtCatalogControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`。
- [ ] **Step 3: 实现读取时转换。** 不新增展示表或缓存；固定 SHA 文件读取重用 Git reader 的路径/模式校验；来源路径为空或越界时返回明确错误，不读工作树。
- [ ] **Step 4: 验证通过。** 重跑目录与 Git reader 测试，检查 `semantic_resource` 未新增展示字段。

### Task 7: 查询选项与维度值公开 API

**Files:** Modify core `DbtQueryService.java`、`DbtQueryDtos.java`，backend `DbtQueryController.java`，`backend/src/main/resources/db/migration/V13__dbt_dimension_values_query.sql`，`api/openapi.yaml`；update `DbtQueryControllerTest.java`, `DbtPersistenceTest.java`。

**Interfaces:** `queryOptions(projectId, releaseId, metrics) -> QueryOptions(releaseId, metrics, dimensions, timeDimensions, allowedFilters)`；`submitDimensionValues(projectId, releaseId, metrics, dimension, filters, limit) -> QuerySubmission`。选项均带原生 token；平台路由 `GET /query-options`、`POST /queries:dimension-values` 返回 202；原 `GET /queries/{queryId}` 返回模式 `DIMENSION_VALUES` 结果。普通查询的日期范围、类型化过滤和排序字段同步扩展到运行服务 DTO，不让控制器接受任意 SQL。

- [ ] **Step 1: 写失败测试。** `multiMetricOptionsAreFixedToRelease`、`dimensionValuesHoldReleaseReference`、`unsupportedOperatorOrOversizeLimitRejected`、`cleanedOrForeignReleaseDoesNotFallback`、`openApiMatchesRoutesAndModes`。
- [ ] **Step 2: 验证测试失败。** 运行 `mvn -B -pl backend -am -Dtest=DbtQueryControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`。
- [ ] **Step 3: 实现接口。** 只代理固定 run 的 MetricFlow 能力；新 Flyway 迁移扩展 `semantic_query.mode` 检查约束，不重写 V12；更新 OpenAPI schema、错误和 README 模块命令。
- [ ] **Step 4: 验证通过。** 运行 `mvn -B verify`；检查 `rg -n 'JdbcTemplate' backend/src/main/java/com/metricplatform/dbt metric-core/src/main/java metric-persistence/src/main/java/com/metricplatform/persistence/dbt` 仅允许测试或无结果；核对根仓库与本子仓库的状态。

## 计划完成后的提交

按工作区规则，在**整份计划执行完成且验证通过后**于 `metric-platform` 创建一个代码 commit，不在各 Task 中分别提交。不得把用户的无关改动、生成文件或根仓库 Git 链接纳入提交；实施前须遵循用户对提交的明确授权。
