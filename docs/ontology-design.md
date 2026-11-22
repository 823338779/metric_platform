# Metric Platform 业务本体设计草案

建议从一个只读业务闭环起步：让运营人员从销售额找到客户，再沿“下单客户”关系查到具体订单。定义与测试放在 dbt 项目，dbt-service 随固定构建校验和发布，metric-platform 负责授权、展示与访问归因。首版直接读已部署模型，把图存储和写回交易系统的决策留给实际查询负载和业务需求。

调研日期为 2026-11-20。现状依据两个仓库的契约、服务公开说明和平台授权实现。下文新增端点、产物和校验门槛均为设计提案，尚未做运行联调。

## 两种语义模型分别解决什么问题

Foundry Ontology 把业务人员识别和操作的事物建模为对象。以订单为例，订单类型规定有哪些属性，主键标识一笔具体订单。“由客户下单”定义订单与客户之间的关系。“取消订单”如果要改变订单或产生副作用，则要定义成动作。多个对象类型确实需要共享属性或能力时，才有提取接口的理由。[Foundry 核心概念](https://www.palantir.com/docs/foundry/ontology/core-concepts) · [Foundry 接口](https://www.palantir.com/docs/foundry/interfaces/interface-overview)

Foundry 对关系和权限的处理尤其值得借鉴。只有业务人员能解释用途的关系才适合成为导航入口。若关系本身带有效期或角色，可以把这段关系建成独立对象。能查看对象类型的属性定义、能读取某笔订单的属性值、能对订单执行操作，分别涉及资源、实例和动作授权。[Foundry 本体建模指南](https://www.palantir.com/docs/foundry/ontology/ontology-structural-guidance) · [Foundry 权限模型](https://www.palantir.com/docs/foundry/object-permissioning/overview)

dbt Semantic Layer 解决指标如何计算、能够按哪些维度分析的问题。语义模型中的 entity 声明连接键及其角色，dimension 提供时间或分类分组，metric 定义聚合或组合计算。例如，“按客户地区统计订单金额”需要订单金额的聚合口径、客户地区维度，以及从订单到客户的可用连接路径。[dbt 语义模型](https://docs.getdbt.com/docs/build/semantic-models) · [dbt 指标定义](https://docs.getdbt.com/docs/build/metrics-overview)

**据此建议分别定义业务导航关系和可用的指标查询路径。** 假设一笔订单有两条明细，沿“订单包含明细”浏览这两条记录符合业务含义。沿同一方向连接后直接汇总订单金额，会把同一笔订单金额计入两次。MetricFlow 根据语义模型中的 entity 角色决定允许的连接，包括限制会放大行数的连接。本平台可以展示“订单包含明细”，指标是否支持某个分组仍应以对应发布版本的查询能力为准。[dbt 连接规则](https://docs.getdbt.com/docs/build/join-logic)

## 两个仓库已经解决了什么

| 边界 | 已有行为 | 对本体的约束 |
| --- | --- | --- |
| 构建与部署 | 平台提交固定执行快照，dbt-service 持有产物和生效部署。每个环境一次成功切换可以替换另一分支的部署，并回收旧产物 | 本体随同一个 build 发布。请求从进入平台到服务执行都携带同一 buildId |
| 发现与展示 | 服务从封存 Manifest 提供原生目录、资源详情和血缘。平台给租户内原生目录标注资产使用权 | 可以复用模型及列描述。业务角色和基数由领域负责人声明，不能从 ref 血缘推出 |
| 查询与授权 | 平台区分指标、维度和数据集授权。服务支持同步 QUERY、EXPLAIN、PREVIEW、DIMENSION_VALUES | 看销售额、按客户维度筛选、读客户明细是三次不同的权限判断。PREVIEW 只有 selector 和 limit，无法保证返回指定客户 |
| 历史与审计 | 平台记录执行归因。旧构建记录可见，已回收的产物返回 410 | buildId 固定模型语义和执行绑定；底层数据可能变化。要重现数据还需快照或可信数据水位 |

平台规则中的固定 releaseId 原则，在当前公开协议中通过 buildId 实现。旧 release 与 build 的历史关联需要可信映射。平台落地以 v3 同步接口为准。[平台 README](../README.md) · [公开契约](../api/openapi.yaml) · [平台鉴权](../backend/metric-app/src/main/java/com/metricplatform/catalog/GrantedQuery.java) · [服务 README](../../dbt-metricflow-service/README.md)

服务侧 10 月的分析设计已经提出 `config.meta.ontology`、稳定类型 ID、基数和逐构建测试证据，并设想独立分析服务、图索引和受控 SQL。该路线适合自由分析与证据会话。本方案面向控制台的已知问题和固定访问路径，复用前半段建模约定，把编译产物纳入现有构建，把对象取数收窄为类型化操作。两端继续通过 HTTP 独立构建。[已有本体评估](../../dbt-metricflow-service/docs/dbt-model-as-ontology-assessment.md) · [分析服务设计草案](../../dbt-metricflow-service/docs/superpowers/specs/2026-10-26-business-ontology-analysis-design.md)

## 建议用一条用户路径定义首版

运营人员查看“本月销售额”，按已授权的客户维度筛选，打开某个客户，再列出该客户的订单。前半段验证指标口径和归属，后半段验证业务对象的身份和关系。若指标维度只返回地区或客户名称，就停在聚合视图；只有拿到明确授权且能唯一对应客户主键的原始键时才提供实例跳转。服务存在显示值映射，多个原始键可以显示同一标签，前端不能用显示文本还原身份。[服务维度映射](../../dbt-metricflow-service/README.md)

| 概念 | 电商试点 | 契约里必须回答的问题 |
| --- | --- | --- |
| ObjectType / Object | commerce.customer、commerce.order；订单 O-42 | 类型 ID 跨兼容构建稳定。实例身份由租户、环境、类型 ID、按类型编码的业务键组成。一次读取再固定 buildId |
| Property | order.status、order.paid_at | 属性的名称、数据类型、空值和来源列是什么？支付时间与下单时间各自表示什么？ |
| LinkType | 下单客户与订单，订单到客户为多对一 | 买家与收货人即使都指向客户，也使用不同的关系 ID。正向和反向导航共享一份映射 |
| ObjectSet | 客户 C-17 的订单，按订单主键分页 | 一组对象由固定构建、已声明关系与键值限定。首版用这种有界集合即可支持一对多导航 |
| Metric binding | 客户卡片显示选定月份的 revenue | 绑定指标名、业务角色、客户原始键对应的维度和适用粒度。指标定义、时区和计算交给现有查询链路 |
| ActionType | 订单发起退款 | 明细读取和退款授权独立。写入需要交易系统提供命令、状态检查和处理回执，属于后续立项 |

若一个订单有两个明细，“订单销售额”仍只计一张订单。用户可以沿业务关系浏览两条明细，指标继续走 MetricFlow 的合法查询路径。尤其要区分买家、卖家两条身份路径。服务已经给出按指标分别绑定维度的用例，当前平台的普通查询鉴权按 optionId 校验。首版只开放两端都能唯一解析并授权的单指标路径；多指标多角色绑定要先扩展平台契约及逐绑定鉴权。[服务多通路用例](../../dbt-metricflow-service/docs/examples/native-metric-dimension-bindings.md) · [平台查询鉴权](../backend/metric-app/src/main/java/com/metricplatform/catalog/GrantedQuery.java)

## 定义、编译和生效

建模入口放在源 dbt 工程，由领域负责人确认“订单”“下单客户”的含义，工程维护者提交模型、声明和数据测试，同一提交一起评审。沿用服务草案中的字段。以下是拟议的项目协议片段，字段绑定位于对应模型的 config.meta 中。客户模型另行声明 commerce.customer，以 customer_id 为主键。

```yaml
ontology:
  schema_version: 1
  type_id: commerce.order
  kind: object
  label: 订单
  identity: [order_id]
  grain: [order_id]
  properties:
    - {id: status, column: order_status}
    - {id: paid_at, column: paid_at}
  relationships:
    - relation_id: order_placed_by_customer
      predicate: placed_by
      target_type: commerce.customer
      source_fields: [buyer_id]
      target_fields: [customer_id]
      cardinality: many_to_one
      nullable: false
```

1. **声明与解析。** 一个类型在每个构建中绑定一个实际可查的模型。dbt-service 从该构建的 Manifest 解析节点和字段，记录模型 unique_id、属性投影、主键、关系和指标引用。公开类型视图输出业务 ID、说明和导航能力。物理表名及连接表达式留在服务端。首版限定单列主键、同环境、当前状态、单跳等值关系。历史、复合键或多对多需求先用显式模型澄清粒度，例如把订单明细建成对象。
2. **构建验证。** 拒绝重复类型和关系 ID、悬空目标、缺列、类型不兼容及歧义指标角色。实例能力还要求同次构建的主键唯一、非空和关系引用测试通过。可空关系明确处理 NULL。保存测试 ID、结果、buildId 与摘要；仅有 Manifest 中的测试定义不能视作执行证据。无本体声明的项目沿用今天的构建流程。
3. **发布。** 建议试点环境显式启用本体校验门槛。dbt-service 在部署资格检查前封存本体产物，绑定 buildId、commitSha、schemaVersion、编译器版本和摘要。声明无效或必需测试未通过则不切换这个环境的部署。切换和回收复用现有部署生命周期；同构建重试校验同一摘要。在原服务的部署事务之外另设当前本体指针，会让指标与对象来自不同版本。
4. **变更与恢复。** 增加说明和可选展示属性可按兼容变更交付。替换主键含义、粒度或关系角色时使用新的业务 ID 并评审授权影响。现有数据集授权按环境与原生名称可跨分支沿用，同名模型换义时必须核对原授权。旧页面或游标遇到切换收到 410，用户显式选择新版本。恢复已回收版本时按服务支持的流程重新提交已知提交并取得新 buildId。[部署与保留策略](../../dbt-metricflow-service/README.md) · [平台资产身份](../README.md)

## 两端各自需要做什么

| 交付方 | 负责的变更 | 持久化边界 |
| --- | --- | --- |
| dbt 项目 | 声明对象、关系、键、暴露属性及指标绑定，提供模型与测试 | Git 管定义，仓库存对象行 |
| dbt-service | 发布时校验并封存只读本体产物；执行固定构建下的对象定位、单跳关系读取与既有指标查询 | 产物跟随构建。首版直接读取已绑定的数据模型，用真实查询计划确认主键和外键访问成本 |
| metric-platform 后端 | core 定义端口与 DTO，engine 做 HTTP 适配，app 校验可信租户、环境、构建、账号授权并做审计；persistence 复用资产授权 | 平台保存授权与访问归因。新增业务审计表须经 Flyway，tenant_id 非空、带归属说明并按租户关联，通过 Mapper 读写 |
| metric-platform 前端 | 在现有固定构建目录增加对象入口，展示类型、属性、关系方向、关联指标。实例页支持“下单客户”“该客户的订单”等明确操作 | 页面上下文保留 buildId。迟到响应不能覆盖已切换的页面 |

**建议的 HTTP 契约。** 以下路径均为提案。平台使用前缀 `/api/dbt/builds/{buildId}/ontology`，服务使用 `/builds/{buildId}/ontology`。平台从服务端身份解析归属并用内部凭据调用服务。指标请求继续使用已公布的 queries 和 available-dimensions。

| 操作 | 请求和响应的最小内容 |
| --- | --- |
| GET 根路径，读取类型目录 | 返回 buildId、schemaVersion、类型、公开属性、带方向的关系和指标绑定。无声明返回明确的空集合，构建不可用返回 410 |
| POST /objects/lookup，按键读取 | 例：请求 `{"typeId":"commerce.order","key":"O-42"}`。服务按已验证的键类型绑定参数，返回 buildId、业务键、声明允许的属性。无匹配返回 404 |
| POST /links/traverse，单跳导航 | 例：请求 `{"relationId":"order_placed_by_customer","direction":"inverse","sourceKey":"C-17","limit":20}`。按关联声明解析源和目标，仅返回目标投影和可选 nextCursor。按唯一目标键稳定排序，游标绑定构建、关系、方向、源键与读者上下文，每页重新鉴权 |

提议服务使用有界 limit 和参数化读取，表列只取自编译产物。禁止客户端传物理表名、SQL、连接条件或自定义 selector。缺少当前构建的授权登记时停止读数；服务故障沿用非 2xx 语义，不伪造“客户没有订单”。实现时平台路由、错误模型和调用方一起更新 OpenAPI，双方用固定契约样例独立验收。

**授权示例。** 按现有产品策略，租户内可发现类型及关系的业务说明。读 O-42 要求订单数据集使用权。沿订单查客户须同时拥有订单、客户两个数据集使用权，在找对象前先检查权限；无权统一返回 403，不透露客户是否存在或订单数量。指标卡另查指标权，按客户筛选还需客户键的维度权。指标接口用当前构建和指标集合重新获取合法 optionId；无法唯一匹配就禁用该卡片。撤权后下一次读详情或翻页立即生效。浏览器不持有 dbt-service 凭据。[目录与授权实现](../backend/metric-app/src/main/java/com/metricplatform/catalog/AssetAccessService.java)

首版假设获准读取试点数据集的人有权读取其全部行；只选已按租户隔离、适合现有整数据集授权的模型。若需要“销售员只看自己的客户”或“隐藏手机号”，现有 PREVIEW 也要纳入权限设计。在上线该数据集前提供受限投影并限制原数据集授权，或先交付所有读取入口共同执行的行列策略。审计记录读者、租户、buildId、类型或关系、操作及结果状态；原始客户键和属性值避免写入普通日志。

## 上线顺序和验收

| 阶段 | 可实际演示的结果 | 进入下一阶段的门槛 |
| --- | --- | --- |
| 1 · 业务字典与指标 | 从已部署构建找到订单、客户、“下单客户”关系及其指标，从客户类型进入现有指标查询 | 同一提交可重复生成相同内容摘要；缺失关系字段的候选不能替换活动构建；旧工程仍能正常构建 |
| 2 · 可读对象 | 由 C-17 打开其订单，再反查下单客户；从唯一客户键查看当月销售额 | 两租户使用相同业务键互不串读；仅有指标权的账号不能取明细；翻页中途撤权被拒；切换构建后旧链接返回 410；订单含两条明细时指标和直接 MetricFlow 查询一致 |
| 3 · 用真实需求决定扩展 | 用试点环境的对象数、每客户订单分布和并发测量详情及关系查询的 P95、扫描量与超时率 | 先由业务约定延迟预算，压测大客户和深分页。已有模型满足预算就沿用；出现需要全文检索、频繁多跳或查询延迟持续超标时，再评估对象索引的同步、水位和权限成本 |
| 4 · 运营动作，独立立项 | 如确需“发起退款”，平台提交请求，由订单交易系统受理并返回命令 ID | 交易系统明确归属与许可，独立动作授权、前置状态及并发版本校验、幂等键、结果未知时对账、审计与回流全部可验证。用户看到“已受理”后，以权威业务回执和后续构建水位确认状态 |

**评审时优先确认三件事。** 业务侧选定一条真实链路并确认稳定主键及角色。平台侧确认试点数据集是否满足整表授权假设。服务侧确认目标适配器能在部署前提供本次关键测试的通过证据，并能在固定构建内高效地按键读取。三项确认后即可排定试点，先交付类型目录和指标入口，再开放实例数据。

