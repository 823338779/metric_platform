# Metric Platform 多租户与账号实施计划

> 实施时可使用 superpowers:executing-plans 顺序执行，或使用 superpowers:subagent-driven-development 按任务实施与审阅。以下复选框用于跟踪执行状态。

**目标**　运维通过接口开通租户及唯一管理者；管理者通过页面创建只读账号。平台在访问环境、构建及派生资产前完成租户和账号权限校验。

**架构**　PostgreSQL 保存身份与资产归属。Spring Security 从当前账号关系构造身份，各业务入口以此限定访问范围。dbt-service 继续持有构建、部署和查询事实。

**技术栈**　Spring Boot、Spring Security、Flyway、PostgreSQL、React/Vite、OpenAPI、JUnit/Testcontainers、前端单测及 Playwright。沿用现有依赖。

**依据**　[已确认的设计（工作区文档）](../../../../docs/superpowers/specs/2026-11-19-metric-platform-tenancy-design.md)。本文代码路径相对于 metric-platform；Java 文件按实际模块和 com.metricplatform 包名定位。

## 实施验收记录（2026-11-19）

- 后端默认 `mvn -B verify` 通过，涵盖四个模块、146 项测试、打包及全量 Spotless 检查。
- 前端 167 项单测通过，默认 `npm run build` 完成全目录 Prettier 检查、TypeScript 检查和 Vite 生产构建。
- 在隔离 PostgreSQL 上演练 V18 → V19。两次生成同一清单，700 个分页构建与 1 个有运维证据的外部构建统一归属首租户。冲突清单被拒绝。核对了两个存量账号、唯一管理者和原令牌关联。
- 使用原版 dbt-service、真实 PostgreSQL 和 Redis 验证两个租户、只读账号、环境创建、构建和目录隔离。真实浏览器沿用固定构建查询断言，按当前页面默认已选时间维度调整临时脚本的点击步骤，验证月粒度结果 10.25、20.75 及维度值。
- 默认浏览器套件 6 项通过，包含两项新增账号管理用例。首页路径与资源树宽度断言已按当前行为更新。真实 PostgreSQL 专项在默认套件中按配置跳过，独立演练结果见上。生产未切换。

## 全局约束

- 每个账号属于一个租户。数据库字段为 `tenant_id`。编号为 `tn_00001` 至 `tn_99999`，允许跳号。登录名按现有 trim 和小写规则全平台唯一。登录仍只提交 username、password。
- 管理者对本租户全部资产有权限。只读账号初始可见资产为空。运维凭据独立于业务登录；本期无平台管理员角色、租户开通页面或资产授权表。
- dbt-service 仅面向可信内部调用方。沿用现有 HTTP 协议，保持 Data Agent、子模块指针和独立构建边界。仅在当前产品目录开发。
- 一个计划分七个验证任务，中间状态不对外上线。全部任务验收后在产品仓库形成一个实现提交。每次改动公开接口，同步维护 api/openapi.yaml、服务端行为和调用方测试。

## 审查重点

| 容易遗漏的输入或故障 | 验收任务 |
| --- | --- |
| 运维指定不存在、停用或仍锁定的账号；历史构建位于平台未登记环境 | 1、7 验证切换阻断与清单复核 |
| 大小写或空白不同的同名账号并发创建；事务失败后出现空租户 | 1、2 验证全局唯一性和原子性 |
| 存量令牌或浏览器残留旧身份，仍显示或访问旧资产 | 3、6、7 验证当前身份和跨登录缓存 |
| 远端已受理，本地记录失败，原键重试时环境已更新 | 4 验证快照及唯一归属 |
| 同仓库、同分支或重放游标，在列表、日志、部署指针或查询中暴露其他租户数据 | 5、7 验证逐入口授权 |

## 执行与验证约定

按 1 → 2 → 3 → 4 → 5 → 6 → 7 执行。每项先增加对应的失败用例，执行一次确认失败原因，完成实现后重复同一命令直至通过。后端从产品根目录使用以下 PowerShell 命令，将示例中的测试类替换为任务列出的类。数据库相关用例使用真实 Testcontainers PostgreSQL。

```powershell
mvn -f ./backend/pom.xml -B -pl metric-app -am '-Dtest=TenantMigrationTest,TenantProvisioningTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

### 1. 建立归属模型和可重跑的存量迁移

**落点**　metric-persistence 的 src/main/resources/db/migration/，当前末版 V18；产品 scripts/ 下新增租户迁移工具及其使用说明；metric-app 的 integration/persistence 测试包。

- [ ] 增加 TenantMigrationTest，覆盖 V18 存量库与空库两条路径。保留 AuthenticationOnlyMigrationTest 对 V10 移除旧模型的原断言，固定到对应历史 schema 版本；为最终 schema 新增独立断言。排查其余针对最新版本的旧契约断言，保留原有认证与安全回归覆盖。
- [ ] 新增版本迁移。platform_tenant 以 tenant_id 为主键，并以非空管理者 UUID 和同租户复合外键引用 platform_user；账号的非空租户外键与管理者引用支持同一事务内建租户和账号。沿用全局登录名约束。execution_environment 增加归属；建立以 build_id 为唯一键的构建归属记录，保存 tenant_id 与来源 environment_id。对仅有外部历史证据的环境允许记录来源 UUID，避免伪造可执行环境。保留 execution_namespace_owner 原物理唯一键。
- [ ] 将数据导入与 Flyway 的运行时机写清：先以独立运维工具按环境遍历现有公开历史 API，把冻结边界、环境及构建 ID、分页结束情况、统计与冲突写入暂存清单。清单并入已有提交记录和运维提供的外部历史，覆盖未部署及失败构建。Flyway 使用已预检的管理者登录名与暂存数据，在事务中回填、核对、施加非空和外键约束；Flyway 本身不发远端 HTTP 请求。
- [ ] 非空旧库缺管理者、归属冲突、遗漏环境、分页不完整或在途提交尚未核对时拒绝切换。清单可重复导入且归属不可覆盖。空库不预建空租户；空库首次开通取得 tn_00001。存量迁移固定 tn_00001，下一次开通取得 tn_00002。序列用尽明确失败，不循环。回归测试验证现有 UUID、密码散列、历史快照及令牌关系保留。

### 2. 运维接口原子开通租户

**落点**　metric-app 的 security/PlatformSecurityConfig.java、identity/bootstrap/PlatformIdentityBootstrap.java、identity/api/；metric-core 的 identity/PlatformIdentityRepository.java；metric-persistence 的 persistence/identity/PlatformIdentityStore.java；application.yml、api/openapi.yaml。

- [ ] 新增 TenantProvisioningTest。断言两个不同登录名并发开通后各有一个管理者；规范化后相同的登录名只有一个成功，另一请求为 409，库内没有孤立租户。验证编号耗尽和缺失运维凭据的情形。
- [ ] 新增专用运维安全链，仅匹配 POST /api/ops/tenants，验证通过部署注入的独立运维凭据；未配置时关闭入口。现有安全链含 anyRequest().permitAll()，须验证无凭据、无效凭据及普通用户 bearer 均不能开通。部署文档要求 HTTPS，日志屏蔽密码及凭据。
- [ ] 新接口输入 username、password，成功返回 201 和 tenant_id。服务在同一事务内分配租户 ID、预分配账号 UUID、插入租户和管理者，复用当前密码编码器与规范化规则。停用全局账号自动初始化路径，替换依赖该路径的测试数据准备。验证首次启动和重启都不会额外创建账号。

### 3. 会话身份与租户内创建只读账号

**落点**　metric-app 的 security/PlatformPrincipal.java、security/PlatformTokenAuthenticationFilter.java、identity/auth/PlatformAuthenticationService.java、identity/api/IdentityDtos.java、identity/api/PlatformIdentityController.java；沿用任务 2 的持久化端口。测试 PlatformAuthenticationServiceTest 和新增 TenantAccountApiTest。

- [ ] 写测试验证迁移前签发的有效令牌在 /api/v1/auth/me 上得到当前租户与角色；失效、过期、停用及 authVersion 校验照常生效。定义业务身份为账号 UUID、tenant_id、role，角色值 TENANT_MANAGER 或 READ_ONLY，通过唯一管理者引用推导。
- [ ] 登录响应的 identity 及 /me 增量返回 tenant_id、role；保留原字段和两字段登录请求。认证过滤器从当前账号关系构造 principal。更新 api/openapi.yaml 并验证 token 及 /me 返回一致。
- [ ] 新增 POST /api/v1/tenant/accounts，输入 username、password，返回 201 及新账号身份摘要。仅管理者可调用，目标租户取自 principal，角色固定为 READ_ONLY。测试两个租户登录名冲突为 409、只读发起创建为 403；提交额外的租户或角色字段不能改变归属和权限。审计保存操作者及目标身份，排除密码和令牌。

### 4. 环境隔离与幂等构建归属

**落点**　metric-core 的 environment/EnvironmentStore.java、BuildSubmissionStore.java；metric-app 的 environment/EnvironmentService.java、EnvironmentBuildService.java 及对应控制器；metric-persistence 的 persistence/store/ExecutionEnvironmentStore.java、EnvironmentBuildSubmissionStore.java。

- [ ] 扩展 EnvironmentApiTest、EnvironmentBuildServiceTest，新增真实库的 TenantBuildOwnershipTest。环境列表在 SQL 限定 tenant_id 后分页或限量；读写以租户和环境联合定位，创建时从 principal 注入归属。只读账号空列表，管理操作返回 403，管理者访问未知或其他租户的环境统一返回 404。
- [ ] 在平台部署配置中按 tenant_id 准入实例、凭据引用和 catalog/schema 的完整组合。按规范物理实例身份校验，创建和更新都检查；缺少配置时禁止创建、修改到未授权目标。保留现有配置版本并发控制和跨租户物理目标独占。测试别名、不同租户同一目标及越界更新。
- [ ] 将 EnvironmentBuildService.submit 的 actor 参数扩展为可信身份，在 reserve 前验证环境归属；事务内固定租户、账号、环境、配置版本和既有幂等快照。accepted 同一事务写回 build_id 和唯一构建归属；重复确认必须匹配原归属，冲突整体回滚。使用原远端请求身份恢复超时或本地落库失败；验证环境后来变更仍复用原请求，未确认构建不能被查询。

### 5. 对全部资产入口执行同一归属规则

**落点**　metric-app 的 catalog/api/BuildHistoryController.java、PublishedCatalogController.java、environment/api/EnvironmentBuildController.java、RepositoryController.java、query/api/QueryController.java、query/QueryService.java；metric-persistence 的 persistence/store/BuildQueryAuditStore.java。新增 TenantAssetAccessTest，复用 BuildApiContractTest、SemanticOpenApiContractTest。

- [ ] 按 OpenAPI 生成端点验收表，逐项覆盖环境列表与详情、环境构建历史、全局构建历史、分支、部署历史和当前部署、固定构建详情、日志、目录、资源及 Manifest 内容、血缘、可用维度和查询。对两个同仓库租户和只读账号执行；公开 GET /api/dbt/contract 维持原行为。
- [ ] 共用服务端归属检查：环境入口先查本地租户与环境，构建入口先查本地租户与 build_id。跨租户与不存在的详情返回相同 404；无权限的查询或管理操作返回 403，不触发远端查询。只读账号得到契约允许的空集合。无权读取单项的响应不可包含资产信息。
- [ ] 历史接口按已授权环境调用 dbt-service，再按可信构建归属选择记录；部署指针及其嵌套构建也须校验。处理上游分页直到得到授权记录或耗尽，不把上游总数或未过滤页直接返回。分页状态绑定租户、环境、调用入口及原筛选，跨范围重放拒绝。对同环境内夹杂未知构建、首个上游页无可见记录、两租户交换游标、直接访问日志和深链接写定向测试。若有缓存，将授权范围纳入键。
- [ ] 查询成功审计可按租户、账号和构建定位；校验拒绝发生在查询网关调用前。保留上游服务错误与本地 401/403/404 的契约区别。更新相关 OpenAPI 响应及契约测试。

### 6. 交付管理者账号页面与只读状态

**落点**　frontend/src/api/auth.ts、authApi.ts、types.ts、auth/AuthenticationGuard.tsx、routes.tsx、layout/PlatformLayout.tsx；新增 features/accounts/TenantAccountsPage.tsx 及同目录测试。

- [ ] 用 TenantAccountsPage.test.tsx 和 PlatformLayout.test.tsx 覆盖管理者创建只读账号、重复登录名反馈、只读账号直接打开管理路由、旧会话刷新和切换账号。
- [ ] AuthenticationGuard 使用 /me 返回的当前身份替换旧会话摘要。账号管理页只收登录名和初始密码，成功后清空密码；登录页保持两字段。只读账号显示空资产状态，隐藏操作入口。登出及切换身份时清理上个账号的资产数据，资源深链接正确呈现拒绝访问结果。
- [ ] 在 frontend/ 运行 `npm test -- src/features/accounts/TenantAccountsPage.test.tsx src/layout/PlatformLayout.test.tsx src/features/auth/LoginPage.test.tsx` 和 `npm run build`。确认业务账号界面没有运维开通入口。

### 7. 存量切换演练与交付

**落点**　产品 README.md、scripts/ 的迁移使用说明、frontend 的既有 E2E 测试目录。同步更新 AGENTS.md 中“当前全局身份”的现状描述。生产迁移由运维按发布窗口另行执行。

- [ ] 用 V18 数据库副本或合成夹具演练。备份数据库；冻结涉及的写入和旧实例流量；核对在途及外部历史清单；指定现有可登录管理者；为 tn_00001 配置目标范围并核验底层凭据权限；执行迁移。逐项比对账号、环境、构建集合与唯一管理者。异常时保持停流，在恢复写入前用备份和匹配的应用版本回退并核对记录。
- [ ] 以现有版本 dbt-service 做端到端验收：存量管理者查看历史、提交构建和查询；存量只读账号含旧令牌看不到资产；运维开通 tn_00002，管理者创建只读账号；两个租户同仓库无法互读、互写。核验用户侧网络无法绕过平台直连 dbt-service。先在隔离环境验证，再由运维恢复流量。
- [ ] 在 backend/ 运行 `mvn -B verify`，在 frontend/ 运行 `npm test`、`npm run build`、`npm run test:e2e`。真实 PostgreSQL 浏览器验收按 README 的 fixture 和环境变量执行并单独记录结果。协议桩结果与真实集成结果分别记录。
- [ ] 检查产品仓库 diff、根仓库 `git status --ignore-submodules=none` 和 `git diff --ignore-submodules=none`，核对仅包含本计划产物。实现验收后在 metric-platform 提交，建议消息为 `feat: 实现租户账号管理与资产隔离`。
