# dbt 全量构建与版本发布运行说明

## service-proxy 分支目录与查询

使用 `metric.publication.mode=service-proxy` 时，dbt-service 是唯一发布者；旧平台 Git Webhook、扫描和本地目录导入不能同时启用。平台通过 `/api/v2/semantic-projects/{projectId}/branches` 读取登记分支，创建和删除由 Agent 调用服务完成，页面刷新不会触发构建。

目录 URL 为 `/dbt/projects/{projectId}/catalog?branchId=...&releaseId=...`。没有 branchId 的旧目录链接明确解析生产登记；没有 releaseId 时首次采用该分支活动版本。此后页面保留固定版本，活动版本推进需显式刷新。资源详情、源码、差异和查询均固定此上下文。复制链接会保留原分支实例和版本；删除后同名新分支不会接管旧链接。

页面分别展示可用提交、观测到的最新提交和构建历史。最新构建失败时旧活动版本仍可查询；选择失败版本只查看构建状态和安全测试摘要，不把旧资源冒充失败提交的结果。首次预览未就绪时禁用资源和查询入口，404、410、503 不转换为空目录或生产数据。

开发分支差异以创建时固定基线为准，同时提示线上是否推进、资源基线是否可用和内容是否截断。合并请求链接仅接受服务返回的 HTTP(S) 地址。原始执行日志和连接配置不通过此界面返回。

选项、PREVIEW、DIMENSION_VALUES 和查询结果使用固定分支/版本。受理响应丢失后保持原请求体和幂等键；旧查询返回时不绘制到已切换的分支页面。HTTP 410 要求显式刷新并重新选择资源，不自动重发到新版本。无分支的旧 API 仍指向生产。

service-proxy 浏览器验证命令在根 README 的验证节维护。浏览器 fixture 验证 UI 与请求身份；真实 Forgejo、数据库权限和 SQL 结果一致性由工作区的跨服务验收覆盖。

## legacy 发布含义

平台只消费固定配置仓库 `main` 的最新提交。提交 SHA 变化时，平台以该 SHA 请求外部服务在固定的 StarRocks `dbt_ecom` schema 中使用本 run 专属表前缀全量构建并运行完整测试。服务报告 READY，且摘要、配置、工具链、物理 relation 和查询能力验证通过后，平台在 PostgreSQL 事务中导入只读目录并切换 `activeReleaseId`。Git push、构建成功与发布成功是三个不同状态；失败保持旧版可查询。

只有 SHA、输入摘要、配置版本和工具链版本均未变化时，重复刷新才跳过创建新构建。只要 SHA 变化，即使仅修改 README 或提交的 dbt 输入摘要相同，也创建独立候选、幂等键和运行任务，重新全量构建并发布，不复用旧 run。新候选的目标 SHA 与实际构建来源 SHA 一致；旧的在途候选标记为过时。相同 SHA 的在途任务继续推进，失败任务不会因重复刷新自动重试；新的 SHA 不受上一次成功或失败状态影响。

## Forgejo main 自动发布

本节用于 `metric.publication.mode=legacy`。在 Forgejo 的 `agent-admin/metrics` 仓库设置中添加 **Forgejo** 类型 Webhook：URL 为 `http://host.docker.internal:8090/internal/git-sync/events`，方法 POST，内容类型 `application/json`，Secret 和 Authorization Header 均留空，仅选择 Push Events，分支过滤填写 `main`，启用 Active。这是 Forgejo 容器访问宿主机平台的地址；根工作区 Compose 已将该主机加入 Webhook 允许名单。

应用会自动读取仓库外的 `${user.home}/.metric-platform/webhook.properties`。在该文件配置 `metric-platform.git-sync.enabled=true` 即可，Forgejo 原生通知不需要密钥、签名或登录鉴权。`application.yml` 的 `forgejo-repository=agent-admin/metrics` 匹配事件中的仓库全名，`repository-id=metrics_store` 仍用于平台内部发布记录。修改本机配置后重启平台。

平台接收 [Forgejo 原生 Webhook 载荷](https://forgejo.org/docs/latest/user/repository/webhooks/)，使用 `X-Forgejo-Event` 区分事件，不校验签名或 Authorization。原生载荷上限为 1 MiB，旧自定义 `X-Git-Signature` 协议仍需签名且保持 4096 字节。非 Push、非 main、删除分支及未改变 SHA 的事件不触发；其他仓库被拒绝。通知只唤醒固定远端读取，不允许通知指定构建远端或执行命令。

发布复用下文的全量构建和失败保留旧版语义。重复投递按实际 main SHA 和构建配置去重；连续推送可合并为最新 main，不保证每个中间 SHA 都构建。Webhook 返回 202 仅表示已接收。可在 Forgejo 的 Recent Deliveries 查看状态，并在指标平台的发布历史核对 SHA 与最终状态。停机期间的漏投递可通过 Forgejo 重投；平台启动也会扫描一次。`service-proxy` 模式禁止此发布入口并返回 409，不应同时启用两个发布者。

## 开关和固定绑定

空定义是有效的清空发布：manifest 中模型、source、语义模型和指标均明确为空，且原生目录与构建结果均为空时，运行服务跳过无指标可执行的查询探针，并报告查询能力和代表性查询结果为 false。仍须校验产物格式、版本、摘要、配置和目标 SHA；缺失或损坏的产物不能视为空发布。平台将活动版本切换为空目录，旧物理表、视图与发布历史保留。构建交给 dbt 执行；删除定义不等于删除旧物理表。平台不包含自动清理调度、删表调用或相关开关。

默认 `metric-platform.git-sync.enabled=false`，不执行启动自动扫描，也不开放 Webhook；页面手动刷新不受此开关限制。手动发布仍须配置固定仓库、项目绑定和 `dbt-runtime.enabled=true`，但不要求 Webhook 密钥。已受理的构建继续由后台推进，不会因为关闭自动同步而中断。启用发布前应完成下述真实库验收。配置直接写入 `backend/metric-app/src/main/resources/application.yml`；以下是待替换为实际绑定的示意值：

```yaml
metric-platform:
  git-sync:
    enabled: false
    repository-id: metrics
    remote: http://127.0.0.1:3000/agent-admin/metrics.git
    cache-directory: E:/IdeaProjects/agent-root/tmp/metric-debug/git-cache
    project-id: sales
    project-subdir: project
    profile-binding-id: starrocks
    schema-name: dbt_ecom
    config-version: local-debug-v5
    toolchain-version: dbt-core 1.12.5 / metricflow 0.213.0
    # 仅旧自定义协议需要密钥，Forgejo 原生通知保留为空。
    webhook-secret: ""
    poll-interval: PT30S
  dbt-runtime:
    enabled: false
    base-uri: http://127.0.0.1:8000
    timeout: PT30S
```

平台只支持 dbt 项目发布。`remote`、profile、schema、运行服务地址及密钥只由部署配置决定，不接受 Git 内容、Webhook 或公开 API 覆盖。运行服务项目绑定的 `schemaName` 必须同为 `dbt_ecom`。`config-version` 在 profile、schema、环境变量或工具链等构建输入改变时必须变更，促使重新构建。`POST /api/v1/semantic-projects:refresh` 接收已登录用户的手动刷新请求；开启自动同步后，`POST /internal/git-sync/events` 也可发送刷新信号。信号在下一轮后台调度中处理；202 只表示已受理，不能当作发布成功。

## 服务接口与运营

外部服务需实现 `POST/GET /v1/project-runs`、按幂等键查询、`GET /v1/project-runs/{runId}/catalog`、`POST/GET /v1/query-jobs`、按幂等键查询。READY 必须附带固定 SHA、输入摘要、profile 和配置版本、工具链版本、目标 schema、三个产物摘要、完整测试结果、代表性查询结果、查询能力及物理 relation 验证。平台不直接执行 dbt 命令或 StarRocks DDL。固定 schema 模式拒绝 dbt 模型与项目级运行钩子；自定义宏和 materialization 仍属于受控 Git `main` 的可信代码。
指标平台不调用运行服务的物理清理接口；需要删除具体旧表时，由运维显式使用 dbt 工具处理。

发布历史和错误码通过 `/api/v1/semantic-projects/{projectId}/releases` 查看；目录与查询须携带 `releaseId`。查询提交返回 202 和 `queryId`，GET 任务状态返回固定版本与类型化结果。非活动历史版本的目录或新查询返回 410，已受理查询仍可完成。历史构建产物保留。历史记录中的 cleanupState 仅保留兼容性和已有清理结果，不表示有清理任务排队。

## 启用前验收门槛

平台固定版本运行服务先前已在本机 StarRocks 上验证电商示例的全量构建、目录、成交金额、精确去重订单数、月份与地区分组、客户维表关联、比率指标、筛选、维度值和表预览。固定 `dbt_ecom` schema 与前缀表的升级仍须重新完成真实库验收。通用 `/v1/metricflow/jobs` 仍使用上游 CLI，并继续对 StarRocks 返回 `metricflow_adapter_not_supported`。上述两个开关保持 `false`，直到部署环境完成平台发布与浏览器端到端验收，包括失败不影响旧版、manifest 和 semantic manifest 的物理 relation 指向，以及指标结果与基准 SQL 一致。平台的 PostgreSQL、临时 Git 和桩服务测试不能代替这些真实库验收。
