# 执行环境内的业务本体维护 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 租户管理者从一个执行环境出发，基于当前已部署数据集定义对象、属性和关系，保存后在该环境的拓扑图中看到结果。

**Architecture:** metric-app 解析可信租户和环境、核实当前部署并组织写入。metric-core 定义数据契约，metric-persistence 使用官方 Java Driver 在共享 Neo4j 内保存完整本体。React 页面只持有当前环境的编辑状态。

**Tech Stack:** JDK 21、系统 Maven、Spring Boot、Neo4j、React/Vite、Ant Design、已有 ECharts、JUnit/Testcontainers、Vitest 和 Playwright。

**Spec:** [执行环境内的业务本体维护](../specs/2026-11-20-environment-ontology-design.md)，2026-11-20 已确认。

## Global Constraints

- 一租户一管理者；每个租户的每个环境拥有独立逻辑范围。隔离覆盖每个读写入口、端点和属性归属。
- 图库唯一保存本体。PostgreSQL 继续维护平台身份、环境、资产登记和授权，沿用已有 Mapper；dbt-service 仅使用已公开接口。
- 每个对象绑定一个稳定 datasetAssetId，声明一个单字段主键。边使用两端属性 ID，支持一对一、多对一和一对多。
- 每次保存立即生效，首版交付定义维护和拓扑展示。编辑序号用于并发控制，buildId 仅用于本次绑定核验。旧产物回收后仍可打开和修复定义。

## Review Focus

| 条件 | 用户应看到的结果 | 归属 |
| --- | --- | --- |
| 两个窗口同时首次编辑空环境 | 一笔提交、一次序号增长，另一笔明确提示刷新 | 任务 1、2 |
| 同一对对象间有两条不同角色的边 | 删除“收货人”保留“下单客户”，改显示名保留两端身份 | 任务 2、5 |
| 当前构建没有声明旧字段，或者只缺类型 | 按证据显示未核实；已有明确类型冲突才标记修复 | 任务 3 |
| 部署、重新绑定与慢速复核交错 | 旧检查不能覆盖新绑定或冒充新构建结果 | 任务 3、4 |
| 切换环境后旧请求返回，或直接提交外部端点 | 旧数据显示不到新环境，服务端拒绝写入 | 任务 2、4、5 |

## 文件和验证约定

Java 路径以下列根目录为准，每个类名对应同名文件。C = `backend/metric-core/src/main/java/com/metricplatform`，P = `backend/metric-persistence/src/main/java/com/metricplatform`，A = `backend/metric-app/src/main/java/com/metricplatform`。对应测试路径将 `src/main` 换为 `src/test`。F = `frontend/src`。按下述顺序交付，每项通过后只提交该项文件。

后端定向验证从仓库根用 PowerShell 执行，先定义下面的会话函数。前端 npm 命令从 `frontend/` 执行。真实图库和 PostgreSQL 集成测试需要 Docker。

```powershell
function Test-MetricModule($module, $cases) {
    mvn -f ./backend/pom.xml -B -pl $module -am "-Dtest=$cases" '-Dsurefire.failIfNoSpecifiedTests=false' test
}
```

### 任务 1 · 一个能独立读写的环境图

**文件** 新建 C/ontology/OntologyContracts.java、C/ontology/OntologyStore.java、P/persistence/ontology/Neo4jOntologyStore.java、P/persistence/ontology/OntologyGraphConfiguration.java 及对应 Neo4jOntologyStoreTest.java。修改 backend/metric-persistence/pom.xml、docker-compose.yml、backend/metric-app/src/main/resources/application.yml。

**接口** 契约采用 Scope(tenantId, environmentId)、Topology(revision, objects, relations)、ObjectType、Property、Relation。ID 为 UUID，tenantId 沿用 String。存储端口提供 `Topology read(Scope scope)`、`long createObject(Scope scope, long expectedRevision, ObjectType object)` 和参数、返回值相同的 updateObject。创建与更新由路由分别调用，已有 ID 的创建和目标不存在的更新均返回明确错误。

- [ ] 写真实图库测试：空范围 `assertEquals(0, read(scope).revision())`；在测试环境创建订单及主键后，生产环境和另一租户仍为空；假造重复属性 ID 应整笔回滚。
- [ ] 运行 `Test-MetricModule metric-persistence Neo4jOntologyStoreTest`，确认失败位于缺失的图存储行为。
- [ ] 引入官方驱动、真实测试容器及 Compose 持久卷，固定经过兼容验证的镜像标签；凭据使用部署注入。建立命名约束，覆盖环境组合键和作用域中的对象、属性及边 ID。应用 Graph Driver 延迟连接，幂等初始化失败可重试，避免图库不可用导致整个平台启动失败。
- [ ] 实现读空图、对象与属性同事务写入。按唯一环境键定位或创建范围节点，在其写锁下读取并比较 revision，逐项验证非空归属再提交。两个线程持相同 revision 首写时断言恰有一个成功，另一个是并发冲突。
- [ ] 重跑该命令，要求实际执行图库用例且零失败。提交 `feat: persist environment ontology in Neo4j`。

### 任务 2 · 可独立维护的对象与具名关系

**文件** 扩展任务 1 的契约、存储端口和实现，新增同测试包 OntologyMutationsTest.java。

**接口** 所有写方法共享 `Scope, long expectedRevision` 前缀，返回新序号。补充 createRelation/updateRelation(..., Relation)、deleteRelation(..., UUID relationId)、deleteObject(..., UUID objectId)。ObjectType 包含 datasetAssetId、primaryPropertyId、带稳定 ID 的属性列表；Relation 包含稳定 ID、两个对象 ID、其各自的属性 ID、名称、说明和 cardinality。源、目标的次序定义方向。

- [ ] 写测试建立“下单客户”和“收货人”，断言 `assertEquals(2, read(scope).relations().size())`；改名仍保留 ID，删一条边只剩另一条。补充直接传入异租户、异环境、同域但属性属于另一对象的用例，均不得改变图或序号。
- [ ] 运行 `Test-MetricModule metric-persistence OntologyMutationsTest` 观察预期失败。
- [ ] 在环境写锁内同时核对两端及属性、单主键和声明基数；检测对象、属性、整数据集换绑的引用冲突，返回本范围内阻止修改的关系 ID/名称。主键替换在同一事务完成。引用解除后允许重新绑定，保留对象和沿用的属性 ID，重新验证每项映射。
- [ ] 验证带引用删除返回冲突且节点完整、删边后可删对象、旧窗口写入被拒。运行 `Test-MetricModule metric-persistence 'OntologyMutationsTest,Neo4jOntologyStoreTest'`，确认两类测试全绿。提交 `feat: maintain scoped ontology relationships`。

### 任务 3 · 当前部署的候选与绑定复核

**文件** 新增 A/catalog/OntologyBindingService.java、对应测试 OntologyBindingServiceTest.java；按需扩展 C/ontology/OntologyContracts.java、OntologyStore.java 及 Neo4jOntologyStore.java。复用 A/catalog/DeployedAssets.java、C/catalog/AssetGrantRepository.java、C/catalog/BuildGateway.java。

**接口** `datasets(Scope)` 返回当前 buildId 和可选资产；`columns(Scope, UUID assetId, UUID buildId)` 返回声明列和可选类型；`check(Scope, Topology)` 返回逐对象、属性和关系的 BindingReport(buildId, revision, findings)。状态包括 COMPATIBLE、UNVERIFIED、NEEDS_REPAIR，另用 reason 区分无部署、缺少完整性证据及上游故障。复核不改业务编辑序号。

- [ ] 写用例覆盖相同资产新构建、其他环境同名数据集、缺列声明、列类型未知、确定类型冲突、无部署、慢检查与新编辑交错。典型断言为 `assertEquals(UNVERIFIED, undeclaredColumn.status())`、`assertEquals(NEEDS_REPAIR, incompatibleType.status())`。
- [ ] 运行 `Test-MetricModule metric-app OntologyBindingServiceTest` 确认缺失行为。
- [ ] 按可信环境和已登记部署目标核对实时活动构建，使用 DeployedAssets.current 校验归属和登记，按稳定 assetId 从固定构建成员解析唯一数据集，通过 BuildGateway.resource 获取其声明列；核对响应上下文。目标匹配歧义返回诊断，不能按同名表猜测。
- [ ] 区分已有列但无类型、列未声明、模型不可达和明确不兼容。声明列可用于创建，缺少类型时允许带“未核实”保存。缺列仅在输入有可信的完整性证据时判移除；当前公开协议无该证据则保留未核实。主键唯一性和基数继续显示为使用者声明。
- [ ] 检查结果按范围、构建和编辑序号隔离，写回附加 revision 条件，返回或复用时核对当前构建；失败状态可再次复核。新增/改绑保存前再次核对来源构建。纯改名、说明和删除可在上游离线时完成。运行上面的测试和图库条件写回测试，验证旧结果不能覆盖新编辑。提交 `feat: verify ontology bindings against current deployment`。

### 任务 4 · 环境管理 API 和契约

**文件** 新增 A/ontology/OntologyService.java、A/ontology/api/OntologyController.java、对应测试 OntologyControllerTest.java；修改 api/openapi.yaml 和 A 对应测试包 api/OpenApiContractTest.java。复用 EnvironmentService.get(UUID)、PlatformPrincipal.current().requireManager()。

**接口** 根路径 `/api/dbt/environments/{environmentId}/ontology`。GET 根返回 revision、objects、relations、bindingReport；GET /datasets 和 GET /datasets/{assetId}/columns?buildId=… 分别返回任务 3 的候选。POST /objects、/relations 创建；PUT、DELETE /objects/{id}、/relations/{id} 修改删除。写操作传 expectedRevision（DELETE 为查询参数），涉及绑定的表单传 sourceBuildId。成功写返回新序号及资源 ID，前端重读图。创建属性由客户端生成稳定 UUID，便于同一表单引用主键；现有归属由后端核对。

- [ ] 用管理者、同租户只读账号、另一租户管理者写 HTTP 用例，覆盖成功、401、403、资源范围外 404、序号/引用/部署切换 409、非法映射 422、图库不可用 503。断言错误响应和引用清单不泄露其他范围的资源。
- [ ] 运行 `Test-MetricModule metric-app 'OntologyControllerTest,OpenApiContractTest'`，确认新路径和行为失败。
- [ ] 每个管理入口先认证、验证管理者、读取租户内环境，派生 Scope，再调用任务 1–3。上游暂不可用时，已有图保留、检查显示未核实；依赖候选的改绑返回可重试错误。图库不可用只影响本体路由。明确结构化错误字段和各状态，并同步 OpenAPI。
- [ ] 重跑上述测试，加断言部署切换后提交旧候选失败、上游失联仍可纯改名。验证打包契约包含新增路径。提交 `feat: expose authenticated environment ontology APIs`。

### 任务 5 · 环境内的建模页面

**文件** 修改 F/routes.tsx、F/features/environments/EnvironmentDetailPage.tsx；新增 F/api/ontologyApi.ts、F/features/ontology/OntologyPage.tsx、ObjectEditor.tsx、RelationEditor.tsx、OntologyGraph.tsx（后三者同目录）、OntologyPage.test.tsx。

**接口** 页面路由 `/dbt/environments/:environmentId/ontology`。API 客户端封装任务 4 的方法，调用时显式传 environmentId 和页面已读序号。对象表单只接收当前环境数据集和声明列；关系表单只接收当前图。图视图用已有 ECharts 的有向 graph，以 relationId 区分并行边，同时提供对象、关系可操作列表。

- [ ] 写页面测试覆盖管理员入口、只读账号不可进入、无部署提示、主键选择、两条不同角色的连线、冲突提示及修复映射。切环境时保留或明确放弃未提交修改，并模拟旧请求在新图加载后才返回，断言 `expect(screen.queryByText('仅测试环境订单')).not.toBeInTheDocument()`。
- [ ] 运行 `npm test -- src/features/ontology/OntologyPage.test.tsx`，确认测试定位到新增流程。
- [ ] 在环境详情加入入口，页面随 environmentId 重新挂载。所有候选和请求以环境作键，核对响应代次。对象表单一次提交属性和主键；关系表单显示两端方向、名称和声明基数。拒绝删除时展示引用；候选过期或并发冲突保留输入并提示刷新。打开/刷新图触发任务 3 的按需复核。
- [ ] 重跑上述用例、`npm test -- src/features/environments/Environments.test.tsx` 和 `npm run build`。确认 UI 可以用列表完成与图点击等价的操作。提交 `feat: edit ontologies within an environment`。

### 任务 6 · 交付验收和备份恢复

**文件** 新增 frontend/e2e-publication/environment-ontology.spec.ts；真实集成用例位于 A 对应测试包 integration/ontology/EnvironmentOntologyIntegrationTest.java；更新 README.md 的启动、验收和图库恢复说明。

- [ ] 先建立可重复的测试流：测试环境内建订单、客户和两条具名边，再从生产环境验证不可见。浏览器走真实路由和页面；后端集成用真实 Neo4j/PostgreSQL、Mapper 准备平台夹具，仅对 dbt HTTP 边界使用固定构建响应。
- [ ] 运行 `Test-MetricModule metric-app EnvironmentOntologyIntegrationTest` 及 `npm run test:e2e -- --config=playwright.publication.config.ts environment-ontology.spec.ts`。若首次发现功能缺口，保留失败断言，在对应任务的实现内修复后重新运行。
- [ ] 在隔离测试卷写入有绑定的环境图，按锁定 Neo4j 版本的官方运维流程备份，恢复到全新测试卷；用同一读取 API 比较节点与边 ID、字段映射、作用域和序号。停止测试图库后验证原平台目录可用，本体路由返回 503。将实际验证过的恢复命令记录到 README。
- [ ] 从仓库根运行 `mvn -B verify`，从 frontend 运行 `npm test`、`npm run build` 和上述端到端用例。记录实际运行结果和必要环境条件。提交 `test: verify environment ontology isolation and recovery`。

## 交付顺序

任务 1–2 完成后可独立验收图库隔离和关系编辑。任务 3–4 完成后可通过受控 HTTP 验收部署复核与权限。任务 5–6 完成后验收用户操作和恢复。每个阶段复用前一阶段的接口，最终验收以运行证据为准。
