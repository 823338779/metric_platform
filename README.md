# Metric Platform

指标平台负责登录认证、构建与部署历史展示，以及固定 `buildId` 的目录、可用维度和查询。dbt-service 是构建事实、有效部署、目录和查询执行的唯一权威。平台与 Agent 不直接交互；平台提交环境执行快照，构建和部署由 dbt-service 执行。

## 模块边界

| 目录 | 职责 |
| --- | --- |
| `backend/metric-core` | 身份、环境、构建、授权及查询的端口和类型契约 |
| `backend/metric-engine` | dbt-service 公开接口的 HTTP 适配 |
| `backend/metric-persistence` | 账号、环境版本、构建归属、资产授权和审计的持久化及 Flyway 迁移 |
| `backend/metric-app` | 登录与授权、环境管理、构建提交、目录和历史读取、查询代理及归因 |
| `frontend` | 部署历史筛选、固定版本目录与查询界面 |
| `api/openapi.yaml` | 平台公开接口的唯一契约，构建时同步打包 |
| `mcp/` | 独立安装的 stdio MCP 工具，使用登录账号调用平台 API |

产品独立构建，不导入 Agent 或 dbt-service 的实现类。旧审计表、历史资源和已应用的迁移继续保留；这些记录不再驱动发布。

## MCP 客户端接入

在平台仓库的 `mcp/` 目录执行 `uv sync --frozen`。将客户端的 command 设为安装后的 `metric-platform-mcp` 绝对路径（Windows 位于 `.venv/Scripts`，Linux/macOS 位于 `.venv/bin`），args 留空，传输选择 stdio。也可使用同一虚拟环境的 Python，args 填 `["-m", "metric_platform_mcp"]`。

通过客户端的进程环境注入 `METRIC_PLATFORM_URL`（平台地址，例如 `http://127.0.0.1:8090`）和 `METRIC_PLATFORM_TOKEN`（平台登录接口签发的账号访问令牌）。远程连接使用 HTTPS。令牌到期后更新客户端环境并重启 MCP 进程。由旧版本迁移时，替换原 `dbt-metricflow-mcp` 命令及两项环境变量。

`tools/list` 可发现部署、目录搜索、资源详情、可用维度、指标查询、提交构建和构建详情七个工具。查询权限与该账号在平台中的权限一致。提交构建需要管理者权限，使用 `environment_id` 和 `expected_config_version` 选择平台环境的配置版本。完整的调用顺序和幂等重试示例见[查询与构建示例](mcp/查询与构建示例.md)。

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
2. 汇总平台环境及外部历史环境，明确清单冻结时间。准备 Python、Java 21 和已打包的平台 JAR。通过 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD` 指定待升级的平台库。脚本默认从 `backend/metric-app/target` 读取 JAR，也可以用 `--app-jar <路径>` 指定发布包。由运维指定一个已启用、可登录的存量账号。执行 `python scripts/tenant_inventory.py --manager <登录名> --service <内部服务地址>`；对每个额外历史环境追加 `--external-environment <UUID>`。也可使用 `--external-builds <JSON路径>` 导入运维已核验的外部历史，文件为含 `build_id` 和 `environment_id` 的数组。按环境核对数量和历史记录，分页失败后重新生成清单。
3. 启动新版平台执行 Flyway。V19 将存量账号、环境和清单内构建统一归入 `tn_00001`，仅指定账号成为管理者。新安装空库直接由运维接口开通首个租户。
4. 保持停流，核验各类记录总数与原 ID、管理者身份、其他已登录账号的空资产列表、旧构建详情及一次实际查询。遇到不一致时停止新版实例，在恢复写入前成对恢复备份数据库与旧应用并复核。已恢复写入后应先对账再制定恢复方案，直接还原旧快照会丢失新增数据。

### V19 升级到 V20

V20 将 `tenant_id` 扩展到平台业务表，包括令牌、审计、执行环境快照、提交记录、旧语义目录和历史投影。运维应在维护窗口先备份平台库，暂停旧版平台写入，再执行升级。迁移通过账号、环境、构建和发布版本核对归属。若迁移报告某张表仍有未归属记录，事务会回滚。运维应按主键核实这些记录的归属，在报错表中预置可空的 `tenant_id varchar(8)` 并仅回填已核实的记录，再重试迁移。相互冲突的账号、环境或构建引用需要先核对原始证据。

登录仍使用全平台唯一的账号和密码。服务端根据认证身份绑定租户，持久化层据此限制读写。新业务表必须设置非空的 `tenant_id`。记录写入后归属固定，业务表之间的关联通过包含 `tenant_id` 的复合外键约束到同一租户。物理执行目标仍在全平台范围内排他占用。

PostgreSQL 业务读写和测试夹具均通过 Mapper。单表 CRUD 优先使用 MyBatis-Plus 的 `BaseMapper` 和 Lambda 条件构造器；联表、行锁、冲突写入及历史版本造数使用专用 Mapper SQL。业务表的 `tenant_id` 须注明归属含义。常规 Mapper 由租户拦截器注入租户列与查询条件。显式处理租户的业务 SQL 从可信上下文取得租户，新增写入该列，查询、更新、删除和跨表关联均限定租户。Redis 令牌的读写校验同一归属，缺少租户字段的旧令牌须先验证账号归属。

租户开通、认证前按唯一登录名或令牌定位租户、后台任务枚举待处理租户，均在未绑定业务租户时执行。定位完成后绑定租户，再读取业务数据。Flyway 的迁移历史属于整个数据库。

## 已部署资产授权

构建受理时，平台在同一事务保存构建归属以及固定的仓库、环境和分支。授权目录只在 dbt-service 确认部署生效并完成资产登记后更新。V24 会补齐目标明确的历史提交；缺少目标或目标有歧义的历史构建仍由后台向服务核实。

平台启动后默认等待 60 秒开始后台核对已知部署目标，每轮结束后等待相同间隔再同步。首次升级需等首轮登记完成才会显示已有资产。授权页面按执行环境分组，从业务库读取最近一次成功登记的目录，实时关联所选账号的使用权。新部署在下一次成功同步后出现。登记完成后，租户管理者可在「账号管理」的只读账号目录中选择本租户的只读账号，分别授予或撤销指标、维度、数据集的使用权。进入环境的构建后，「资源管理」的目录卡片展示本租户当前已部署的指标、维度和数据集，按当前账号标注“无权限”。用户可从已授权的指标进入查询，或查看已授权数据集的明细。

授权按租户、执行环境、资产类型和原生名称隔离。维度还区分所属语义模型的原生名称。切换仓库、分支或 dbt 包时，同一环境中身份相同的资产沿用已有授权。资产移除后，授权仍保存在平台业务库。相同标识重新部署会恢复已有授权，移除期间主动撤销的授权继续失效。

仅持有指标权限即可查汇总。按业务维度分组、筛选、查询维度值或者排序，需要对应的维度权限。系统时间维度 `metric_time` 随指标可用，按天分组、排序或设置时间范围均无需单独授权。无法唯一识别的选项不会向普通用户开放。数据集明细单独授权。缺少权限时，平台返回 HTTP 403，整条请求不执行。普通用户访问旧的生效版本时会收到 HTTP 410，可返回执行环境选择当前版本。

## 固定构建与历史

执行环境页面按构建列出分支、提交、状态和时间，保留历史构建记录及管理者可查看的构建日志。dbt-service 按执行环境保留当前生效版本的产物。新版本替换旧版本后，旧版本的目录、源码、血缘和查询不可用。

构建列表根据 dbt-service 返回的 `catalogAvailable` 展示入口。可用构建的目录和查询固定到所选 buildId，并遵循当前账号的资产权限。访问已清理构建的收藏链接时，页面提示产物不可用，用户可从执行环境选择当前版本。

当前部署接口默认直接读取 dbt-service 的部署记录。目录、查询、授权和本体绑定校验均使用这一读取方式；版本详情中的「检查远端分支」显式传入 `refreshSource=true`。升级时先部署支持该参数的 dbt-service，旧服务会忽略参数并继续同步访问 Git。

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

## 环境内的本体建模

租户管理者可从工作区左侧「分析探索」下方的「本体建模」进入当前构建所属环境，也可从执行环境详情进入。选择当前部署的数据集、列和主键属性，再用具名关系连接本环境的两个对象。保存即生效。打开或刷新页面时，平台按当前构建复核字段；「未核实」表示当前声明不足以证明绑定是否兼容。切换环境会重新加载该环境独立的本体。图库保存对象类型、属性映射和关系定义，平台 PostgreSQL 保存原有身份与资产归属。

本地开发可把 `METRIC_NEO4J_PASSWORD=<随机密码>` 存入当前用户专用的 `~/.metric-platform/ontology.properties`，限制文件访问权限。平台后端启动时加载该文件。用同一份配置从仓库根启动专用图库：

```powershell
docker compose --env-file "$HOME/.metric-platform/ontology.properties" --profile ontology up -d --wait neo4j
```

独立 Compose 使用 Neo4j 2026.10.1、初始用户 `neo4j`、本机 Bolt 端口 `17687` 和持久卷。已有独立图库可通过 `METRIC_NEO4J_URI`、`METRIC_NEO4J_USERNAME`、`METRIC_NEO4J_PASSWORD` 接入。开发测试使用隔离容器；图库不可达时，本体入口展示故障，原有目录仍可访问。

**备份与恢复。** 对独立 Compose 单实例，在维护窗口暂停图写入，记录当前环境的对象、边、字段绑定和编辑序号。以下 PowerShell 示例从已停止的库离线备份，先验证恢复至新的空卷。每条命令成功后再执行下一条，恢复演练保留原卷。

```powershell
$graphContainer = docker compose --profile ontology ps -q neo4j
$graphVolume = docker inspect --format '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Name}}{{end}}{{end}}' $graphContainer
if (-not $graphVolume) { throw '未找到图库数据卷' }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backupVolume = "ontology-dump-$stamp"
$restoreVolume = "ontology-restore-$stamp"
$graphImage = 'neo4j:2026.10.1'
docker volume create $backupVolume
docker volume create $restoreVolume
docker run --rm --user root --entrypoint chown -v "${backupVolume}:/backups" $graphImage neo4j:neo4j /backups
docker compose --profile ontology stop neo4j
docker run --rm -v "${graphVolume}:/data" -v "${backupVolume}:/backups" $graphImage neo4j-admin database dump neo4j --to-path=/backups
docker compose --profile ontology start neo4j
docker run --rm -v "${restoreVolume}:/data" -v "${backupVolume}:/backups:ro" $graphImage neo4j-admin database load neo4j --from-path=/backups
```

用相同镜像从恢复卷启动独立验证实例，并在隔离的平台环境读取原本体，核对对象与边 ID、关系角色、资产与字段映射及编辑序号。备份卷应随运维策略异机保存。本命令备份业务图 `neo4j`；部署凭据与图库系统配置由部署方另行管理。

## 验证

按改动范围选择验证命令，不必每轮依次运行后端、前端、构建和浏览器验收。后端使用系统 Maven；涉及事务的测试使用真实 Testcontainers PostgreSQL，需要 Docker。

MCP 独立测试在 `mcp/` 目录运行 `uv run --frozen pytest -q`。设置 `MCP_INSPECTOR_ENTRY` 指向本机 MCP Inspector CLI 的 JavaScript 入口，可额外比对 Inspector 和 Python 客户端发现的工具以及读取的生效构建。

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
- `/api/dbt/builds/{buildId}`：固定元信息，完整 execution 为必填字段；dbt-service 返回构建受理时固定的物理执行参数。平台环境配置版本由环境接口返回，并在提交时通过 `expectedConfigVersion` 校验。
- `/api/dbt/builds/{buildId}/execution-logs?cursor=…&limit=100`：结构化执行日志，limit 范围 1–200；旧 `/logs` 已移除。
- 固定 buildId 的目录和查询使用同一无版本号资源接口，`/dbt/projects` 按环境 UUID 查询部署。

### 本体字段的 StarRocks 实测类型

平台 API 容器可通过 Spring 部署配置 `metric-platform.ontology.warehouse.instances` 开启内部 `default_catalog` 的元数据查询。默认列表为空。以下配置使用环境变量引用，实例 ID 对应活动构建快照。只在实例下列出的租户能访问其环境快照的 schema，租户列表值用于明确追加其他 schema。

```yaml
metric-platform:
  ontology:
    warehouse:
      instances:
        - id: ${WAREHOUSE_INSTANCE_ID}
          enabled: true
          url: ${WAREHOUSE_METADATA_JDBC_URL}
          username: ${WAREHOUSE_METADATA_USER}
          password: ${WAREHOUSE_METADATA_PASSWORD}
          generation: "1"
          complete: false
          tenants:
            "[tn_00001]": []
```

上线前从平台容器验证独立只读身份、JDBC 路由和 TLS；URL 仅放主机、端口及驱动选项，凭据分别注入。先用已知列验证可见性，只有该实例所有获准范围的整表列清单均可见时才设置 `complete: true`；空结果始终显示未核实。轮换凭据时递增 `generation`，重新加载平台配置。停用实例后，下次读取用模型声明重新评估，过去的实测值保留观察时间。主动发现同一部署上的 DDL 时，在本体页点击“重新核实”。

隔离 StarRocks 验收从产品根目录运行 `mvn -f backend/pom.xml -B -pl metric-app -am '-Dtest=StarRocksOntologyMetadataIntegrationTest' '-Dontology.starrocks.it=true' '-Dsurefire.failIfNoSpecifiedTests=false' test`。测试自行启动临时 StarRocks，仅在该容器内准备表、执行 DDL 和授权。

部署时平台和服务必须配置相同的 `EXECUTION_INSTANCES` JSON，例如 `{"warehouse":{"type":"starrocks","hosts":["localhost","127.0.0.1"],"port":9030}}`。这份清单只规范物理实例和地址别名，不含环境的 catalog/schema。凭据引用及 secret 仅由服务部署解析；浏览器不提交密码或服务令牌。

平台通过 Flyway V17/V18 保存环境版本、物理目标归属和幂等提交。提交前固定登录身份、环境版本和完整快照；HTTP 超时后用原 `idempotencyKey` 重试，仍发送原快照，即使环境已修改。只有明确开始新的提交才生成新键。初始化新数据库并启动 dbt-service 后，再启动平台。

同一环境后续构建会更新同名表。旧 build 固定的是源码和执行目标，不承诺历史数据快照。环境目标修改后旧 build 的查询仍使用当时的 catalog/schema。


### 详细执行日志

日志响应为 `{items, nextCursor, hasMore}`。每条事件包含 logId、jobId、attemptId、phase、command、eventTime、level、eventName、eventCode、invocationId、nodeId、threadName、message、createdAt。logId 是十进制字符串，按服务返回的 ID 顺序追加并去重，不转为 JavaScript Number，也不按事件时间重排。消息按文本渲染，保留换行，不作为 HTML 执行。

首次读取不传 cursor，成功处理后保存 nextCursor；hasMore=true 时串行继续分页，false 时保留游标轮询。空页也返回有效游标，不能清空或视为构建结束。切换构建时取消旧请求并清空旧游标及列表，重试沿用最后成功的游标。阶段和状态读取构建详情，观察到正常终态后再读一次并取完已有页；OUTCOME_UNKNOWN 仍需核查，迟到日志保留刷新入口。hasMore 只表示当前是否有下一页，不保证日志完整。

平台对原生 Manifest 和资源详情采用 64 MiB HTTP 响应上限，与 dbt-service 默认单产物大小一致；查询及其他响应仍为 8 MiB。若服务部署提高单产物限制，需同步评估平台读取边界。
