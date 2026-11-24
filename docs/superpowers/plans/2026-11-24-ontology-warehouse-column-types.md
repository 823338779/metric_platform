# 指标平台按需核实本体字段类型实施计划

> 执行者按复选框逐项完成。分任务实施与评审使用 `superpowers:subagent-driven-development`，同一会话连续实施使用 `superpowers:executing-plans`。

**目标**　在管理者选择数据集、创建或改绑本体、复核拓扑时，提供可追溯到一次 StarRocks 物理列观察的类型证据，并且能安全处理漂移和暂时无法核实的情况。

**架构**　指标平台通过已有 dbt-service API 确认部署、构建快照及模型资源，在平台后端授权并读取 StarRocks 列元数据。Neo4j 保存观察、比较基准及报告。前端通过现有本体 API 发起复核与显式接受操作。

**技术栈**　Java 21、Spring Boot、JDBC、Neo4j、OpenAPI、React/TypeScript、JUnit、Vitest、Playwright。

**已通过的设计**　[指标平台按需核实本体字段的 StarRocks 类型](../specs/2026-11-24-ontology-warehouse-column-types-design.md)

**实施记录**　列目录查询通过独立的 MyBatis Mapper 使用 Connector/J，沿用仓库的持久化边界检查。报告同时校验活动部署指针版本，可识别同一构建的再次激活。属性条件更新比较完整的前次证据，避免超时覆盖较新的观察和冲突记录。验收在隔离实例中重放旧图和执行 DDL；实际实例通过部署配置启用。

**验收结果**　后端 221 个测试、前端 195 个测试及两个 Chromium 用例通过。旧图脱敏快照重放保留 13 个对象、64 个属性、21 条关系及其 ID。原样 Maven 验证遇到工作区既有的 Spotless 格式差异；最终运行全部后端测试和打包，仅限制 Spotless 检查的文件范围。运行中的实例仍按已有部署配置工作。

## 全局约束

- 实现归属 `metric-platform/`，在当前工作区完成。复用构建与资源的公开 API。dbt-service、Data Agent、生产 dbt 模型均无实施项。
- 首期覆盖 StarRocks 内部 `default_catalog`。开关默认关闭。每次操作对同一物理表至多查询一次，只读列目录。适用对象是已部署模型声明的列；本期不推导指标、表达式维度、主键唯一性或关系基数。
- 保留 `declaredType` 语义、对象与关系的稳定 ID，以及 `COMPATIBLE`、`UNVERIFIED`、`NEEDS_REPAIR` 三种状态。当前报告、上次成功观察、已接受基准分别有自己的更新条件。
- 单独注入平台只读身份。连接地址、凭据引用及底层异常明细不得进入浏览器、Neo4j 报告或业务日志。生产只读核对与隔离环境破坏性测试分别执行。
- 每项先运行指定测试确认红灯，再实现并用同一命令确认绿灯。遵守产品目录的 AGENTS.md，整份计划执行并验证后提交一次。

## 评审重点

| 容易出错的输入 | 可观察的结果 | 对应用例 |
| --- | --- | --- |
| 两租户使用同名 schema 和同名资产，或模型别名落到未授权 schema | 授权失败发生在打开 JDBC 连接之前 | 任务一 `deniedTargetNeverConnects` |
| 表中没有业务行、账号仅见部分列、元数据查询返回零行 | 空业务表可核实；后两种情况不能据此认定缺列 | 任务二 `requiresCompleteInventoryToProveAbsence`、任务六真实库验收 |
| 新部署与旧部署、或同一图版本的两次复核交错完成 | 旧结果不能建立基准、回写成功历史或覆盖较新报告 | 任务三 `lateObservationCannotOverwriteNewerEvidence`、任务四切换部署用例 |
| 同一表既是自关联的两端，又被多条边复用 | 每次操作只发一条列目录查询，所有端点共享同一观察 | 任务四 `readsEachPhysicalTableOncePerOperation` |
| 管理者查看漂移后又发生 DDL，或同一属性已被改绑 | 旧页面的接受请求失败，原基准和引用均保留 | 任务四 `staleAcceptanceIsAtomic` |

## 文件位置与验证约定

相对路径从 `metric-platform/` 起算。下表中的 Java 包名对应各模块 `src/main/java/com/metricplatform/`，同包测试在 `src/test/java/com/metricplatform/`。

| 模块 | 本次文件与职责 |
| --- | --- |
| `backend/metric-core` | 新建 `ontology/WarehouseColumns.java` 定义只读目录端口与返回类型；扩展 `ontology/OntologyContracts.java`、`ontology/OntologyStore.java` 的证据和条件写入契约。 |
| `backend/metric-engine` | 新建 `engine/warehouse/StarRocksColumnReader.java` 实现 JDBC；在本模块 `pom.xml` 引入驱动。 |
| `backend/metric-persistence` | 修改 `persistence/ontology/Neo4jOntologyStore.java` 的报告、属性证据和对象更新事务。 |
| `backend/metric-app` | 新建 `catalog/WarehouseMetadataPolicy.java` 承载实例配置和授权判定；修改 `src/main/resources/application.yml`、`catalog/OntologyBindingService.java`、`catalog/BindingEvidence.java`、`ontology/OntologyService.java` 和 `ontology/api/OntologyController.java`。 |
| API、控制台和部署说明 | `api/openapi.yaml`、`frontend/src/api/ontologyApi.ts`、`frontend/src/features/ontology/{OntologyPage,ObjectEditor,RelationEditor,BindingHealth}.tsx`、产品 `README.md`。 |

后端局部验证在产品根目录打开 PowerShell，先定义以下函数。各任务的命令传入实际模块和测试类名；通过时应在 Surefire 摘要看到指定测试已执行且零失败。

```powershell
function Test-Ontology([string]$module, [string]$classes) {
    mvn -f backend/pom.xml -B -pl $module -am "-Dtest=$classes" '-Dsurefire.failIfNoSpecifiedTests=false' test
}
```

### 任务一　从活动部署解析并授权物理表

- [x] 在 `metric-app` 的 `catalog/OntologyBindingServiceTest.java` 增加 `resolvesModelAliasFromPinnedBuild` 和 `deniedTargetNeverConnects`。构造 `database=null, schema=env_prod, alias=orders` 的模型，断言目标为快照实例的 `default_catalog.env_prod.orders`。覆盖异租户资产、未登记模型、未授权自定义 schema、非管理者、部署缺失或歧义、构建过期。所有拒绝分支断言 `verifyNoInteractions(reader)`。运行 `Test-Ontology metric-app OntologyBindingServiceTest` 确认新用例失败。
- [x] 定义端口 `WarehouseColumns.read(TableTarget target)` 返回 `TableInventory`。`TableTarget` 包含可信实例 ID、catalog、schema、最终表名；`TableInventory` 含列名到完整类型的映射、清单完整性、观察时间、失败原因。为 `WarehouseMetadataPolicy` 定义按租户和快照实例解析的授权结果。部署配置区分显式停用、启用且允许、启用且拒绝及启用但缺凭据。默认只允许快照 schema，额外 schema 逐项配置；拒绝优先于检查凭据。配置策略代次须覆盖开关、路由、授权范围、完整可见性资格及凭据轮换，不纳入秘密值。
- [x] 在 `OntologyBindingService.model` 接入现有环境、活动部署和登记资产检查；用 `getBuild(buildId).execution()` 固定实例和 catalog，核对资源的 `unique_id`、模型类型、最终 schema、alias。catalog 缺省时取快照；冲突或无法定位生成专门原因。实例 ID 决定平台侧可信连接，不采信快照回环地址或客户端连接参数。对已启用且目标越权的新建或改绑返回 403；现有绑定生成 `TARGET_NOT_ALLOWED`。对显式停用、其他适配器、已确认的外部 catalog 使用声明路径。
- [x] 重跑任务一命令。补断言允许的自定义 schema 能定位到别名表，关闭直查及旧配置均不访问 JDBC。

### 任务二　读取列目录并界定缺列证据

- [x] 新建 `metric-engine` 同包 `StarRocksColumnReaderTest.java`。用 JDBC 测试替身记录 prepare、参数、执行次数和资源关闭。断言 `decimal(18,2)` 原样返回；表名有通配字符时仍作为一个参数。用 `requiresCompleteInventoryToProveAbsence` 覆盖非空但不完整、零行、权限不足、连接和读取超时。运行 `Test-Ontology metric-engine StarRocksColumnReaderTest` 确认红灯。
- [x] 实现 `StarRocksColumnReader.read(TableTarget)`。采用项目 Spring Boot 4.1.0 BOM 管理的 `com.mysql:mysql-connector-j`（本地 BOM 为 9.7.0），使用标准 JDBC。单次操作逐表串行查询；连接超时 3 秒、socket 与语句超时各 5 秒。通过部署提供的独立账号和可信地址连接，使用 try-with-resources。唯一业务 SQL 如下。

  ```sql
  SELECT COLUMN_NAME, COLUMN_TYPE
  FROM information_schema.columns
  WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
  ```

- [x] 将“具有整表元数据可见性”设为按授权范围配置、默认关闭的资格，只在隔离库实测该账号能完整列举目标表后开启。成功返回的可见列可作为正向证据；判定缺列必须同时满足整表可见资格、查询成功、清单非空且不含目标列。零行、权限不足和超时分别产生可辨别的原因。若部署无法证实完整性，缺列继续返回未核实。
- [x] 重跑任务二命令，检查每个目标仅执行一次精确过滤查询，异常路径释放连接且返回值无原始 SQL 异常或连接信息。

### 任务三　在 Neo4j 保存基准、成功历史和当前报告

- [x] 在 `metric-persistence` 的 `persistence/ontology/Neo4jOntologyStoreTest.java` 增加旧报告升级、策略代次变更、改绑及 `lateObservationCannotOverwriteNewerEvidence`。构造同图版本的 A、B 两次观察，让 B 先完成并首次建立基准，再完成 A；断言报告、基准及最后成功值仍来自 B。再让一次较新的查询失败，断言失败原因更新，成功值及原时间保留。运行 `Test-Ontology metric-persistence Neo4jOntologyStoreTest` 确认红灯。
- [x] 在 `OntologyContracts` 定义 `ColumnObservation`（完整类型、时间、构建、物理表）、`PropertyEvidence`（属性与绑定身份、已接受类型、最后成功观察、当前原因）。当前状态沿用 `Finding`。扩展 `OntologyStore` 的读取与写入，给报告加固定格式版本 2、策略代次以及单调观察序号。旧属性缺少字段时按无基准读取，缺少格式版本的旧报告一律不复用。
- [x] 在 `Neo4jOntologyStore` 增加 `beginObservation(Scope)`，用短事务在该租户环境下分配单调序号。I/O 结束后在图锁内校验图版本、绑定身份及序号，再原子写本次报告和涉及的属性证据；跨 build 的属性证据也按序号保护。重叠的局部操作按其影响的属性作条件更新；若拓扑报告含已被较新操作更新的属性，放弃旧报告并重读。序号和报告更新均不增加图编辑版本。
- [x] 首次可用且与声明兼容的观察初始化基准，已有基准只由显式接受更新；成功观察到的冲突类型仍可进“最后成功观察”。数据集或列改绑时清除旧基准和历史，同列重命名保留。按旧 schema 构造图并重跑任务三命令，校验对象、属性和边 ID、已有引用以及图版本均符合预期。

### 任务四　接入创建、复核和显式接受流程

- [x] 在 `OntologyBindingServiceTest.java`、`integration/ontology/EnvironmentOntologyIntegrationTest.java` 和持久层的 `persistence/ontology/OntologyMutationsTest.java` 加入下表用例。运行 `Test-Ontology metric-app 'OntologyBindingServiceTest,EnvironmentOntologyIntegrationTest,OntologyMutationsTest'` 确认红灯。

  | 用例 | 必须断言 |
  | --- | --- |
  | `readsEachPhysicalTableOncePerOperation` | 列选择、对象写入、关系写入、整图复核分别计数；两个别名资产指向同一表、自关联、多条边时，各操作内每个物理表至多一次。Manifest 未声明的物理列不进入候选列表。 |
  | `combinesActualDeclaredAndAcceptedTypes` | 无声明的 BIGINT 实测可核实，DECIMAL 精度变化保留两种完整类型；声明冲突、基准漂移、确认缺列均需修复。一端缺列且另一端超时，边仍需修复。 |
  | `authorizedOutagePreservesKnownEvidence` | 临时故障时已授权且已声明、无已知冲突的新绑定可保存为未核实；已知冲突不可借重试绕过。未授权新绑定返回 403，已有绑定保留。 |
  | `checksDeploymentAndRevisionAfterObservation` | 查库中部署切换、等待图锁期间 revision 变化，过期观察不能发布；包括缓存命中路径和原有 `finalBuildCheckFollowsGraphWrite` 回归。 |
  | `staleAcceptanceIsAtomic` | 旧基准不符、再次 DDL、声明不兼容、改绑、混入其他对象属性或批次一项失败时，所有基准和图版本均保持原值；成功接受只增一次 revision，原边 ID 保留，仍冲突的边继续报修。 |

- [x] 在 `OntologyBindingService` 为单次操作共享物理表观察映射。列选择只返回已部署声明列；报告及保存通过同一逻辑比较当前模型声明、已接受基准与实测值，关系再比较两端类型。沿用 `BindingEvidence.compareTypes` 和状态优先级，保留既有 `COLUMN_UNDECLARED` 行为。浏览器传入的类型不能作为证据；无实测且缺凭据、不可见或超时的当前结果为未核实。
- [x] 扩展 `check(Scope, Topology, boolean refresh)`。普通读取仅在 scope、build、revision、格式、策略均匹配且报告可复用时返回；手动刷新跳过缓存。切换构建、变更策略或发生可重试故障后在下次读取重算，不定时轮询。关闭直查后按声明生成新报告，历史实测带时间显示。操作开始和结束校验当前部署，必要时重读图版本；写入在事务持锁后、提交前执行最后一次前置条件核对，过期则回滚。返回值不能带上已淘汰的观察。外部部署指针与图库没有共享事务，验收以核对时刻为界。
- [x] 扩展 `OntologyService.ObjectWrite`，可选数组 `acceptPhysicalTypes` 的元素为 `{propertyId, expectedBaselineType, observedType}`。仅对象 PUT 支持；服务端要求现有同一对象、属性和绑定、有效 `expectedRevision` 和 `sourceBuildId`。强制取新观察，比对页面旧基准及完整新类型，核验当前声明兼容，再在同一次对象更新事务中检查并更新所有指定基准。旧观察、部署、基准或版本冲突返回 409 并要求刷新；保留现有非法绑定校验。普通保存或报告生成均不得接受冲突。保留无数组的旧调用和 `OntologyReferences.checkUpdate` 对已引用属性的保护。
- [x] 重跑任务四命令，包括已有“成功可缓存、临时失败可重试”与关系两端校验用例。另断言一次显式复核不增加 revision，成功接受才增加。

### 任务五　公布兼容契约并展示证据

- [x] 扩展 `metric-app` 的 `ontology/api/OntologyControllerTest.java`、`api/OpenApiContractTest.java` 及 `frontend/src/features/ontology/OntologyPage.test.tsx`。覆盖旧请求省略新字段、显式刷新、只读用户鉴权、临时失败仍显示上次成功时间、双端点类型、接受请求的准确负载及 409 刷新。先运行 `Test-Ontology metric-app 'OntologyControllerTest,OpenApiContractTest'`，在 `frontend/` 运行 `npm test -- src/features/ontology/OntologyPage.test.tsx`，确认新增断言失败。
- [x] 同步控制器及 `api/openapi.yaml`。现有拓扑 GET 增加可选 `refresh=false`，列选择沿用原 URL；列与属性 finding 可返回 `typeSource`、`lastObservation`，属性 finding 另含 `acceptedPhysicalType`；关系 finding 提供 `sourceEvidence`、`targetEvidence`。列项也返回本次 `reason`，使选择器能解释失败。`lastObservation` 对应任务三的 `ColumnObservation`；`typeSource` 区分当前证据来源（声明、仓库、无可用来源），当前失败时同历史值分别展示。将任务四的可选接受数组、403 与 409 纳入契约。所有新增响应字段可空或可缺省。
- [x] 扩展 `ontologyApi.get(environmentId, refresh?)` 及 `saveObject` 的可选接受数组。在数据集选列、属性详情呈现声明、实测、来源和观察时间，在关系详情展示双方列和类型；图区复用已有状态标签。管理者点击“重新核实”才强制刷新，在漂移详情显式点击“接受当前物理类型”才提交接受数组。409 后重新展示当前证据并要求重新选择。
- [x] 重跑本任务后端和前端命令；在 `frontend/` 运行 `npm run build`，要求前端类型检查及生产打包通过。

### 任务六　真实 StarRocks、旧图升级与发布验收

- [x] 在 `metric-app` 新建 `integration/ontology/StarRocksOntologyMetadataIntegrationTest.java`。通过显式启用的隔离 StarRocks fixture，预置带别名的订单表、无声明类型的 BIGINT、DECIMAL(18,2)、空业务表、部分元数据可见或权限不足的账号。fixture 管理身份只用于准备/清理，平台使用待验证的只读身份。记录数据库审计或 JDBC 执行计数，核对仅执行指定列目录查询。隔离库内删除一列、修改类型并恢复权限，验证状态、接受和恢复流程；真实驱动兼容性以此用例为准。
- [x] 扩展 `frontend/e2e-publication/environment-ontology.spec.ts` 覆盖“选择列 → 创建 → 查看两端 → 主动复核 → 显式接受”。运行 `Test-Ontology metric-app StarRocksOntologyMetadataIntegrationTest` 和（在 `frontend/`）`npm run test:e2e -- --config=playwright.publication.config.ts environment-ontology.spec.ts`。真实库用例必须有执行计数；环境未准备时记录缺项，协议替身或跳过不能计作真实库通过。
- [x] 用隔离 Neo4j 恢复已脱敏的旧格式图和旧报告，先导出各类稳定 ID 及引用集合，再升级并复核。设计基线为构建 `bae818c9-0fec-42f6-9d19-94535109a22a`、图版本 39、13 个对象、64 个属性、21 条关系。数据结构与基线一致时，原 63 个缺声明属性、21 条边均应能凭物理类型完成核实；分别比较数量、ID 集合、引用和状态。实际部署已变化时记录新基线和逐项证据，避免把历史数量当成生产断言。
- [x] 在产品 `README.md` 增补启用、验证和停用步骤。先验证平台容器到可信实例的连通性、只读身份和整表可见资格，再为该实例开启；收紧授权和关闭直查都须在下次普通读取生效。发布核对期间只做获准的元数据观察，所有 DDL 演练使用隔离库。
- [x] 在产品根运行 `mvn -B verify`，在 `frontend/` 运行 `npm test`、`npm run build`；确认普通业务查询、已有管理权限与本体工作流回归通过。分别检查工作区根 `git status --ignore-submodules=none` 和产品仓库 `git status --short`、`git diff --check`、`git diff --cached`。实施和验收完成后仅提交本计划涉及文件为一个产品仓库 commit，建议消息为 `feat(ontology): 按需核实 StarRocks 物理列类型`；已有暂存改动须排除。
