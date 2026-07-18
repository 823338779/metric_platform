# Metric Platform Agent 语义 API 设计

## 1. 目标

将 Metric Platform 扩展为可被 Data Agent 本地 Tools 稳定消费的受治理语义服务。平台负责语义目录、自然语言候选检索、对象上下文、维度成员解析、查询校验、关系规划、SQL 编译、只读执行、权限和查询证据。

Metric Platform 不依赖 Spring AI，不识别 Data Agent Skill，也不提供 MCP。它通过版本化 `/api/v1` HTTP API 和 `api/openapi.yaml` 发布能力。Data Agent 将这些 API 包装成本地 Spring AI Tools。

## 2. 设计原则

1. Metric Platform 是指标、维度、数据集和关系的唯一权威来源。
2. 调用方只提交稳定语义 ID 和受控 QuerySpec，不提交物理字段、Join、表达式或 SQL。
3. 搜索、详情、校验和执行应用相同的 tenant、身份和资源权限。
4. 所有可恢复问题返回稳定错误码和结构化候选，不依赖解析英文异常文本。
5. 查询执行和回答所需的定义版本、新鲜度和证据由平台返回。
6. 管理 CRUD 与 Agent 查询 API 权限分离。
7. 平台保持独立构建，不导入 Data Agent 实现类。

## 3. 范围

### 3.1 包含

- 指标、维度、数据集、字段和关系的 Agent 所需业务元数据。
- 增强语义搜索和批量语义上下文解析。
- 受控维度成员搜索。
- 支持运行时过滤、时间范围、排序和 limit 的 QuerySpec。
- 结构化校验问题、候选关系路径和规范化计划。
- 短期 planToken 和证据化查询结果。
- 查询 Client 角色、tenant/workspace 范围和审计。
- OpenAPI 契约版本化与 Data Agent 消费兼容信息。

### 3.2 不包含

- Spring AI ToolCallback、Tool Registry 或 Skill Runtime。
- 对话状态和用户澄清流程。
- Agent 自动创建或修改语义对象。
- 任意调用方 SQL。
- 跨数据源联邦查询。
- 一对多或多对多的扩张性关系遍历。
- 首期向量检索基础设施。

## 4. 语义元数据扩展

### 4.1 通用语义属性

数据集、指标、维度和关系应提供：

- `description`
- `aliases`
- `domain`
- `owner`
- `status`：`DRAFT`、`ACTIVE`、`DEPRECATED`
- `certificationStatus`
- `version`
- `updatedAt`

`name` 是稳定系统名称，`displayName` 是展示名称，`description` 是业务含义，`aliases` 是自然语言召回词。四者不能相互替代。

别名使用独立可检索子表或等价规范化结构，不使用不可索引的任意 JSON 作为唯一存储。状态为非 ACTIVE 的对象默认不进入 Agent 搜索结果，除非调用方显式请求且有权限。

### 4.2 指标

指标补充：

- `businessDefinition`
- `unit`
- `format`
- `aggregationBehavior`
- `defaultTimeDimensionId`
- `additivity`
- `metricType`：首期保留 `BASE`，派生指标支持时增加 `DERIVED`

首期必须提供 description、aliases、status 和 unit。派生公式、指标过滤和复杂可加性规则属于后续阶段。

### 4.3 维度

维度补充：

- `businessDefinition`
- `semanticType`
- `valueType`
- `defaultGranularity`
- `sensitivityLevel`

现有时间维度及 allowed granularities 保持权威。成员搜索只对允许暴露且敏感级别符合调用者权限的维度开放。

### 4.4 数据集和字段

数据集在现有 description 基础上补充：

- `businessGrain`
- `defaultTimeDimensionId`
- `freshnessPolicy`
- `qualityStatus`

字段补充 description 和 sensitivityLevel，用于管理和验证。Agent 查询 API 默认不返回 physicalName、schemaName、objectName 或物理类型细节。

### 4.5 数据集关系

关系补充：

- `description`
- `displayPath`
- `semanticRole`
- `fanoutSafe`
- `validationStatus`

关系方向、基数和字段对继续由平台管理。Agent 只看到可解释路径、关系 ID、方向、基数和安全警告，不看到物理 Join 条件。

## 5. API 能力

### 5.1 服务能力

新增只读能力接口：

```http
GET /api/v1/capabilities
```

返回 API 版本、支持的 QuerySpec 特性、最大 topK、最大 limit、支持的时间预设、planToken 支持状态和 catalogVersion。Data Agent Skill 可通过该信息检查最小兼容版本。

### 5.2 增强语义搜索

保留并扩展：

```http
POST /api/v1/semantic-objects:search
```

请求：

```json
{
  "query": "支付销售额",
  "objectTypes": ["METRIC"],
  "datasetIds": [],
  "domains": [],
  "statuses": ["ACTIVE"],
  "certifiedOnly": false,
  "topK": 10
}
```

响应候选包含：

- type、id、datasetId
- name、displayName、description、aliases
- score、matchReasons
- domain、status、certificationStatus
- version、updatedAt
- 指标 unit/aggregation 或维度 type/granularities 的有界摘要

首期搜索按以下权重排序：精确 name、精确 alias、精确 displayName、前缀、description 命中。相同分数优先 ACTIVE、认证对象和同域对象。搜索必须在数据库查询层分页和限制，不全表加载后在 JVM 过滤。

`matchReasons` 使用稳定枚举，例如 `EXACT_NAME`、`EXACT_ALIAS`、`DISPLAY_NAME`、`DESCRIPTION`。向量检索后续作为额外召回通道，不能绕过状态和权限过滤。

### 5.3 批量语义上下文

新增：

```http
POST /api/v1/semantic-objects:resolve
```

请求包含一组 `{type, id}`。响应按输入顺序返回：

- 完整业务定义和版本
- 所属数据集摘要与 businessGrain
- 指标单位、聚合行为和默认时间维度
- 维度类型、允许粒度和敏感级别
- 对象兼容性摘要
- 可用且安全的候选关系路径
- 数据源逻辑状态、新鲜度和质量警告

该接口是 Agent 消歧和查询准备接口，不返回管理 CRUD 所需的物理绑定信息。

### 5.4 维度成员搜索

新增：

```http
POST /api/v1/dimensions/{dimensionId}/members:search
```

请求：

```json
{
  "query": "华东",
  "matchMode": "AUTO",
  "topK": 10
}
```

响应包含规范值、展示值、匹配类型和有界上下文。约束：

- query 不得为空。
- topK 有服务端上限。
- 禁止借此枚举全部高基数成员。
- 敏感维度按权限拒绝或脱敏。
- 使用只读连接、查询超时和参数化条件。
- 结果不得返回未授权列或额外行内容。

## 6. QuerySpec

QuerySpec 扩展为：

```json
{
  "metricIds": ["uuid"],
  "dimensions": [
    {
      "dimensionId": "uuid",
      "granularity": "MONTH"
    }
  ],
  "filters": [
    {
      "dimensionId": "uuid",
      "operator": "IN",
      "values": ["华东"]
    }
  ],
  "timeRange": {
    "dimensionId": "uuid",
    "preset": "YEAR_TO_DATE",
    "timezone": "Asia/Shanghai"
  },
  "metricFilters": [],
  "sort": [
    {
      "metricId": "uuid",
      "direction": "DESC"
    }
  ],
  "limit": 10,
  "relationshipIds": []
}
```

首期实现 dimensions、dimension filters、timeRange、sort、limit 和 relationshipIds。metricFilters、同比环比、派生计算和分页游标后续增加。

过滤只能引用已治理维度 ID。操作符由维度 valueType 决定。所有值经过类型校验并绑定 JDBC 参数；调用方不能提交表达式字符串。

时间范围支持绝对 from/to 和受控 preset。平台使用请求 timezone 解析日历边界，并在 normalized request 中返回最终绝对范围。缺少 timezone 时使用租户配置，不能静默使用服务器本地时区。

`limit` 有服务端上限。排序只允许引用本次选择的指标或维度。

## 7. 查询校验

保留：

```http
POST /api/v1/queries:validate
```

成功响应包含：

- `valid`
- `normalizedRequest`
- `metrics` 和 `dimensions` 定义摘要及版本
- `planSummary`
- `resolvedRelationshipIds`
- `warnings`
- `catalogVersion`
- 可选 `planToken` 和 `expiresAt`

失败响应仍使用可解析响应体，不通过字符串异常承载候选：

```json
{
  "valid": false,
  "issues": [
    {
      "code": "AMBIGUOUS_RELATIONSHIP_PATH",
      "message": "存在多个安全关系路径",
      "objectId": "uuid",
      "candidatePaths": []
    }
  ],
  "warnings": []
}
```

稳定 issue code 至少包含：

- `SEMANTIC_OBJECT_NOT_FOUND`
- `SEMANTIC_OBJECT_INACTIVE`
- `INCOMPATIBLE_OBJECTS`
- `AMBIGUOUS_RELATIONSHIP_PATH`
- `MISSING_RELATIONSHIP_PATH`
- `INVALID_TIME_GRANULARITY`
- `INVALID_FILTER_OPERATOR`
- `INVALID_FILTER_VALUE`
- `INVALID_SORT`
- `UNAUTHORIZED`
- `DATA_SOURCE_UNAVAILABLE`
- `QUERY_TOO_EXPENSIVE`

系统级认证失败继续使用 RFC 9457 和对应 HTTP 状态。业务可修正的语义问题使用 validation issues，便于 Agent 在同一 Run 内修正。

关系歧义 issue 的 candidatePaths 返回关系 ID、displayPath、方向、基数和 warnings，不返回字段对。

## 8. planToken 与执行

阶段一允许 Data Agent 使用完全相同的 QuerySpec 调用 `POST /api/v1/queries`，平台重新校验和规划。

生产阶段新增短期 planToken。Token 绑定：

- normalized QuerySpec 哈希
- tenant/workspace
- subject 或服务授权范围
- 语义对象 ID 和版本
- catalogVersion
- 过期时间

执行接口：

```http
POST /api/v1/query-plans/{planToken}:execute
```

Token 失效、对象版本变化、权限变化或数据源停用时拒绝执行并返回稳定错误。Token 不包含凭据或明文 SQL，也不作为永久保存查询。

查询执行继续使用平台编译器、只读连接、参数绑定、超时和最大结果行数。Data Agent 无法覆盖这些限制。

## 9. 查询结果与证据

查询响应包含：

- `queryId`
- `columns`
- `rows`
- `rowCount`
- `truncated`
- `metrics`
- `dimensions`
- `appliedFilters`
- `timeRange`
- `dataFreshness`
- `warnings`
- `catalogVersion`
- `evidence`

每个列定义包含 semanticObjectId、name、displayName、logicalType、unit、format 和 time granularity。结果列顺序继续遵循 dimensions 在前、metrics 在后及请求内顺序稳定规则。

`evidence` 提供生成回答所需的指标与维度定义快照、对象版本、关系路径摘要、数据集和查询时间，不暴露物理 SQL。结果达到上限时必须标记 truncated，不能让 Agent 把有限结果描述成完整全集。

## 10. 认证与授权

现有硬编码 admin/query token 仅适用于本地开发，生产设计要求：

- 管理客户端和查询客户端使用不同 scopes。
- 查询客户端不能访问数据源凭据和管理写接口。
- Token 绑定 tenant/workspace；Data Agent 不能用一个全局 Token读取全部目录。
- subject、sessionId、runId 和 correlationId 作为受信任调用上下文传递并进入审计。
- 搜索、resolve、成员搜索、validate 和 execute 使用相同资源权限过滤器。
- 不存在“搜索可见但执行不可见”的默认权限差异；如策略确需差异，响应必须明确标记不可执行。

API 角色至少区分：

- `PLATFORM_ADMIN`
- `SEMANTIC_EDITOR`
- `METRIC_QUERY_CLIENT`

控制器或服务层对每个操作执行 scope 检查，不能仅要求 `/api/v1/**` authenticated。

## 11. 审计与可观测性

每次 Agent API 调用记录：

- tenant、subject、service client
- sessionId、runId、correlationId
- endpoint 和状态
- 搜索词哈希或安全摘要
- 语义对象 ID 和版本
- queryId、planToken 哈希
- 结果行数、截断、耗时
- issue/error code

不记录服务 Token、JDBC 凭据、未脱敏成员值或完整编译 SQL。日志、指标和追踪通过 correlationId 关联 Data Agent Tool 事件和 Metric Platform 请求。

关键服务指标包括搜索延迟、搜索无匹配率、候选点击/选择反馈、校验成功率、歧义率、执行成功率、查询耗时、截断率和权限拒绝率。

## 12. 兼容与发布

`api/openapi.yaml` 是唯一跨服务契约。每次契约变更同时更新：

- Metric Platform Backend DTO 和 Controller。
- Metric Platform Frontend Client。
- Data Agent `MetricPlatformClient`。
- Data Agent 运行时 `metric-platform-analysis` Skill。
- 开发环境 `.codex/skills/metric-platform` API reference。

`GET /api/v1/capabilities` 返回当前 API 能力。新增可选响应字段保持向后兼容；QuerySpec 破坏性变更通过协调版本发布，不在两个工程间共享 Java DTO。

## 13. 实施阶段

### 阶段一：最小 Agent 语义闭环

- 指标和维度 description。
- aliases、status、指标 unit、数据集 businessGrain。
- 数据库级增强语义搜索。
- 批量 semantic resolve。
- 现有 QuerySpec 的结构化 validation issues。
- 查询 Client 的接口级权限。
- 完整查询结果证据。

### 阶段二：业务查询表达力

- dimension member search。
- dimension filters。
- timeRange 和 timezone。
- sort 和 limit。
- 成本与新鲜度 warnings。
- Data Agent 可恢复错误所需的候选路径。

### 阶段三：生产治理

- planToken。
- tenant/workspace 短期服务 Token。
- 混合关键词与向量搜索。
- 认证、生命周期和质量排序。
- 查询审计、追踪和反馈指标。
- 派生指标、同比环比和分页能力评估。

## 14. 故障语义

- 无候选返回空 candidates，不伪造近似对象。
- 对象不存在或已下线返回结构化 issue。
- 关系缺失和歧义返回候选路径或明确缺失，不猜 Join。
- 数据源不可用不回退其他数据源。
- 成员搜索超时返回可重试问题，不执行无限扫描。
- 查询超时、行数上限和截断状态进入结果和审计。
- 不向调用方返回数据库异常、连接串、凭据或编译 SQL。

## 15. 验收标准

- 自然语言搜索覆盖 name、displayName、aliases 和 description，并在数据库层限制结果。
- 搜索结果包含选择实体所需的业务定义、匹配原因、状态和版本。
- 批量 resolve 不暴露物理绑定和凭据。
- “华东”类成员可在授权维度中受控解析。
- QuerySpec 可表达时间、过滤、排序和 Top N，所有值参数化编译。
- 校验歧义和错误使用稳定 issue code 和结构化候选。
- 执行结果包含 queryId、语义列、口径版本、新鲜度和截断状态。
- 查询 Client 无法调用管理写接口或读取其他 tenant 目录。
- Data Agent 只依赖 OpenAPI 和认证配置，Metric Platform 不依赖 Data Agent 或 Spring AI。
- 现有非扩张关系规划、多事实预聚合、固定过滤和只读执行约束保持成立。

