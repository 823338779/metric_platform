# dbt-service 统一发布与平台只读代理实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将发布权威、类型化目录和查询受理统一到 dbt-service，使指标平台直接代理已发布资源，无发布后导入步骤。

**Architecture:** dbt-service 在发布前生成完整展示快照，在同一个 PostgreSQL 事务中封存产物、记录发布并切换活动指针。指标平台只认证、转接和展示；查询固定版本，由服务将资源 ID 转换为 MetricFlow 参数。先建立全构建发布闭环，再增加物理复用，最后迁移平台读取入口。

**Tech Stack:** Python、FastAPI、psycopg2、dbt、MetricFlow；Java 21、Spring Boot、React、TypeScript、PostgreSQL、StarRocks。使用现有锁定依赖。

**Spec:** [已确认设计](../specs/2026-09-30-artifact-driven-platform-catalog-design.md)。执行前同时阅读设计、工作区及各归属仓库 AGENTS.md。

## Global Constraints

- 唯一发布者为 dbt-service；指标平台不新增资源镜像表、目录缓存、导入状态或发布确认回调。
- 构建模式为 SEMANTIC_ONLY、SELECTIVE_BUILD、FULL_BUILD；各模式都产生完整新快照。
- 发布状态为 PREPARING、BUILDING、VALIDATING、PUBLISHED、FAILED、SUPERSEDED；执行 READY 不等于 PUBLISHED。
- 资源类型为 METRIC、DIMENSION、SEMANTIC_MODEL、TABLE、VIEW；不新增重复 DATASET。
- 历史版本允许查看发布记录；非活动版本的目录和新查询返回 410；已受理查询继续完成。
- 首版不做行级 incremental、不自动删表、不新增人工回滚入口、不修改共享活动物理对象。
- 不创建 worktree，不修改 vendor，不修改 Data Agent；产品保持独立构建。
- 增量字段和逻辑块按仓库规则注释；字符串常量遵循现有要求。中文文档，禁止真实凭据。
- 工作区已有大量无关暂存及未暂存变更。开始执行时保存差异基线；仅提交本计划产生的文件或差异块，禁止 git add . 和直接提交现有整个索引。
- 本文件仅为计划。执行完成后按工作区规则分别提交受影响产品仓库；不在撰写计划时提交，不自动固定根仓库 Git 链接。

## Review Focus

1. 发布提交成功但响应丢失：重读权威记录，不能误标 FAILED（任务 3）。
2. 活动版本切换与旧查询受理竞争：同一项目锁串行化，先受理查询继续执行（任务 6）。
3. 复用视图隐含旧上游依赖：变更上游时重建受影响下游，保护物理对象引用（任务 4）。
4. 同名维度、多 join 路径及合成 metric_time：不按名称合并，不接受任意原生路径（任务 1、6）。
5. 平台断网、目录读取失败及前端乱序响应：不伪造空目录、不改变发布状态、不混合版本（任务 7、8）。

## 文件与职责

下列路径均相对工作区；S 表示 `dbt-metricflow-service/`，P 表示 `metric-platform/`。Java 路径中的包根均为 `src/main/java/com/metricplatform/`，测试使用对应模块 `src/test/java/com/metricplatform/`。

| 文件 | 操作与职责 |
| --- | --- |
| S/src/dbt_metricflow_service/publication_models.py | 新增：发布、展示目录、查询的稳定类型契约 |
| S/src/dbt_metricflow_service/publication_catalog.py | 新增：将已验证原生产物映射为完整类型化展示产物 |
| S/src/dbt_metricflow_service/publication_plan.py | 新增：构建模式、模型选择、直接物理绑定及证明 |
| S/src/dbt_metricflow_service/publication.py | 新增：候选输入固定、发布门槛、查询映射编排 |
| S/src/dbt_metricflow_service/publication_api.py | 新增：v2 读取、选项与查询路由 |
| S/src/dbt_metricflow_service/storage/publications.py | 新增：发布存取及参与现有事务的发布操作 |
| S/src/dbt_metricflow_service/storage/migrations/002_publication.sql | 新增：发布记录、权威指针、引用与历史身份映射 |
| S/src/dbt_metricflow_service/storage/postgres.py、jobs.py、artifacts.py | 修改：顺序迁移、事务复用、产物封存及引用保护 |
| S/src/dbt_metricflow_service/runtime.py、runtime_api.py、runtime_execution.py、worker.py | 修改：连接 v2、构建与同事务终结；保留 v1 行为 |
| S/src/dbt_metricflow_service/platform_namespace.py、platform_runs.py、platform_bindings.py、admin.py | 修改：受控绑定、分模式证明、固定输入与管理入口 |
| P/backend/metric-core/包根/catalog/PublishedCatalogGateway.java、PublishedCatalogDtos.java | 新增：平台类型化目录与查询代理端口，无原生语义解释 |
| P/backend/metric-engine/包根/engine/dbt/runtime/HttpPublishedCatalogClient.java | 新增：服务 v2 HTTP 客户端，复用既有连接配置与错误惯例 |
| P/backend/metric-app/包根/catalog/api/PublishedCatalogController.java | 新增：平台 v2 目录、发布、选项、查询门面 |
| P/backend/metric-app/包根/gitsync/GitSyncConfiguration.java、GitRefreshController.java、GitWebhookController.java | 修改：切换后停用平台发布入口 |
| P/backend/metric-app/包根/catalog/api/CatalogController.java、publication/api/ReleaseController.java、query/api/QueryController.java | 修改：v1 兼容读取路由，取消新业务对本地发布/查询写入的依赖 |
| P/frontend/src/api/types.ts、metricPlatformApi.ts | 修改：v2 类型和资源 ID 请求 |
| P/frontend/src/features/dbt/DbtProjectsPage.tsx、DbtPublicationProgress.tsx、DbtCatalogPage.tsx、DbtResourceDetail.tsx、DbtQueryPage.tsx、DbtDimensionValues.tsx | 修改：代理展示、版本固定、选项 ID 查询和纯读取刷新 |
| P/api/openapi.yaml、P/AGENTS.md、两个仓库 README.md | 修改：公开协议、发布归属、启动与迁移说明 |

若实际 Java 模块位置与上表不同，先用现有类的路径定位归属模块，不恢复工作区已删除的旧 backend-app 或 metric-runtime-client 模块。

## 统一接口决定

- `catalogSchemaVersion` 首版固定为 `1`；资源用 `kind` 判别 attributes。公共字段及各类型字段完全采用设计第 6 节；`nativeDetails` 仅为追溯信息，页面业务不解析它。
- `PublishedCatalog`：schemaVersion、projectId、releaseId、resources、relations、relationBindings。`RelationBinding`：nativeId、relation、creatorRunId、mode、definitionDigest、verifiedAt。relation 为 database/schema/identifier 三字段对象。
- 展示快照保存为 `target/published_catalog.json`，与原生产物一起摘要、封存；服务读取预生成 JSON，执行过滤后稳定排序分页，不重新解析 manifest。首版不另建逐资源数据库索引表。
- `PublicationDescriptor`：projectId、releaseId、runId、artifactSetId、publicationSequence、publishedAt、sourceSha、buildMode、catalogSchemaVersion、catalogDigest。
- 新服务接口使用 `/v2/projects`；平台使用 `/api/v2/semantic-projects`，后续路径同构。补齐项目列表 GET、单条 release GET，以及 resource 下 `/lineage`、`/source`、`/native-details`。
- `GET /{projectId}/publication` 返回 `{projectId, activePublication: descriptor|null}`；未首次发布不能返回伪造的空版本。
- 目录分页 `page>=1`、`1<=size<=200`，默认 1/50；q 按名称、展示名、说明不区分大小写匹配；kind 精确筛选；resourceId 稳定升序。
- `QueryOptionsRequest`：releaseId、metricResourceIds。响应含 releaseId、metricResourceIds、options；选项含 optionId、可空 resourceId、displayName、granularities、operators。
- `PublishedQueryRequest`：releaseId、idempotencyKey、mode、metricResourceIds、groupBy（optionId、可空 grain）、filters（optionId、operator、value）、startTime、endTime、orderBy（fieldId、direction）、limit、可空 datasetResourceId、可空 dimensionOptionId。mode 沿用 QUERY/EXPLAIN/PREVIEW/DIMENSION_VALUES；limit 沿用 1..10000，默认 1000。fieldId 只能引用当前已选指标或分组选项。
- 选项身份采用固定版本、排序去重后的指标集合和引擎原生选项身份的规范 JSON 摘要。服务重算合法选项并比对，不能把摘要当授权或接收客户端原生表达式。
- 错误：跨项目或未知资源 404；已替代版本 410 `release_gone`；幂等键冲突 409 `idempotency_conflict`；非法组合 422 `invalid_query_selection`；上游不可用 503 `runtime_unavailable`。服务协议违约由平台返回 502 `invalid_runtime_response`，不得修改发布状态。
- 同一项目、调用身份作用域、幂等键绑定规范化请求摘要；相同请求重试返回原 queryId，包括该版本已经过期时。不同内容复用同一键返回 409。新键必须重新检查活动版本。
- 查询响应始终包含 queryId、projectId、releaseId；结果保留现有精度、空值和 EXPLAIN 表达方式。

## 任务 1：生成可直接展示的完整目录

**文件：** 新建 publication_models.py、publication_catalog.py、tests/test_publication_catalog.py；复用 platform_catalog.py 的受支持原生格式检查。

**接口：** `build_published_catalog(*, project_id: str, release_id: UUID, native_catalog: dict, semantic_manifest: dict, relation_bindings: list[RelationBinding]) -> PublishedCatalog`；类型定义归 publication_models.py。

- [ ] 写 `test_typed_catalog_is_complete`：断言同名维度不同 resourceId、派生指标 inputMetricIds、多数据集 datasetIds、label 优先、SEMANTIC_MODEL 不重复为 DATASET；序列化后按 kind 可还原所有类型。
- [ ] 写 `test_invalid_catalog_cannot_publish`：重复 ID、悬空引用、循环依赖、未知关键指标类型及缺少必要 relation 均失败；合法空项目返回 resources=[]，缺少产物不能变成空目录。以 fixture 明确空项目标识。
- [ ] 在 S 执行 `uv run --frozen pytest -q tests/test_publication_catalog.py`，确认新增行为尚未满足；先提供最小接口壳，不能只把导入错误当作业务红灯。
- [ ] 实现上述生成器与判别类型；摘要与定义说明采用确定性规则；能力来自经过验证的绑定。补充 manifest/semantic manifest 的规范化集合一致性检查。
- [ ] 重跑该测试及 `tests/test_platform_catalog.py`，全部通过；保存固定 JSON 契约样本供任务 7 使用。

## 任务 2：建立发布存储与安全引用

**文件：** 新建 storage/publications.py、002_publication.sql、tests/test_publication_storage.py；修改 storage/postgres.py、artifacts.py、jobs.py。

**接口：** `PublicationStore.create_candidate(project_id: str, request: dict, idempotency_key: str) -> dict`、`get_publication(project_id: str) -> dict`、`get_release(project_id: str, release_id: UUID) -> dict`。跨方法业务记录使用任务 1 模型校验；store 返回既有风格 dict。

- [ ] 写数据库迁移及引用测试：从 schema v1 升级后旧数据不变，重复 migrate 无副作用，未知更高版本拒绝启动；`current_output_set_id` 与新增 `active_published_release_id` 互不代替。
- [ ] 写 `test_candidate_sequence_and_idempotency`：并发候选取得唯一项目内序号，同键同输入返回同候选，不同输入冲突；release 与项目、run、产物集归属不一致不能绑定。
- [ ] 运行 `uv run --frozen pytest -q tests/test_publication_storage.py tests/test_runtime_migration.py`，使用独立 SERVICE_TEST_DATABASE_URL；无数据库导致 skip 不算通过。
- [ ] 新增 runtime_release（状态、序号、输入、基线、run、产物集、错误及发布时间），项目活动指针及候选序号；新增 runtime_release_relation 存储直接 creatorRunId 引用；新增 runtime_legacy_identity 存储 projectId/kind/旧 ID/目标 ID 与唯一约束。只追加迁移，迁移器按顺序执行。
- [ ] 将 published_catalog.json 和步骤证明纳入产物允许列表、大小与摘要检查。所有发布包和复用绑定进入现有 GC/cleanup 保留判断；旧 cleanup 接口也不能绕过引用保护。
- [ ] 写并通过 `test_cleanup_rejects_referenced_creator`：来源版本已过期但仍被当前绑定或在途查询引用时拒绝清理。未引用暂存产物仍按现有规则处理。

## 任务 3：服务独立创建候选并原子发布

**文件：** 新建 publication.py、tests/test_publication_transaction.py；修改 runtime.py、runtime_execution.py、worker.py、storage/jobs.py、storage/publications.py、platform_bindings.py、admin.py。

**接口：** `PublicationService.submit(project_id: str, idempotency_key: str) -> dict`；`PublicationStore.publish_in_transaction(cursor, *, job_id: UUID, attempt_token: str, release_id: UUID, output_set_id: UUID) -> None`。后者只参与 JobStore.finish 已有事务，禁止内部提交或另开连接。

- [ ] 写故障注入测试：封存前、发布记录更新后、指针更新后各抛异常，断言 A 指针及目录不变、B 不可见、任务终态与发布状态不分裂。
- [ ] 写过期租约、并发 B/C、提交后断连测试：旧 worker 无权发布；最新候选语义明确为服务最近接受的输入序号；较旧候选 SUPERSEDED；提交结果未知时重读记录，B 已 PUBLISHED 即使 C 已取代也不能误标 FAILED。
- [ ] 运行 `uv run --frozen pytest -q tests/test_publication_transaction.py`，观察预期业务断言失败。
- [ ] 增加管理入口 `dbt-service-admin publish --project-id <id> --idempotency-key <key>`，由服务现有绑定固定远端允许分支的 SHA、项目摘要、配置和工具链，不接受任意客户端连接参数。首版不增加调度系统。
- [ ] 先连接 FULL_BUILD：生成并验证展示产物后进入既有 finish 事务，在统一锁顺序下核验租约、候选序号、预期活动基线、配置版本，封存并切指针。审计 reserve/finish/cleanup 锁顺序，避免项目与 job 反向加锁。普通 v1 BUILD_RUN 仍只产生 READY。
- [ ] 重跑新增测试及 `tests/test_runtime_worker.py tests/test_runtime_storage.py`。平台进程完全未启动时也必须独立发布成功。

## 任务 4：仅语义发布与选择性物理构建

**文件：** 新建 publication_plan.py、tests/test_publication_plan.py、tests/test_publication_build.py；修改 runtime_execution.py、platform_namespace.py、platform_runs.py。

**接口：** `plan_publication(current: dict, baseline: dict | None, context: dict) -> BuildPlan`；BuildPlan 定义在 publication_models.py，含 buildMode、selectedNativeIds、reuseNativeIds、reasons、relationBindings。`validate_publication_evidence(plan: BuildPlan, evidence: dict) -> dict` 返回可持久化发布证明，失败抛验证错误。

- [ ] 写模式矩阵：首发 FULL_BUILD；仅语义或 README 修改 SEMANTIC_ONLY；模型 SQL 修改选择自身和受影响下游；宏、依赖、vars、source 配置或工具链影响不能可靠界定时 FULL_BUILD。仅本次物理前缀变化不能造成全模型误判。
- [ ] 写 `test_reused_view_and_final_binding_agree`：A 上游变化后 B 下游视图重建；未变模型保持 A 实际 relation；最终 manifest、semantic manifest 与执行 SQL 一致，不出现不存在的 B 前缀对象。
- [ ] 运行 `uv run --frozen pytest -q tests/test_publication_plan.py tests/test_publication_build.py` 验证红灯。
- [ ] 将逻辑状态比较和物理别名分开；用 dbt 状态及依赖图建立选择，生成受控 alias 映射后重新解析原生及语义产物。defer 只作辅助，不能通过发布后手改 JSON 修补绑定。复用关系直接记录实际创建 run。
- [ ] 保存 build/test/semantic-validation/relation-verification/query-probe 分步骤证明；语义模式 build 明确 NOT_REQUIRED，不能伪造全量 run_results。必需测试仍执行，覆盖集合必须完整且包含已验证复用；source 是 EXTERNAL，不承诺历史数据快照。
- [ ] 写真实数据库隔离测试：候选失败前后 A 受管理表定义和内容一致。通过受控模板限制写操作并用数据库权限保护复用对象；无法提供有效隔离的 adapter/profile 拒绝复用发布。已有宏、hook 安全限制保留，不能仅检查最终 relation 名称。
- [ ] 对复用对象执行存在性与约定的新鲜度检查；缺失或不满足条件时重建或失败，理由入证明。重跑模式矩阵、原有 namespace/runs 测试及真实数据库用例。

## 任务 5：只读已发布目录 API

**文件：** 新建 publication_api.py、tests/test_publication_api.py；修改 runtime_api.py、runtime.py、storage/publications.py。

**接口：** `create_publication_router(runtime: Runtime) -> APIRouter`；路径与统一接口决定一致。同步数据库调用沿用 asyncio.to_thread，不在事件循环阻塞。

- [ ] 写 API 测试：无首次发布 activePublication=null；活动目录按 q/kind 过滤后分页；每个响应固定 releaseId；失败候选不可读目录；历史发布记录可读但目录 410；跨项目资源 404。
- [ ] 写源码测试：只从封存 SOURCE 集读取固定版本允许路径，路径穿越拒绝；不访问 Git main；native-details 保持只读追溯用途。
- [ ] 运行 `uv run --frozen pytest -q tests/test_publication_api.py` 确认红灯。
- [ ] 实现发布、目录、详情、血缘、源码和原生详情读取。每个响应按一次选定版本读取完整快照；读取时禁止业务重投影，损坏产物报告故障，不能返回空目录。
- [ ] 重跑新增及 `tests/test_runtime_api.py`，确认未改变 v1 任务接口语义。

## 任务 6：资源 ID 查询与原子受理

**文件：** 修改 publication.py、publication_models.py、publication_api.py、storage/jobs.py；新增 tests/test_publication_queries.py。

**接口：** `PublicationService.query_options(project_id: str, request: QueryOptionsRequest) -> dict`；`submit_query(project_id: str, request: PublishedQueryRequest, identity_scope: str) -> dict`；`get_query(project_id: str, query_id: UUID) -> dict`。身份作用域从受信服务绑定获取，不能信任客户端任意身份 header。

- [ ] 写选项测试：同名维度不同路径 optionId 不同；metric_time 没有伪造 resourceId；乱序指标集合产生相同选项；跨版本、非法粒度/操作符、任意原生字段及无指标上下文维度值查询均拒绝。
- [ ] 写双连接竞争测试：查询先预留再发布 B，则查询固定 A 执行；B 先发布则 A 新键 410。相同键重试取得原 queryId，不同 payload 同键 409；项目不匹配 404。
- [ ] 运行 `uv run --frozen pytest -q tests/test_publication_queries.py`，确认断言失败。
- [ ] 在事务外通过固定 run 调用现有 MetricFlow options/validate；事务内重新检查活动指针并预留 job。提取 JobStore.reserve 的同事务 cursor 内部实现，保存转换后的请求和固定 input_set_id，禁止验证后另开无锁预留事务。
- [ ] 实现 QUERY、EXPLAIN、PREVIEW、DIMENSION_VALUES；preview 必须有验证过的物理 capability。查询状态直接读服务 job；无需平台 QueryStore 落库。
- [ ] 运行新增测试及 `tests/test_platform_queries.py tests/test_runtime_api.py`；断言历史 query 状态仍可读、结果保留原 releaseId 和数值精度。

## 任务 7：指标平台 HTTP 代理与兼容接口

**文件：** 文件表中的新增 Java gateway/DTO/client/controller；修改 ApiExceptionHandler.java、PlatformSecurityConfig.java、api/openapi.yaml；新增 engine 对应 HttpPublishedCatalogClientTest、app 对应 PublishedCatalogControllerTest。

**接口：** PublishedCatalogGateway 定义 `projects()`、`publication(String projectId)`、`releases(String projectId)`、`release(String projectId, UUID releaseId)`、`catalog(String projectId, UUID releaseId, String q, String kind, int page, int size)`、`resource/lineage/source/nativeDetails(String projectId, UUID releaseId, String resourceId)`、`queryOptions(String projectId, QueryOptionsRequest request)`、`submitQuery(String projectId, PublishedQueryRequest request)`、`query(String projectId, UUID queryId)`。返回值为统一接口决定对应 DTO；资源使用判别类型，不引入新的执行语言。

- [ ] 使用任务 1 生产者 JSON 样本写契约测试，字段和值原样传递；同一个样本由服务测试验证后供 Java 测试读取副本，记录摘要以发现两端样本漂移。
- [ ] 写只读代理测试：CatalogStore、PublicationStore、QueryStore 全部禁止写入时目录与新查询仍成功；上游 410/422/503 正确传递，非法响应 502；无 token 保持现有 401 行为。
- [ ] 在 P 运行 `mvn -B -pl backend/metric-app -am test -Dtest=HttpPublishedCatalogClientTest,PublishedCatalogControllerTest -Dsurefire.failIfNoSpecifiedTests=false`，确认行为红灯。
- [ ] 实现 HTTP 客户端和 v2 controller；复用已有超时、响应大小限制及禁止重定向惯例。平台不调用 DbtArtifactPresenter，不解析 YAML、manifest 或原生表达式。
- [ ] 在 OpenAPI 定义完整 v2 schema、错误及版本字段；v1 保持原公开名称语义，兼容字段由服务发布前生成或由平台固定传输重命名，不重新业务投影。历史身份路由使用任务 9 映射，切换前不得启用新读路径。
- [ ] 重跑定向测试及现有 OpenApiContractTest、SemanticOpenApiContractTest。确认没有必须成功的本地资源写入，也没有向 dbt-service 报告“平台导入完成”的调用。

## 任务 8：页面展示与可视化查询切换

**文件：** 文件表 frontend 文件及对应 `.test.tsx`；修改 frontend/e2e/dbt-catalog.spec.ts。

**接口：** metricPlatformApi 使用 httpClient 的 `/api/v2` base，方法请求/返回采用统一契约；查询控件 value 使用 resourceId/optionId，label 使用 displayName。

- [ ] 写页面测试：用户选择指标与维度提交资源 ID/选项 ID，无须输入原生路径；切版本后重取选项，保留存在的指标，移除无效选项并明确提示。
- [ ] 写竞态和错误测试：B 请求迟于 C 返回不覆盖 C；目录/血缘失败显示读取异常；合法空目录才显示无资源；410 提示版本已更新而非已清理。
- [ ] 在 P/frontend 执行 `npm test -- --run`，确认新增目标断言失败。
- [ ] 替换目录与详情为类型化展示；原生详情仅作可选追溯。刷新按钮只重新获取发布结果，不发 Git refresh 或构建请求。发布进度展示服务候选状态，不显示导入阶段。
- [ ] 保留请求 generation 防乱序逻辑，查询结果显示固定 releaseId，读取故障保留已显示内容并标记失败；不得自动用新版本执行旧选择。
- [ ] 运行 `npm test -- --run`、`npm run build` 和 `npm run test:e2e`；e2e 验证从目录选择到结果的完整路径。

## 任务 9：单写者切换与旧身份保留

**文件：** S/admin.py、publication.py、storage/publications.py、tests/test_publication_migration.py；P 的 GitSyncConfiguration、GitRefreshController、GitWebhookController、旧 controller；新增 app 测试 PublicationProxyConfigurationTest；更新两边 README、P/AGENTS.md。

**接口：** 管理命令 `dbt-service-admin import-publication --file <path> --dry-run`，去掉 dry-run 才执行。文件含 projectId、旧 releaseId/runId、活动标识、旧 queryId 到 runtime jobId 映射；导入必须核验服务已有产物，不接受客户端传入“已验证”标志。文件不包含凭据。

- [ ] 写迁移测试：重复导入幂等；旧 ID 冲突、找不到 run、产物无法生成合法展示目录均拒绝且不切指针；已有终态查询可查询原结果。旧在途请求需映射稳定服务 job，不能重执行产生不同身份。
- [ ] 运行 `uv run --frozen pytest -q tests/test_publication_migration.py`；运行 P 的 `PublicationProxyConfigurationTest` 确认旧发布入口仍需退出。
- [ ] 实现只读导出所需映射的维护步骤及导入管理命令：dry-run 校验，实际导入仍经过完整目录验证和原子发布门槛。不能验证的当前版本需先完成服务新发布，再明确处理历史 ID，禁止静默伪造兼容。
- [ ] 增加部署切换开关 `metric.publication.mode=legacy|service-proxy`，作为短期迁移开关，不是长期双写模式。service-proxy 下不创建 ReleaseCoordinator、不调旧导入、不写本地活动指针；旧 refresh/webhook 发布请求返回明确 409 `publication_managed_by_service`。
- [ ] 文档写清顺序：暂停旧发布写入并等待在途发布终结 → 导出/校验身份 → 服务接管并核验 → 切 service-proxy → 观察查询/目录 → 禁用旧发布配置。真实环境切换另行执行，不在测试过程中操作生产数据库。
- [ ] 保留旧表、已应用 Flyway 和查询审计；兼容读取必须区分已映射服务结果与只读旧终态历史，不恢复平台新发布权力。应用回退仍由服务掌握活动指针。
- [ ] 通过迁移与 Spring 配置测试，更新 AGENTS 中旧职责及 README 操作命令。不得批量删除或弱化旧测试；需要大范围重写时按用户规则先单独说明范围。

## 任务 10：跨服务验收与交付提交

**文件：** 新增 S/tests/integration/test_publication_proxy_flow.py；补充 P/frontend/e2e/dbt-catalog.spec.ts；两边 README 补充验收前提。

- [ ] 写真实集成场景：发布 A → 平台读取并提交查询 → 仅语义发布 B → 旧查询完成而旧版新请求 410 → 选择性发布 C 验证复用 → 候选 D 故意映射失败保持 C → 平台离线期间服务发布 E → 平台恢复直接显示 E。
- [ ] 使用隔离 PostgreSQL 和测试仓库、数据库账号执行 `uv run --frozen pytest -q tests/integration/test_publication_proxy_flow.py`。环境变量遵循 S/README 现有 SERVICE_TEST_DATABASE_URL、PLATFORM_TEST_PG* 约定，不输出凭据；断言所有场景实际执行而非 skip。
- [ ] 服务执行 `uv run --frozen pytest -q`、`uv run ruff check src tests`；P 执行 `mvn -B verify`；前端执行 `npm test -- --run`、`npm run build`、`npm run test:e2e`。记录真实 adapter 验证范围，不能用 mock 通过冒充 StarRocks 隔离已验证。
- [ ] 对照设计第 14 节验收矩阵逐项记录对应测试及结果；检查所有发布成功路径都已持久化展示目录，所有平台新读取路径都无本地业务导入。
- [ ] 分别检查根仓库 `git status --ignore-submodules=none`、两产品 `git diff` 与 `git diff --cached`；仅暂存本计划差异，保留用户已有索引状态。按执行计划要求分别提交，建议消息为 `feat: publish validated catalogs atomically in dbt service`、`feat: proxy published catalogs and queries from dbt service`。不得将其他重构混入提交。

## 自审记录与执行交接

设计 1–3/5–8 节由任务 1–3/5 覆盖；构建模式第 4 节由任务 4 覆盖；代理与查询第 9–11 节由任务 5–8 覆盖；故障第 12 节贯穿任务 2–8；迁移第 13 节由任务 9 覆盖；完整验收由任务 10 覆盖。

本计划不实施调度系统、新缓存、行级增量、人工回滚或 Data Agent 改造。任务按 1→10 顺序执行；服务端稳定契约先于平台切换。实施前需评审本计划并选择执行方式；推荐本会话顺序执行，因为发布事务、绑定和查询受理共享接口与锁边界。

## 本会话执行记录（2026-09-30）

已按本会话顺序实现服务发布、类型化目录、三种构建模式、固定版本查询、平台 v1/v2 代理、页面与迁移开关。上面的原始步骤保留用于审计；实际实现与验证范围以下表为准。

| 范围 | 实现与验证 |
| --- | --- |
| 目录生成 | 判别资源类型、完整引用、同名维度、派生指标、多数据集、原生产物一致性、固定协议样本 |
| 发布存储 | 追加 002 迁移；候选序号、请求幂等、封存与指针同事务、creator 引用及 cleanup/GC 保护 |
| 发布门槛 | 固定输入与工具链核对；封存失败和指针更新后故障均回滚；响应丢失不改写已发布状态；过期租约拒绝 |
| 三种构建 | 真实 PostgreSQL 全量→语义复用→选择性构建；依赖闭包、直接绑定、编译只读 SQL、受控宏、freshness 门槛 |
| 读取与查询 | 预生成目录读取、历史新请求 410、原查询继续固定 A、同键恢复、并发同键一次受理、跨模式参数拒绝 |
| 平台代理 | v1/v2 认证转接；不读取本地资源镜像，不创建平台发布协调器或本地查询受理服务；协议错误明确返回 |
| 页面 | 资源/选项 ID 查询，目录、关系、追溯、筛选、时间、排序、预览、SQL、维度值；版本与请求乱序保护 |
| 迁移 | 现有服务发布的同 run 身份映射、dry-run 与幂等；不验证通过的旧 READY run 保留历史，先服务新发布 |
| 独立审查 | 2 个 Important 均以回归复现后修复：嵌套模型 FQN 和提交期间刷新竞态；复核这些修复及幂等竞态修复后，无剩余 Critical/Important |

验证证据：

- 服务新增范围 45 项测试通过，`ruff check src tests` 通过。
- 真实 dbt/MetricFlow + PostgreSQL 场景通过：A 查询受理后发布 B、旧请求拒绝、旧查询完成、子目录模型选择性发布 C、非法 D 不切指针且旧物理表数据未改变、恢复 E 后消费者直接读取。最终完整场景运行耗时 94.64 秒。此处未运行真实 Java 服务，也未验证 StarRocks adapter，不能称为生产全链路或 StarRocks 验收。
- 前端 63 项测试通过（并发全套曾出现旧页面的等待超时，串行重跑全套通过）；构建通过；独立代理模式 Playwright 场景通过。
- 后端 `mvn -B verify -Dspring-boot.repackage.skip=true` 通过。标准 `verify` 的测试通过，重打包因已有 Java 进程占用目标 JAR 失败；未终止该进程。
- 服务全套执行结果为 318 通过、4 失败、4 环境门槛跳过；新增幂等竞态修复后相关 9 项再验证通过。4 项失败均为基线：3 项 cleanup fixture 缺少 parent_run_id，已用 HEAD 实现复现；vendor 测试预期 05551f73，而 HEAD 已固定 5ccfcc45。未修改 vendor 或弱化旧测试。
- legacy Playwright 1 项失败、1 项环境门槛跳过：HEAD 页面链接为“查看当前目录”，HEAD 测试寻找“查看目录”。保留原行为与测试，未混入修复。

执行中的具体取舍：

1. 遵守工作区规则，直接在现有 develop 上修改，不建 worktree；使用独立临时 Git 索引提交本任务，保留既有暂存差异，不更新根仓库 Git 链接。
2. Java 以 JsonNode 透明传递服务目录，仅检查稳定协议结构；Pydantic 负责发布时类型验证，不复制原生 manifest 解释器。
3. 新代理页面和独立 Playwright 配置由迁移开关选择，保留原页面供迁移前使用；部署时前后端开关必须同步。
4. 首版保留全部发布创建者 run，不自动物理清理；存储增长是明确的运营成本。
5. 受控表达式模板限制宏能力，禁止数据库命令、动态调用、自定义物化及写入钩子；它不提供任意不可信项目的沙箱。数据库函数须由部署侧信任，宏能力扩大需另行验证。
6. 不将未验证的旧产物伪装为发布；无法接管的旧 run 用新服务发布替代活动版本，旧身份保留只读历史。
7. 修改 OpenAPI 路由数断言是新增 12 条 v2 路由的契约变更，同时逐条增加断言；原 v1、认证和禁用旧域接口断言保留。

本次未执行生产迁移或开启部署开关，未推送远端。
