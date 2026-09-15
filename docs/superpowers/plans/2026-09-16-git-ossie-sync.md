# Git 订阅与 Ossie 幂等消费实施计划

> 执行方式：本会话按任务逐项实现和验证。用户已要求按确认的协议实施；遵循工作区规则在当前 develop 目录开发，不创建 worktree。整个计划通过后创建一个本地提交，不推送。

**目标：** Git main 的 semantic/model.yaml 变更能够自动更新本平台的数据集、字段、维度、指标和关系，重复消费不重复创建，失败保持旧定义。

**架构：** 新增独立 Maven library 模块 metric-git-sync，负责协议解析、受限表达式与 Git 快照读取。backend 负责调度、回调、持久化和现有查询编译适配，单向依赖 library。Webhook 唤醒和周期轮询共享同一消费路径；平台 PostgreSQL 事务及检查点 CAS 保证发布原子性。

**技术：** Java 21、Spring Boot 4.1.0、MyBatis-Plus 3.5.16、Git CLI、Jackson 2/YAML、JSQLParser、固定的 Ossie 0.1.1 Schema。

**规格：** 本文接入决策及 data-agent-v2/docs/superpowers/specs/2026-09-16-ossie-metric-profile-design.md 中的协议。运行时不引用另一产品的实现类。

## 全局约束与接入决策

- 固定一个配置的仓库、refs/heads/main、semantic/model.yaml、已有 dataSourceId/schema、固定模型名；默认关闭同步。配置不来自回调载荷。
- 部署配置凭据，不写入定义文件或日志。Git 通过参数数组调用，不执行 shell、checkout、hook、submodule 或仓库脚本。
- 候选快照读取后才能开始业务发布事务。事务校验开始消费时的 appliedCommit，旧任务不能覆盖已发布的新版本。
- PostgreSQL 保存同步状态及对象归属。逻辑名映射稳定 UUID，只修改归属当前仓库的对象；与人工对象冲突时拒绝。删除仅影响当前仓库托管对象，存在外部引用时拒绝整个快照。
- 固定过滤存储为结构化条件，沿用参数绑定编译为指标级 CASE WHEN。不能改为数据集 WHERE。补齐 NOT_IN 与 COUNT_DISTINCT；关系 join_type 持久化并参与 SQL 编译。
- 现有元数据服务直接管理对象；Git 对象在管理写接口中拒绝手工修改，避免重复 SHA 跳过后人工漂移。
- 查询计划构建阶段以 PostgreSQL REPEATABLE_READ 固定元数据读取快照，不在实际数据查询期间持有元数据事务。
- 维度字段创建现有 DimensionEntity；度量字段仅创建 DatasetFieldEntity。TIME 开放既有 DAY/WEEK/MONTH/QUARTER/YEAR 粒度，默认 DAY，这是平台适配规则。
- 不自动重写用户对象、不绕过现有 SQL 编译器、不把平台状态反馈给 Agent 作为 push 成功条件。

## 任务 1：独立模块与协议解析

文件：根 pom.xml、metric-git-sync/pom.xml、该模块 OssieModel/OssieParser/MetricExpressionParser、固定 Schema、契约测试。

接口：`OssieParser.parse(byte[]) -> OssieModel`；Model 包含 Dataset/Field/Metric/Relationship；Metric 归一化为聚合、数据集、字段与固定 predicates。解析结果不带数据库 UUID。

- [x] 用协议完整示例先写测试，断言地区关系、条件聚合、同义词的归一化值；未知字段、错误版本、别名、重复键、尾随 SQL、OR、跨数据集引用、ELSE 0 必须失败。
- [x] 运行测试确认缺少实现失败。
- [x] 实现严格结构及表达式解析；JSON 扩展单独校验；字段及关系引用闭包校验。
- [x] 重跑模块测试。

核心验收形式：

```java
var model = new OssieParser().parse(yamlBytes);
assertThat(model.metrics().getFirst().aggregation()).isEqualTo("SUM");
assertThat(model.metrics().getFirst().predicates().getFirst().values()).containsExactly("PAID");
```

## 任务 2：Git 快照与后台订阅

文件：library 的 GitSnapshotReader/GitSnapshot；backend 的 gitsync 配置、协调器、Controller；OpenAPI；应用配置。

接口：`GitSnapshotReader.read() -> GitSnapshot(commit, yaml)`。回调只接受 repositoryId/ref 提示，必须验签；每次读取真正 main HEAD，不能信任载荷中的 URL、文件路径或 commit。

- [x] 临时真实 Git 仓库测试：初次读取、后续 push、相同 SHA、缺失文件、超大文件、符号链接拒绝。
- [x] 配置 Git 命令截止时间和输出上限；后台单飞，收到回调立即尝试，定时和启动时补偿。
- [x] 回调 HMAC-SHA256 校验；固定内部端点，独立于交互用户 token，不开放其他接口；过大请求或错误签名拒绝。
- [x] 增加状态读取接口，沿用已有 Bearer 身份认证；OpenAPI 同步描述。

核心验收形式：

```java
assertThat(reader.read().commit()).isEqualTo(expectedHead);
assertThat(reader.read().yaml()).containsExactly(expectedYaml);
```

## 任务 3：事务发布与稳定身份

文件：backend/gitsync 的导入适配；persistence Entity/Mapper/Service；下一条 Flyway 迁移；PostgreSQL 集成测试。

- [x] 写真实 PostgreSQL 集成测试，重复应用快照对象数和 UUID 不变，失败时业务数据与检查点同时回滚。
- [x] 保存 repo/model/kind/logicalName 归属及内部 UUID；每次发布在锁定检查点后验证 expected appliedCommit，避免过时结果覆盖。
- [x] 复用 SourceMetadataInspector 和 RelationshipCardinalityValidator 校验数据库环境；批量事务更新、补齐字段/维度/关系/指标；旧对象软删除，归属保留供恢复。
- [x] 管理写路径保护 Git 对象，防止人工改动破坏权威定义；冲突和外部引用删除测试。
- [x] 错误记录不改变 appliedCommit；相同成功 SHA 跳过，失败 SHA 可重试；相同内容的新 SHA 仍更新检查点。

核心验收形式：

```java
var first = importSnapshot(null, snapshot);
var repeated = importSnapshot(first.commit(), snapshot);
assertThat(repeated.objectIds()).isEqualTo(first.objectIds());
assertThatThrownBy(() -> importInvalidSnapshot()).isInstanceOf(RuntimeException.class);
assertThat(currentCommit()).isEqualTo(first.commit());
```

## 任务 4：编译适配与交付验证

文件：MetricEntity、DatasetRelationshipEntity、SemanticCatalogReader、QueryPlanner、SemanticSqlCompiler、FilterSqlCompiler/FilterValidator；契约与编译测试；README 和部署说明。

- [x] 测试两个指标使用不同固定过滤，条件保留在各自 CASE 中，参数按 SQL 占位符顺序绑定。
- [x] 测试 COUNT_DISTINCT 生成 COUNT(DISTINCT ...)，INNER 与 LEFT 保持不同语义，旧人工对象默认 LEFT 且无固定过滤。
- [x] 用元数据读取事务保证查询计划不会混用新旧定义。
- [x] 更新 README 构建命令、Git hook 示例、配置说明、错误与恢复流程。
- [x] 运行相关模块测试及 reactor verify，独立 review 完整差异；仅提交本计划变更。

## 调研纠正

最初协议文档以 Agent 内部语义目录为能力基准。实际 metric-platform 已有独立维度实体与关系唯一性检查，但没有指标固定过滤，且关联 SQL 固定 LEFT。实施时补齐这些消费适配，不能把读取 YAML 成功当成指标口径正确。当前行为事实以本仓库代码为准。


## 完成记录与验收边界

- 已实现计划四项任务，默认禁用，部署方法见 `docs/git-sync.md`。没有安装真实 Git hook、绑定远端或启用生产同步。
- 独立审查发现并修复：SQL 字符串转义、非标准 AST 标志、版本整数溢出、Webhook 类型强制转换、固定条件十进制精度、日期 JDBC 类型、旧人工日期格式兼容、物理列互换与墓碑恢复、部署绑定漂移、有损数值连接。
- 检查点固定模型名、数据源 UUID 和 schema；关系首版要求两侧规范化类型一致。对应边界和并发 CAS 均有真实 PostgreSQL 回归测试。
- `mvn -B clean verify`：library 7 项测试通过；backend 105 项中 104 项通过、1 项既有跳过，0 失败、0 错误。最终在后端 Spotless 检查失败，报告 124 个格式不符合文件，其中含未修改的历史文件；未为通过检查批量格式化无关代码。
- `mvn -B package -DskipTests`：全部模块成功打包；该命令复用上述测试证据，不冒充重新执行测试。
- library 的 Spotless 检查、后端全部 24 个新增 Java 文件的范围格式检查通过；`git diff --check` 通过。
- `python -B ops/git/tests/test_post_receive.py`：2 项通过，使用本地真实 HTTP 接收端验证分支筛选和原始字节 HMAC。
- `GitSyncHttpTest` 使用实际 Spring 路由与安全过滤链验证 202/401/413、状态 Bearer 边界；`GitImportServiceTest` 含 14 项真实 PostgreSQL 集成测试，只有外部业务数据源探测被替换。
- 现有 SQL 编译器仍使用反引号标识符。本次验证 PostgreSQL 元数据库事务和 JDBC 参数绑定，没有扩展 PostgreSQL 业务查询方言，也没有以真实生产 StarRocks 数据源做部署验收。
- 遵循工作区计划完成规则创建一个本地提交，不推送，不纳入任务过程中出现的无关图片文件。

- 最后补充字符串内分号/注释标记的有效值回归；`mvn -B -pl metric-git-sync spotless:apply verify -q` 全模块 8 项测试及格式检查通过。实际 SQL 注释和分号仍拒绝。
