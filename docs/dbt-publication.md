# dbt 全量构建与版本发布运行说明

## 发布含义

平台只消费固定配置仓库 `main` 的最新提交。dbt 项目受支持输入发生变化时，平台以该提交 SHA 请求外部服务在独立 StarRocks schema 中全量构建并运行完整测试。服务报告 READY，且摘要、配置、工具链、物理 relation 和查询能力验证通过后，平台在 PostgreSQL 事务中导入只读目录并切换 `activeReleaseId`。Git push、构建成功与发布成功是三个不同状态；失败保持旧版可查询。

项目输入目录外的 README 改动只记录 `observedSha`，不发起新构建。若 README 提交发生在构建过程中，最新提交可以复用同一物理 run，但发布记录同时保留目标提交 SHA 与实际构建来源 SHA。若 main 的 dbt 输入再次变化，旧候选过时，新提交重新全量构建。

## 开关和固定绑定

默认 `metric-platform.git-sync.enabled=false`，即不访问 Git。启用发布必须同时配置运行服务，并先完成下述真实库验收。示意配置：

```yaml
metric-platform:
  git-sync:
    enabled: false
    repository-id: metrics
    remote: ${METRIC_GIT_REMOTE}
    cache-directory: ${METRIC_GIT_CACHE_DIRECTORY}
    project-id: sales
    project-subdir: project
    profile-binding-id: ${METRIC_DBT_PROFILE_BINDING_ID}
    config-version: ${METRIC_DBT_CONFIG_VERSION}
    toolchain-version: ${METRIC_DBT_TOOLCHAIN_VERSION}
    webhook-secret: ${METRIC_GIT_WEBHOOK_SECRET}
    poll-interval: PT30S
  dbt-runtime:
    enabled: false
    base-uri: ${METRIC_DBT_RUNTIME_URL}
    timeout: PT30S
```

平台只支持 dbt 项目发布。`remote`、profile、schema、运行服务地址及密钥只由部署配置决定，不接受 Git 内容、Webhook 或公开 API 覆盖。`config-version` 在 profile、环境变量或工具链等构建输入改变时必须变更，促使重新构建。`POST /internal/git-sync/events` 仅唤醒刷新；启动扫描和周期轮询负责补偿丢失通知。

## 服务接口与运营

外部服务需实现 `POST/GET /v1/project-runs`、按幂等键查询、`GET /v1/project-runs/{runId}/catalog`、`POST/GET /v1/query-jobs`、按幂等键查询，以及 `POST /v1/project-runs/{runId}:cleanup`。READY 必须附带固定 SHA、输入摘要、profile 和配置版本、工具链版本、独立 schema、三个产物摘要、完整测试结果、代表性查询结果、查询能力及物理 relation 验证。平台不直接执行 dbt 命令或 StarRocks DDL。
清理接口必须对相同 `runId` 幂等：平台可能在服务清理成功、写回 `CLEANED` 前重启并再次调用。

发布历史和错误码通过 `/api/v1/semantic-projects/{projectId}/releases` 查看；目录与查询须携带 `releaseId`。查询提交返回 202 和 `queryId`，GET 任务状态返回固定版本与类型化结果。已清理版本的目录或新查询返回 410。清理由后台按物理 `runId` 组执行，当前、上一个成功发布版本及在途查询会阻止回收；失败可重试，发布元数据保留。

## 启用前验收门槛

平台固定版本运行服务已在本机 StarRocks 上验证电商示例的全量构建、独立 schema、目录、成交金额、精确去重订单数、月份与地区分组、客户维表关联、比率指标、筛选、维度值和表预览。通用 `/v1/metricflow/jobs` 仍使用上游 CLI，并继续对 StarRocks 返回 `metricflow_adapter_not_supported`。上述两个开关保持 `false`，直到部署环境完成平台发布与浏览器端到端验收，包括失败不影响旧版、manifest 和 semantic manifest 的物理 relation 指向，以及指标结果与基准 SQL 一致。平台的 PostgreSQL、临时 Git 和桩服务测试不能代替这些真实库验收。
