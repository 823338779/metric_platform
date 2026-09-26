# Metric Platform dbt 全量构建与版本发布 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 `metric-platform` 从 Git `main` 发现 dbt 项目变更，调用外部服务全量构建，并向平台用户提供原子发布、版本化目录和查询。

**Architecture:** 保留旧 Ossie 模式，新增 DBT 模式及独立 PostgreSQL 发布表。平台用固定 SHA 和项目摘要调用外部运行服务，验证 READY 后事务切换 activeReleaseId；新 API 和控制台只读取已发布投影，异步查询始终固定 releaseId。

**Tech Stack:** Java 21、Spring Boot 4.1、Spring JDBC、PostgreSQL/Flyway、Git CLI、React 19、TypeScript、Vitest。

**Spec:** `docs/superpowers/specs/2026-09-26-dbt-full-build-publication-design.md`

## Global Constraints

- 只修改 `metric-platform/`；Agent 交互和外部 dbt/MetricFlow 服务内部实现不在计划内。
- 单配置仓库、`refs/heads/main`、单 dbt 项目、单 StarRocks 部署；不接受请求方提供的 remote、profile、schema 或 SQL。
- dbt 项目内容一旦变化，就请求新 schema 全量 build 和完整测试；仅输入目录外的 README/Markdown 等非输入文件变化只更新 observedSha，输入目录中的 dbt docs Markdown 仍计入摘要。
- 候选不能覆盖当前 release；只对瞬时网络/服务故障限次重试，确定性错误等待新项目内容。
- 使用 dbt/MetricFlow 原生产物；不转换为 Ossie，不改变现有 `/api/v1/queries` 同步 200 契约。
- 当前外部服务的 MetricFlow StarRocks 查询不支持；平台代码可在 DBT 模式默认关闭时开发，真实库查询和全量构建验收通过前不得启用 DBT 模式或声称已交付可查询版本。
- 增量字段与非显而易见逻辑按工作区 `AGENTS.md` 写注释；代码字符串遵守静态常量规则；公开 API 与 `api/openapi.yaml` 同步。
- 当前工作区规则要求在计划执行完成后才创建一个代码 commit；本计划各任务不单独提交，计划编制阶段也不提交。

## Review Focus

1. 缺失 `dbt_project.yml`、符号链接/子模块、项目路径逃出或自定义资源路径：拒绝该 revision，不能误记为未变化（Task 1）。
2. A 构建时 B 只改 README：B 可复用同摘要结果，但发布记录的目标 SHA 是 B、构建来源 SHA 是 A（Task 5）。
3. B 的项目内容已被观测后 A 才 READY：A 必须 SUPERSEDED，不能改变 activeReleaseId（Task 4、5）。
4. READY 产物的摘要、schema、工具链或 StarRocks 查询能力不匹配：保持旧版可查询（Task 3、4）。
5. 查询固定旧 release 后发生发布与回收：查询占用与清理认领在 release 行锁下互斥，非终态查询保护旧 schema；已清理 release 返回明确错误（Task 7、8）。

---

## File Map

| 单元 | 文件与职责 |
| --- | --- |
| Git 输入 | `metric-git-sync/.../GitSnapshotReader.java` 增加 dbt revision 摘要；新建 `DbtProjectRevision.java`，保留旧 `read()` 行为 |
| 持久状态 | `backend/.../db/migration/V12__dbt_semantic_release.sql`；`backend/.../dbt/DbtReleaseStore.java`、`DbtCatalogStore.java`、`DbtQueryStore.java` 使用现有 Spring JDBC 和 PostgreSQL 事务 |
| 服务边界 | `backend/.../dbt/DbtRuntimeClient.java`、`HttpDbtRuntimeClient.java`、`DbtRuntimeDtos.java`、`DbtRuntimeProperties.java` |
| 发布 | `backend/.../dbt/DbtPublicationService.java` 负责 READY 校验、目录导入与指针切换；`DbtReleaseCoordinator.java` 负责 Git 观察、任务推进与重启恢复 |
| 对外 API | `DbtCatalogController.java`、`DbtReleaseController.java`、`DbtQueryController.java`；`api/openapi.yaml` 同步契约 |
| 查询与回收 | `DbtQueryService.java` 固定 release 并持久记录任务；`DbtReleaseCleanupService.java` 通过服务清理无引用 schema |
| 页面 | `frontend/src/features/dbt/` 的项目/目录/查询页，`frontend/src/api/` 的类型与客户端，`routes.tsx` 和 `PlatformLayout.tsx` |

## Tasks

### Task 1: 固定 SHA 的 dbt 项目摘要

**Files:** Modify `metric-git-sync/src/main/java/com/metricplatform/gitsync/GitSnapshotReader.java`; create `DbtProjectRevision.java`; test `metric-git-sync/src/test/java/com/metricplatform/gitsync/GitSnapshotReaderTest.java`。

**Interfaces:** 产出 `DbtProjectRevision(String sha, String projectDigest)` 与 `GitSnapshotReader.readDbtRevision(String projectSubdir)`；旧 `read()` 签名和行为不变。摘要只取规范中的 Git 路径、模式、blob ID，包括 `package-lock.yml` 和输入目录内的 dbt docs Markdown；使用有界 `ls-tree -r -z` 输出，不 checkout 或执行仓库脚本。

- [ ] 写测试 `readmeOnlyPushKeepsDigest`：两次 SHA 不同而 digest 相同；`modelYamlChangeChangesDigest` 与 `modelDocsMarkdownChangeChangesDigest`：digest 不同；`rejectsSymlinkAndCustomResourcePath`：抛固定类型错误且不泄漏路径/凭据；旧 Ossie 快照测试继续通过。
- [ ] 运行 `mvn -B -pl metric-git-sync -am -Dtest=GitSnapshotReaderTest -Dsurefire.failIfNoSpecifiedTests=false test`，确认新增测试先失败。
- [ ] 实现 `readDbtRevision`，固定并验证 projectSubdir，检查 `dbt_project.yml`、Git 文件模式和资源路径配置；对不支持的 dbt 输入路径报错，Markdown 与生成目录不计摘要。
- [ ] 重跑该命令；所有 `GitSnapshotReaderTest` 通过。

### Task 2: 发布、目录与查询任务持久化

**Files:** Create `backend/src/main/resources/db/migration/V12__dbt_semantic_release.sql`、`backend/src/main/java/com/metricplatform/dbt/DbtReleaseStore.java`、`DbtCatalogStore.java`、`DbtQueryStore.java`；test `backend/src/test/java/com/metricplatform/dbt/DbtPersistenceTest.java`。

**Interfaces:** `DbtReleaseStore.observe(String projectId, DbtProjectRevision revision, String configVersion)` 返回 `ObservationResult(kind, releaseId, reusableRunId)`，kind 为 `SKIPPED/START/REUSE`；`DbtReleaseStore.active(String projectId)` 返回当前 release；`DbtQueryStore` 保存 queryId、releaseId、幂等键、服务任务 ID 与状态；`semantic_release.cleanup_state` 记录待清理、清理中、已清理。`configVersion` 覆盖固定 profile、编译环境变量和工具链等会改变构建结果的外部输入。存储方法不调用远端服务。

- [ ] 写迁移和仓储测试：五张表及字段注释、清理状态存在；同摘要 README 提交只更新 observedSha；确定性失败后同摘要提交也跳过；重复事件不重复创建 release；`SELECT FOR UPDATE` 下并发观测不产生两个 active 指针；非终态查询可按 releaseId 计数。
- [ ] 运行 `mvn -B -pl backend -am -Dtest=DbtPersistenceTest -Dsurefire.failIfNoSpecifiedTests=false test`，确认先失败。
- [ ] 添加 Flyway V12 和三个小型 JDBC store；对项目绑定、release 状态与项目摘要加数据库约束/索引，保持旧表不变。
- [ ] 重跑该命令，确认 PostgreSQL 集成测试通过。

### Task 3: 外部运行服务的结构化客户端

**Files:** Create `backend/src/main/java/com/metricplatform/dbt/DbtRuntimeClient.java`、`DbtRuntimeDtos.java`、`DbtRuntimeProperties.java`、`HttpDbtRuntimeClient.java`；test `backend/src/test/java/com/metricplatform/dbt/HttpDbtRuntimeClientTest.java`。

**Interfaces:** `submitRun(RunRequest) -> RunReceipt`、`getRun(UUID runId) -> RunSnapshot`、`getRunByKey(UUID idempotencyKey) -> RunSnapshot`、`getCatalog(UUID runId) -> CatalogSnapshot`、`submitQuery(QueryJobRequest) -> QueryReceipt`、`getQuery(UUID queryId) -> QuerySnapshot`、`getQueryByKey(UUID idempotencyKey) -> QuerySnapshot`、`cleanup(UUID runId)`；HTTP 路径与 spec 第 3 节一致。构建请求只含固定 projectId、SHA、digest、profileBindingId、configVersion 和幂等键；查询请求只含固定运行版本、幂等键和类型化参数。

- [ ] 写 HTTP 桩测试：请求不含远端/凭据；提交超时后按幂等键找回已创建任务；401/422 是确定性错误，429/502/503/504 是瞬时错误；READY 缺少 schema、摘要或 capability 时反序列化/校验失败。
- [ ] 运行 `mvn -B -pl backend -am -Dtest=HttpDbtRuntimeClientTest -Dsurefire.failIfNoSpecifiedTests=false test`，确认先失败。
- [ ] 使用现有 Spring HTTP 能力实现客户端；限制响应体、超时和错误摘要，日志只记录任务 ID、状态及脱敏代码；不添加新依赖。
- [ ] 重跑该命令；桩服务协议测试通过。

### Task 4: READY 产物发布事务

**Files:** Create `backend/src/main/java/com/metricplatform/dbt/DbtPublicationService.java`；test `backend/src/test/java/com/metricplatform/dbt/DbtPublicationServiceTest.java`。

**Interfaces:** `promote(UUID releaseId, DbtProjectRevision verifiedHead, RunSnapshot run, CatalogSnapshot catalog) -> boolean`。使用 Task 2 的 store；先验证目标/产物，事务内保存完整资源与依赖，再 CAS `activeReleaseId`，失败返回 false 或将候选置 FAILED/SUPERSEDED，不能暴露半份目录。复用 A 的构建发布 README-only 的 B 时，运行产物 SHA 对照 `buildSourceSha=A`，发布目标对照 `targetCommitSha=B`，项目摘要和配置版本必须一致。

- [ ] 写测试：READY 匹配且全测试/代表性查询通过才发布；摘要、schema、profile/工具链、query capability 任一不符不切换；A 结果可服务 README-only 的 B 且发布记录保留双 SHA；已观测 B 内容时 A 标 SUPERSEDED；目录写入中断时旧目录仍可读。
- [ ] 运行 `mvn -B -pl backend -am -Dtest=DbtPublicationServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`，确认先失败。
- [ ] 实现校验、稳定 resourceId、候选目录导入与项目行锁/CAS；外部 HTTP/Git 读取保持在事务外，删除或改名只影响新 release 的投影。
- [ ] 重跑该命令；数据库原子性与判旧测试通过。

### Task 5: Git 观察、构建调度与恢复

**Files:** Create `backend/src/main/java/com/metricplatform/gitsync/GitRefreshTrigger.java`、`backend/src/main/java/com/metricplatform/dbt/DbtReleaseCoordinator.java`；modify `gitsync/GitSyncProperties.java`、`GitSyncConfiguration.java`、`GitSyncCoordinator.java`、`GitWebhookController.java`、`GitSyncStatusController.java`；test `backend/src/test/java/com/metricplatform/dbt/DbtReleaseCoordinatorTest.java` and existing gitsync tests。

**Interfaces:** `GitRefreshTrigger.requestRefresh()`；旧 `GitSyncCoordinator` 和新 `DbtReleaseCoordinator` 二选一实现。`metric-platform.git-sync.mode` 默认 `OSSIE`，显式 `DBT` 启用新链路；DBT 固定 projectId、projectSubdir、profileBindingId，仍沿用原 HMAC Webhook 与轮询。`DbtReleaseCoordinator.refresh()` 和 `resumePending()` 只推进持久候选。

- [ ] 写测试：README-only 跳过；确定性失败后 README-only 提交不触发重试；A 构建中 B 内容变化则 B 全量新建、A 过时；A 构建中 B 只改 README 则复用 runId 且 targetCommitSha=B/buildSourceSha=A；重复 webhook、重启和任务结果未知均幂等；旧 OSSIE 默认模式行为不变。
- [ ] 运行 `mvn -B -pl backend -am -Dtest=DbtReleaseCoordinatorTest,GitSyncCoordinatorTest,GitWebhookControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`，确认新增测试先失败。
- [ ] 实现模式装配、固定 SHA 运行与状态轮询；瞬时故障最多 3 次退避重试，定义/测试错误不重试；调用 Task 4 的 `promote` 前重新读取 `main`。
- [ ] 重跑该命令；两种模式及恢复测试通过。

### Task 6: 发布与目录只读 API

**Files:** Create `backend/src/main/java/com/metricplatform/dbt/DbtCatalogController.java`、`DbtReleaseController.java`、`DbtCatalogService.java`；modify `api/openapi.yaml`、`backend/src/test/java/com/metricplatform/api/OpenApiContractTest.java`；test `backend/src/test/java/com/metricplatform/dbt/DbtCatalogControllerTest.java`。

**Interfaces:** `GET /api/v1/semantic-projects`、`/{projectId}/catalog`、`/{projectId}/resources/{resourceId}`、`/{projectId}/resources/{resourceId}/lineage`、`/{projectId}/releases`、`/{projectId}/releases/{releaseId}`。默认目录只读 activeReleaseId；显式 releaseId 必须属于该项目；已清理版本的目录返回 410，发布历史仍显示清理状态，跨项目版本返回 404。

- [ ] 写控制器和 OpenAPI 契约测试：认证生效、分页/血缘限额、候选不可见、旧 release 可按 ID 读、错误版/跨项目 ID 拒绝、无 POST/PATCH/DELETE 定义接口。
- [ ] 运行 `mvn -B -pl backend -am -Dtest=DbtCatalogControllerTest,OpenApiContractTest -Dsurefire.failIfNoSpecifiedTests=false test`，确认先失败。
- [ ] 实现只读查询 DTO 与 Controller，并同步 `api/openapi.yaml`；旧路径数量断言只按新增路径更新，不弱化旧契约断言。
- [ ] 重跑该命令；API 和契约测试通过。

### Task 7: 固定 release 的异步查询与预览

**Files:** Create `backend/src/main/java/com/metricplatform/dbt/DbtQueryService.java`、`DbtQueryController.java`、`DbtQueryDtos.java`；modify `api/openapi.yaml`；test `backend/src/test/java/com/metricplatform/dbt/DbtQueryControllerTest.java` and `OpenApiContractTest.java`。

**Interfaces:** `submit(String projectId, UUID releaseId, QueryMode mode, TypedQuery request) -> UUID queryId`；`get(String projectId, UUID queryId) -> QueryStatus`。`QueryMode`、`TypedQuery`、`QueryStatus` 定义在 `DbtQueryDtos.java`。新增 `POST /queries:explain`、`POST /queries`、`GET /queries/{queryId}`、`POST /datasets/{resourceId}:preview`，提交均返回 202；只将类型化指标、维度、过滤和有限 limit 转发到 Task 3 客户端。

- [ ] 写测试：提交前在 release 行锁下持久固定 releaseId，清理认领后不能接收新查询；发布切换后旧查询继续使用原运行版本；非法 MetricFlow 组合返回结构化诊断；Decimal/大整数保持字符串与类型；跨项目/已清理 release 拒绝；预览不接受 SQL 和越界 limit。
- [ ] 运行 `mvn -B -pl backend -am -Dtest=DbtQueryControllerTest,OpenApiContractTest -Dsurefire.failIfNoSpecifiedTests=false test`，确认先失败。
- [ ] 实现查询任务状态、幂等键与服务任务 ID 持久化、脱敏审计、结果代理及按幂等键超时恢复；旧 `QueryController` 和自有编译器不改行为。
- [ ] 重跑该命令；查询、精度和 OpenAPI 契约测试通过。

### Task 8: 无引用版本回收

**Files:** Create `backend/src/main/java/com/metricplatform/dbt/DbtReleaseCleanupService.java`；test `backend/src/test/java/com/metricplatform/dbt/DbtReleaseCleanupServiceTest.java`。

**Interfaces:** `cleanupEligible(String projectId) -> int` 查找非当前、非上一成功且无非终态查询的 release；共享同一 runId 的 release 视为一个物理引用组，组中任何版本受保护则不清理 schema；在 release 行锁下将整组认领为 `CLEANING`，从此拒绝该组新查询；仅通过 `DbtRuntimeClient.cleanup(runId)` 请求外部服务清理，成功后写 `CLEANED`，失败回到待清理；周期任务不在请求路径执行。

- [ ] 写测试：当前/上一成功/在途查询版本不清理；A/B 复用同 runId 且 B 已发布时不得清理 A 的物理 schema；查询受理与清理认领并发时只能一方成功；服务清理失败保留元数据并可重试；失败或 SUPERSEDED 候选可清理；清理后新查询返回 410。
- [ ] 运行 `mvn -B -pl backend -am -Dtest=DbtReleaseCleanupServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`，确认先失败。
- [ ] 实现持久清理标记和周期任务；先获得服务清理确认，后标记已清理，绝不由平台直接执行 StarRocks DDL。
- [ ] 重跑该命令；保留策略与幂等清理测试通过。

### Task 9: 平台控制台的新 dbt 页面

**Files:** Create `frontend/src/features/dbt/DbtProjectsPage.tsx`、`DbtCatalogPage.tsx`、`DbtQueryPage.tsx` and focused tests；modify `frontend/src/api/types.ts`、`metricPlatformApi.ts`、`metricPlatformApi.test.ts`、`frontend/src/routes.tsx`、`layout/PlatformLayout.tsx`。

**Interfaces:** 调用 Task 6/7 的新 API；项目页区分 observedSha 与 activeReleaseId，目录页固定 releaseId 并显示定义来源/血缘，查询页提交 202 后轮询并展示版本、状态、类型化结果；新页面无定义写入口。

- [ ] 写 API 和页面测试：只读目录无编辑按钮、Git 已推送但未发布显示两种状态、查询固定 catalog 返回的 releaseId、Decimal 保持字符串、410 提示版本已清理。
- [ ] 在 `frontend/` 运行 `npm test -- src/features/dbt src/api/metricPlatformApi.test.ts`，确认新增测试先失败。
- [ ] 实现独立 dbt 页面、路由与导航；保留旧数据集/指标/维度表单和查询工作台原行为。
- [ ] 重跑测试，再运行 `npm run build`；测试、TypeScript 与格式检查通过。

### Task 10: 端到端回归与发布门槛

**Files:** Create `backend/src/test/java/com/metricplatform/dbt/DbtPublicationFlowTest.java`；modify `backend/src/test/java/com/metricplatform/api/OpenApiContractTest.java` only if needed and `docs/` 中的运行说明。

**Interfaces:** 用临时 Git 仓库、PostgreSQL 测试容器和假服务串起观察→全量运行→READY→事务发布→目录→查询→回收；真实 StarRocks 与真实 MetricFlow 另作上线前验收，不用假服务测试代替。

- [ ] 写完整流程测试：首次发布、第二次 YAML-only 全量构建、README-only 跳过、A/B 并发、FAILED 保留旧版、查询固定版本、重启恢复与清理；断言旧 Ossie 与 `/api/v1/queries` 契约仍有效。
- [ ] 在 `metric-platform/` 运行 `mvn -B verify`；在 `frontend/` 运行 `npm test` 与 `npm run build`，修复本计划引入的问题。
- [ ] 对照 spec 逐项复核 `api/openapi.yaml`、日志脱敏、README/部署开关。另在真实 StarRocks 上验收 MetricFlow 聚合/联接/派生指标与完整构建、独立 schema、relation 指向；若外部服务尚不具备能力，只记录阻塞项并保持 DBT 模式关闭，不能用假服务测试代替验收。
- [ ] 按工作区规则检查根仓库和 `metric-platform` 的状态；执行计划获授权且全部任务完成后，在归属仓库创建一个清晰描述本计划的 commit，不提交无关变更。
