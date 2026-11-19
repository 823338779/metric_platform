# Metric Platform

指标平台负责登录认证、构建与部署历史展示，以及固定 `buildId` 的目录、可用维度和查询。dbt-service 是构建事实、有效部署、目录和查询执行的唯一权威。平台与 Agent 不直接交互；平台提交环境执行快照，构建和部署由 dbt-service 执行。

## 模块边界

| 目录 | 职责 |
| --- | --- |
| `backend/metric-core` | 构建、查询、身份及历史投影端口和类型契约 |
| `backend/metric-engine` | dbt-service 公开接口的 HTTP 适配 |
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

开发服务器将 `/api` 代理到 `http://localhost:8090`。平台部署需要统一支持执行快照构建及固定构建资源接口的 dbt-service，不依赖 Agent 的运行状态。平台没有协议模式开关，也不会在新接口失败时访问旧业务路径。

## 租户开通与资产隔离

运维通过 HTTPS 调用 `POST /api/ops/tenants`，以 `X-Operations-Token` 传入部署注入的 `METRIC_PLATFORM_OPERATIONS_TOKEN`。请求体为 `username`、`password`，成功返回 201 及 `tenant_id`。运维凭据仅在服务端配置。开通失败时检查响应状态，重复登录名返回 409。普通用户只访问平台，部署网络须限制 dbt-service 仅对可信调用方开放。

管理者以账号和密码登录，在「账号管理」创建只读账号。登录名全局唯一。只读账号登录后可查看本人身份，初始没有资产访问权。管理者可操作本租户全部环境与固定构建。创建和更新环境按登录账号的租户管理员身份鉴权。服务校验连接参数是否匹配已配置的执行实例，并检查物理目标是否已被其他环境占用。

### V18 存量切换到 V19

1. 在维护窗口阻断旧平台流量及其他调用方的新提交，等待已受理构建归属确定。核对未确认提交，使用原幂等键和快照恢复。备份平台数据库，保存同版本应用与恢复点。
2. 汇总平台环境及外部历史环境，明确清单冻结时间。准备 Python 和 PostgreSQL 的 `psql` 客户端，通过 libpq 环境或服务文件选择目标平台库。由运维指定一个已启用、可登录的存量账号。执行 `python scripts/tenant_inventory.py --manager <登录名> --service <内部服务地址>`；对每个额外历史环境追加 `--external-environment <UUID>`。也可使用 `--external-builds <JSON路径>` 导入运维已核验的外部历史，文件为含 `build_id` 和 `environment_id` 的数组。按环境核对数量和历史记录，分页失败后重新生成清单。
3. 启动新版平台执行 Flyway。V19 将存量账号、环境和清单内构建统一归入 `tn_00001`，仅指定账号成为管理者。新安装空库直接由运维接口开通首个租户。
4. 保持停流，核验各类记录总数与原 ID、管理者身份、其他已登录账号的空资产列表、旧构建详情及一次实际查询。遇到不一致时停止新版实例，在恢复写入前成对恢复备份数据库与旧应用并复核。已恢复写入后应先对账再制定恢复方案，直接还原旧快照会丢失新增数据。

### V19 升级到 V20

V20 将 `tenant_id` 扩展到平台业务表，包括令牌、审计、执行环境快照、提交记录、旧语义目录和历史投影。运维应在维护窗口先备份平台库，暂停旧版平台写入，再执行升级。迁移通过账号、环境、构建和发布版本核对归属。若迁移报告某张表仍有未归属记录，事务会回滚。运维应按主键核实这些记录的归属，在报错表中预置可空的 `tenant_id varchar(8)` 并仅回填已核实的记录，再重试迁移。相互冲突的账号、环境或构建引用需要先核对原始证据。

登录仍使用全平台唯一的账号和密码。服务端根据认证身份绑定租户，持久化层据此限制读写。新业务表必须设置非空的 `tenant_id`。记录写入后归属固定，业务表之间的关联通过包含 `tenant_id` 的复合外键约束到同一租户。物理执行目标仍在全平台范围内排他占用。

## 已部署资产授权

平台启动后默认等待 60 秒开始后台核对已知部署目标，每轮结束后等待相同间隔再同步。首次升级需等首轮登记完成才会显示已有资产。授权页面按执行环境分组，从业务库读取最近一次成功登记的目录，实时关联所选账号的使用权。新部署在下一次成功同步后出现。登记完成后，租户管理者可在「账号管理」的只读账号目录中选择本租户的只读账号，分别授予或撤销指标、维度、数据集的使用权。进入环境的构建后，「资源管理」的目录卡片展示本租户当前已部署的指标、维度和数据集，按当前账号标注“无权限”。用户可从已授权的指标进入查询，或查看已授权数据集的明细。

授权按租户、执行环境、资产类型和原生名称隔离。维度还区分所属语义模型的原生名称。切换仓库、分支或 dbt 包时，同一环境中身份相同的资产沿用已有授权。资产移除后，授权仍保存在平台业务库。相同标识重新部署会恢复已有授权，移除期间主动撤销的授权继续失效。

仅持有指标权限即可查汇总。按业务维度分组、筛选、查询维度值或者排序，需要对应的维度权限。系统时间维度 `metric_time` 随指标可用，按天分组、排序或设置时间范围均无需单独授权。无法唯一识别的选项不会向普通用户开放。数据集明细单独授权。缺少权限时，平台返回 HTTP 403，整条请求不执行。普通用户访问旧的生效版本时会收到 HTTP 410，可返回执行环境选择当前版本。

## 固定构建与历史

执行环境页面按构建列出分支、提交、状态和时间，保留历史构建记录及管理者可查看的构建日志。dbt-service 按执行环境保留当前生效版本的产物。新版本替换旧版本后，旧版本的目录、源码、血缘和查询不可用。

构建列表根据 dbt-service 返回的 `catalogAvailable` 展示入口。可用构建的目录和查询固定到所选 buildId，并遵循当前账号的资产权限。访问已清理构建的收藏链接时，页面提示产物不可用，用户可从执行环境选择当前版本。

目录接口 `GET /api/dbt/builds/{buildId}/catalog` 返回 dbt ls 原生资源数组，每项保留 `unique_id`、`resource_type`、`name`、`config` 等字段，不接收筛选或分页参数。界面在本地筛选、展示；原生目录没有独立维度资源 ID。资源详情 `/resources/{resourceId}` 使用 dbt `unique_id`，返回 `{buildId, resourceId, resource, source}`，其中 `resource` 为完整节点，`source` 为 `{path, content}` 或 null。血缘改为 `GET /api/dbt/builds/{buildId}/lineage?selector=…`，返回原生节点数组；selector 中的加号必须编码为 `%2B`。旧 `/resources/{resourceId}/{view}` 接口已移除。

可用维度与四种查询模式均同步完成。平台保留 `/builds/{buildId}/available-dimensions` 与 `/builds/{buildId}/queries` 门面；适配层分别调用 `/query/available-dimensions?build_id=…` 和 `/query?build_id=…`。可用维度请求为 `{"metrics":["revenue"]}`，返回 `{buildId, metrics, dimensions}`；指标使用名称，不能传旧资源 ID。维度保留 optionId、dimensionType、granularities 和 operators，不再返回 valueType。

| mode | 请求字段（除 mode） | 结果字段（除 buildId、commitSha、mode） |
| --- | --- | --- |
| QUERY | 必填 metrics；可选 groupBy、filters、startTime、endTime、orderBy、limit | businessTimezone、normalizedTimeRange、columns、rows、resultTruncated |
| EXPLAIN | 同 QUERY | businessTimezone、normalizedTimeRange、sql |
| PREVIEW | 必填 selector；可选 limit | columns、rows、resultTruncated |
| DIMENSION_VALUES | 必填 metrics、dimensionOptionId；可选 filters、startTime、endTime、limit | businessTimezone、normalizedTimeRange、columns、rows、resultTruncated |

模式无关字段即使为 null 或空数组也拒绝。PREVIEW 的 selector 匹配一个非 seed 节点，例如 `{"mode":"PREVIEW","selector":"orders","limit":100}`；指标查询例如 `{"mode":"QUERY","metrics":["revenue"],"limit":100}`。limit 默认 1000，范围 1–10000。EXPLAIN 不执行取数，其 SQL 可能包含 `LIMIT 101`，额外一行用于实际取数时判断截断。表格结果最多返回 limit 行，Decimal 保留字符串精度，日期时间保留 ISO 字符串。

查询不携带幂等键，一次返回完整有界结果，不返回 queryId、任务状态或分页字段，也不提供历史结果读取。失败通过非 2xx 业务错误返回，平台保留服务的业务错误码、phase、buildId、retryable。时间按构建固定的业务时区解释，normalizedTimeRange 只表示归一化输入，不再返回 boundaryPolicy。平台不根据构建状态预判查询可用性，实际产物恢复、引擎初始化和执行错误由服务返回。

`GET /api/dbt/builds` 直接读取服务构建历史，`environmentId` 必填，`repository`、`branchName` 和 `buildStatus` 可选。部署列表使用 `/api/dbt/deployments`，固定 repository/environmentId/branchName 与服务游标。旧投影、变化游标和 Agent 分支缓存表保留供审计，不作为当前列表数据源；旧项目和 Agent 分支代理接口已移除。

构建成功、部署生效和可查询是独立状态。历史物理对象已清理时仍可查看服务封存目录；查询返回对应不可用原因。平台通过环境构建接口提交执行快照；物理构建和部署由 dbt-service 执行。

## 历史关联迁移

`V15` 新增构建/部署投影、游标、查询归因以及稳定映射表，保留原 `semantic_query.release_id` 和 `runtime_query_id`。没有可信映射的旧查询 `build_id` 保持为空，不能从旧字符串猜测新 UUID，也不能放入默认仓库。

迁移操作先获取并核对 dbt-service 的稳定身份映射报告和真实仓库归属，再在平台数据库调用 `import_legacy_build_mapping(tenant_id, old_release, new_build, source_repository, evidence)`。参数必须来自已核验的只读报告，`evidence` 保存报告位置及摘要。函数在同一事务保存映射并关联旧查询；相同输入重复导入保持稳定，任何身份、仓库或证据冲突均报错。未映射记录可通过 `semantic_release LEFT JOIN legacy_build_mapping` 列出，原封存证据不变。

`V16` 新增 `query_execution_audit` 保存同步查询成功后的本地执行 ID、固定构建与登录身份。它不是 dbt-service 查询身份，不对外提供结果检索；旧查询审计及其原始幂等键保持不变。

`GET /api/dbt/contract` 公开只读协议元数据及打包的同一 OpenAPI，供联合部署前检查；业务接口继续使用现有登录安全链。

## 验证

按改动范围选择验证命令，不必每轮依次运行后端、前端、构建和浏览器验收。后端使用系统 Maven；涉及事务的测试使用真实 Testcontainers PostgreSQL，需要 Docker。

在本仓库运行受影响模块的定向测试，例如引擎 HTTP 适配层：

```powershell
mvn -f .\backend\pom.xml -B -pl metric-engine -am '-Dtest=HttpBuildGatewayTest,HttpEnvironmentBuildGatewayTest,QueryTimeTransportTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

接口路由变更可改为 `-pl metric-app` 并指定对应的 Controller/Contract 测试。`-am` 构建依赖模块，`-Dtest` 限定实际执行的用例；不要因依赖模块没有匹配测试而改跑全部测试。跨模块公共行为修改或需要完整验收时，执行一次后端完整验证：

```powershell
mvn -B verify
```

前端在 `frontend/` 执行。首次安装或锁文件变化时运行 `npm ci`；日常开发指定文件或目录，全量单测、生产构建和浏览器验收按需要选用：

```powershell
# 查询界面局部修改
npm test -- src/features/dbt/BuildQuery.test.tsx
# 前端完整单测
npm test
# 生产构建检查
npm run build
# 浏览器验收：按改动涉及的工作流选择配置，两个配置覆盖不同场景
npm run test:e2e
npm run test:e2e -- --config=playwright.publication.config.ts
```

真实 PostgreSQL 浏览器验收需先具备匹配版本的三个服务、真实 Git 项目及已部署构建。测试使用 `postgres_platform` 隔离 fixture 的 `revenue` 指标与 2024 年 1、2 月数据。设置 `DBT_POSTGRES_E2E=1`、`DBT_POSTGRES_E2E_USER`、`DBT_POSTGRES_E2E_PASSWORD`、`DBT_POSTGRES_E2E_BUILD_ID`、`DBT_POSTGRES_E2E_PLATFORM_URL` 后，在 `frontend/` 运行 `npm run test:e2e -- --config=playwright.postgres.config.ts`。可用 `DBT_POSTGRES_E2E_FRONTEND_PORT` 指定独占前端端口（默认 4175），用 `DBT_POSTGRES_E2E_OUTPUT_DIR` 指定操作系统临时目录中的结果位置。专用配置禁止复用其他开发服务器，并关闭含真实认证请求的 trace 和视频。未准备真实环境时必须明确记录跳过，不将协议桩通过视为真实引擎验收。


## 执行环境与构建历史

入口 `/dbt/environments`。环境仅保存连接实例、凭据引用、catalog/schema、并行度和业务时区；源码仓库由服务器全局绑定，每次构建从该仓库的实际分支中选择。修改配置须提交 `expectedConfigVersion`，旧配置、旧目标归属和已受理构建快照保留。多个环境不能占用同一实例/适配器/catalog/schema。

`backend/metric-app/src/main/resources/application.yml` 的 `metric-platform.git.repository` 配置全局唯一构建仓库，默认 `https://github.com/823338779/metric_store.git`，直接访问 GitHub，不依赖本地 Forgejo。当前远端默认分支为 `master`，也可选择 `dbt-dev`；分支列表以远端实际返回为准。可通过 `METRIC_GIT_REPOSITORY` 覆盖仓库地址，修改后重启后端。浏览器只读展示仓库地址。`GET /api/dbt/repository/branches` 需要登录，使用服务器安装的 Git 读取远端分支，最长等待 15 秒；私有仓库的访问凭据由服务器 Git/SSH 配置管理，不放入仓库 URL。分支读取失败或没有分支时不能发起构建，支持刷新重试。构建提交的 `repository` 必须与当前服务器绑定地址一致，否则返回 422。历史记录继续保留原始仓库归属；切换前的 Forgejo 构建不支持直接重新构建，请在顶部为 GitHub 仓库选择分支并提交新构建。

- `/api/dbt/environments`：本租户全部环境列表和新增；`/{environmentId}`：读取和按期望版本更新。
- `/{environmentId}/builds`：POST 提交；GET 按环境读取全部构建历史，可选仓库、分支、状态和游标。失败、排队、未部署构建也保留。
- `/api/dbt/builds/{buildId}`：固定元信息，完整 execution 为必填字段；配置版本读取 `execution.configVersion`，不再返回顶层 configVersion、cancelRequested、queryAvailable 或 queryUnavailableReason。
- `/api/dbt/builds/{buildId}/execution-logs?cursor=…&limit=100`：结构化执行日志，limit 范围 1–200；旧 `/logs` 已移除。
- 固定 buildId 的目录和查询使用同一无版本号资源接口，`/dbt/projects` 按环境 UUID 查询部署。

部署时平台和服务必须配置相同的 `EXECUTION_INSTANCES` JSON，例如 `{"warehouse":{"type":"starrocks","hosts":["localhost","127.0.0.1"],"port":9030}}`。这份清单只规范物理实例和地址别名，不含环境的 catalog/schema。凭据引用及 secret 仅由服务部署解析；浏览器不提交密码或服务令牌。

平台通过 Flyway V17/V18 保存环境版本、物理目标归属和幂等提交。提交前固定登录身份、环境版本和完整快照；HTTP 超时后用原 `idempotencyKey` 重试，仍发送原快照，即使环境已修改。只有明确开始新的提交才生成新键。初始化新数据库并启动 dbt-service 后，再启动平台。

同一环境后续构建会更新同名表。旧 build 固定的是源码和执行目标，不承诺历史数据快照。环境目标修改后旧 build 的查询仍使用当时的 catalog/schema。


### 详细执行日志

日志响应为 `{items, nextCursor, hasMore}`。每条事件包含 logId、jobId、attemptId、phase、command、eventTime、level、eventName、eventCode、invocationId、nodeId、threadName、message、createdAt。logId 是十进制字符串，按服务返回的 ID 顺序追加并去重，不转为 JavaScript Number，也不按事件时间重排。消息按文本渲染，保留换行，不作为 HTML 执行。

首次读取不传 cursor，成功处理后保存 nextCursor；hasMore=true 时串行继续分页，false 时保留游标轮询。空页也返回有效游标，不能清空或视为构建结束。切换构建时取消旧请求并清空旧游标及列表，重试沿用最后成功的游标。阶段和状态读取构建详情，观察到正常终态后再读一次并取完已有页；OUTCOME_UNKNOWN 仍需核查，迟到日志保留刷新入口。hasMore 只表示当前是否有下一页，不保证日志完整。

平台对原生 Manifest 和资源详情采用 64 MiB HTTP 响应上限，与 dbt-service 默认单产物大小一致；查询及其他响应仍为 8 MiB。若服务部署提高单产物限制，需同步评估平台读取边界。
