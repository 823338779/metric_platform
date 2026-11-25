# MCP 迁入 Metric Platform

用户已选定方案 A。由平台交付本地 stdio MCP Server，使用平台账号的 Bearer 令牌调用平台 HTTP API。方案 B 仅移动源码并继续使用 dbt-service 的令牌，客户端改动较少，但查询无法归因到平台账号。方案 A 让目录读取、构建提交和查询分别执行平台现有的租户校验、管理者校验和资产授权。

## 发布与接入

建议沿用现有 Python MCP SDK，在平台仓库的 `mcp/` 中独立打包。启动命令改为 `metric-platform-mcp`，进程从 `METRIC_PLATFORM_URL` 和 `METRIC_PLATFORM_TOKEN` 读取配置。标准输出用于 MCP 协议，令牌只发送给配置的平台。平台 Java 后端继续独立构建。

dbt-service 移除 MCP 启动命令、Python 客户端及服务端模块、专属可选依赖和安装说明，更新锁文件。将 MCP 用例迁入平台，原有目录搜索和查询 HTTP API 继续作为平台的上游接口。更新平台 README 中的安装、令牌配置和工具调用示例。

## 七个工具

下表中 HTTP 路径均以 `/api/dbt` 为前缀。

| MCP 工具 | 平台接口 | 调用方约定 |
| --- | --- | --- |
| `get_current_deployment` | GET `/deployments/current` | 沿用仓库、环境、分支，区分无部署和最近尝试；一次分析固定取得的 activeBuildId |
| `search_catalog` | 新增 GET `/builds/{buildId}/catalog/search` | 平台验证构建属于本租户，调用 dbt-service 现有分页搜索。保留关键词、类型、游标与精确 resourceId 查询，避免读取完整目录 |
| `get_resource` | GET `/builds/{buildId}/resources/{resourceId}` | 使用固定构建下的完整资源 ID，保留节点、源码和不可用错误 |
| `get_available_dimensions` | POST `/builds/{buildId}/available-dimensions` | 先在同一构建精确解析指标 ID，遇到重名返回歧义错误；保留平台返回的选项、粒度、运算符及 granted |
| `query_metrics` | POST `/builds/{buildId}/queries` | 固定 QUERY 模式，保留过滤、排序、时间范围、行数限制和分组；由平台执行授权并记录登录账号 |
| `submit_build` | POST `/environments/{environmentId}/builds` | 提供 environment_id、expected_config_version、仓库、分支、固定 SHA、显式部署策略及幂等键。执行快照与提交身份由平台确定，移除 execution 和 origin 入参 |
| `get_build` | GET `/builds/{buildId}` | 区分构建状态、关联部署和目录可用性，查询可用性以实际查询为准 |

## 已确认的查询兼容范围

旧工具除普通分组外，还支持形如 `{"name":"channel","bindings":{"metric.a.sales":{"optionId":"A"},"metric.b.cost":{"optionId":"B"}}}` 的分组。每个选项绑定到单个指标；平台目前只接受整组指标的共享 optionId。

**用户已确认保留这项能力。** 平台查询契约允许每个分组使用普通选项或显式绑定。校验绑定覆盖所选指标、指标名称唯一，并在每个指标自己的合法选项中逐项检查维度授权和粒度。沿用部署有效性复核，再把请求交给同一构建执行。同步更新平台 OpenAPI 和测试。未经授权的选项不得进入执行或成功审计。

若此次限定在现有平台查询契约内，MCP 仅公开共享 optionId 分组，对旧 bindings 输入返回明确的参数错误。这个选择减少了平台鉴权层的改动，但使用不同指标维度路径的客户端需调整查询。

## 验收

1. 在独立安装的 MCP 包上通过真实 stdio 完成初始化、列出七个工具、发起调用。用平台 HTTP 夹具检查实际路径、令牌、请求字段、结构化结果和错误。对照原用例覆盖歧义、超限目录分页、空结果、部署切换、构建受理未知及同键重试。
2. 通过平台后端测试验证跨租户构建隔离、只读账号提交构建被拒、查询逐项授权和拒绝后的执行次数。若保留显式绑定，覆盖部分授权、缺失或多余绑定以及同一维度不同粒度。
3. 验证 dbt-service 可独立安装和启动，目录搜索和查询的 HTTP 用例继续通过，发布包中移除 MCP 入口和专属依赖。分别检查两个产品及根仓库差异。
