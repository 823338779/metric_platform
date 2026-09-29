# Metric Platform 规则

本仓库负责语义项目的版本发布、只读语义目录和查询门面，dbt/MetricFlow 是当前计算引擎实现。必须保持独立构建，并且不得依赖 Data Agent 的实现类。

## 项目结构

- `backend/`：Java 聚合模块；`metric-core` 负责平台契约、引擎端口与发布规则，`metric-persistence` 统一负责 PostgreSQL、Redis 与 Flyway，`metric-engine` 封装 dbt/MetricFlow HTTP、产物解析与项目 Git 读取；`metric-app` 承载 Spring Boot API、发布控制、认证流程与装配。
- Java 包按目录、发布、查询、身份等职责划分；dbt 专属实现放在 `metric-engine` 的 `com.metricplatform.engine.dbt` 下，平台业务类与持久化类不得使用 `Dbt` 前缀。
- `frontend/`：React/Vite dbt 项目、发布、目录和查询控制台。
- `api/openapi.yaml`：已发布的集成契约。
- `docker-compose.yml`：独立的 PostgreSQL 开发服务。

## 变更边界

- 平台持有发布指针和版本目录；dbt 项目持有指标、维度等定义，外部运行服务负责构建与查询执行。平台保留身份认证与展示界面。
- 当前平台使用全局身份认证模型，不按租户、工作区、角色或数据集授权进行隔离。除非需求明确要求，并且数据库迁移、实现和 OpenAPI 契约同步变更，否则不得重新引入这些语义。
- 身份认证、访问令牌生命周期、发布切换、版本引用、查询归因以及破坏性变更均属于安全或正确性边界。
- 有意修改公开 API 时，应更新 `api/openapi.yaml`，并确保路由、数据结构、身份认证、错误行为、实现、测试和调用方与其保持一致。
- Data Agent 必须通过已发布的 HTTP API 集成。不得导入或访问父工作区及 Data Agent 源码树中的实现。
- 类型化查询必须固定 `releaseId` 并交给外部运行服务；平台不得重新引入语义 SQL 编译与直接执行路径。
- 数据库结构变更必须使用带版本的 Flyway 迁移，且不得改写已经应用的迁移。

## 编码与验证

- 仅为非显而易见的语义规则、身份认证约束、SQL 生成不变量和破坏性操作添加注释；不要注释不言自明的代码。
- 不得为了让变更通过而删除、弱化或改写测试。修复缺陷或新增行为时，可以添加最小范围的回归测试；大范围调整测试体系必须获得用户明确授权。
- 严禁提交真实凭据、连接信息、令牌或客户数据。
- 使用 `README.md` 中记录的受支持命令。先运行受影响后端或前端范围内的最小测试；当变更跨越边界时，再扩大验证范围。
- 仓库中的 Maven Wrapper 不完整，因为缺少 `.mvn/wrapper/maven-wrapper.properties`。后端工作使用系统安装的 `mvn` 命令；前端命令从 `frontend/` 目录运行。
